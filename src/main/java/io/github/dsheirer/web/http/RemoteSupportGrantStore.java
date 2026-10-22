/* Copyright (C) 2026. Licensed under GPL-3.0-or-later. */
package io.github.dsheirer.web.http;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.List;

/** Private application-owned state. No credentials are stored in the web preferences or activity database. */
class RemoteSupportGrantStore
{
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final Path mFile;

    RemoteSupportGrantStore(Path applicationRoot)
    {
        mFile = applicationRoot.resolve("remote-support").resolve("grant.json");
    }

    State read() throws IOException
    {
        if(!Files.exists(mFile, LinkOption.NOFOLLOW_LINKS)) return new State(1, null, List.of());
        if(!Files.isRegularFile(mFile, LinkOption.NOFOLLOW_LINKS) || Files.size(mFile) > 32768)
            throw new IOException("Remote support state is not a bounded regular file");
        privateAccess(mFile.getParent(), true);
        privateAccess(mFile, false);
        State state = JSON.readValue(Files.readAllBytes(mFile), State.class);
        if(state == null || state.version() != 1 || state.pending_revocations() == null ||
            state.pending_revocations().size() > 16) throw new IOException("Remote support state is invalid");
        if(state.grant() != null) state.grant().validate();
        for(Grant pending: state.pending_revocations()) pending.validate();
        return state;
    }

    void write(State state) throws IOException
    {
        Path directory = mFile.getParent();
        if(Files.exists(directory, LinkOption.NOFOLLOW_LINKS) &&
            !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Remote support directory is invalid");
        Files.createDirectories(directory);
        privateAccess(directory, true);
        Path temporary = Files.createTempFile(directory, ".grant-", ".tmp");
        try
        {
            privateAccess(temporary, false);
            byte[] bytes = JSON.writeValueAsBytes(state);
            try(FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE))
            {
                java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes);
                while(buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            Files.move(temporary, mFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            syncDirectory(directory);
        }
        finally { Files.deleteIfExists(temporary); }
    }

    /** Last-resort fail-closed fallback when an atomic disabled-state replacement cannot be written. */
    void removeActiveState() throws IOException
    {
        Files.deleteIfExists(mFile);
        syncDirectory(mFile.getParent());
    }

    /** POSIX rename/deletion durability requires the directory entry as well as the file contents to be synced. */
    private static void syncDirectory(Path directory) throws IOException
    {
        // Windows does not expose directory FileChannels. Do not suppress actual POSIX sync failures.
        if(Files.getFileAttributeView(directory, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS) == null) return;
        try(FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) { channel.force(true); }
    }

    private static void privateAccess(Path path, boolean directory) throws IOException
    {
        if(Files.isSymbolicLink(path)) throw new IOException("Remote support state cannot use symbolic links");
        PosixFileAttributeView posix = Files.getFileAttributeView(path, PosixFileAttributeView.class,
            LinkOption.NOFOLLOW_LINKS);
        if(posix != null) posix.setPermissions(PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
        AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class,
            LinkOption.NOFOLLOW_LINKS);
        if(acl != null)
        {
            AclEntry.Builder entry = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(acl.getOwner())
                .setPermissions(EnumSet.allOf(AclEntryPermission.class));
            if(directory) entry.setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT);
            acl.setAcl(List.of(entry.build()));
        }
    }

    record State(int version, Grant grant, List<Grant> pending_revocations)
    {
        State { pending_revocations = List.copyOf(pending_revocations); }
        @Override public String toString() { return "RemoteSupportState[redacted]"; }
    }

    record Grant(String grant_id, String secret, long expires_at_ms, boolean unlimited, String code)
    {
        void validate() throws IOException
        {
            if(grant_id == null || !grant_id.matches("[0-9a-f]{32}") || secret == null ||
                !secret.matches("[0-9a-f]{64}") || unlimited != (expires_at_ms == 0) || expires_at_ms < 0 ||
                code == null || !code.isEmpty() && !code.matches("[0-9]{9}"))
                throw new IOException("Remote support grant is invalid");
        }
        boolean expired(long now) { return !unlimited && now >= expires_at_ms; }
        String host() { return "vce-" + grant_id + ".radioresolve.com"; }
        Grant withCode(String value) { return new Grant(grant_id, secret, expires_at_ms, unlimited, value); }
        @Override public String toString() { return "RemoteSupportGrant[redacted]"; }
    }
}
