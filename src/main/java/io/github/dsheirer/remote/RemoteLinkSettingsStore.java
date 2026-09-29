/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */
package io.github.dsheirer.remote;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Portable receiver-owned remote-link settings.  This file contains link credentials; never serialize a Settings
 * instance into diagnostics, an HTTP response, or a log.
 */
public final class RemoteLinkSettingsStore
{
    public static final String DEFAULT_BIND_ADDRESS = "0.0.0.0";
    private static final Set<PosixFilePermission> OWNER_ONLY = Set.of(
        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    private final Path mPath;
    private final ObjectMapper mMapper = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public RemoteLinkSettingsStore(Path dataRoot)
    {
        mPath = dataRoot.toAbsolutePath().normalize().resolve("remote-links").resolve("settings.json");
    }

    public synchronized Settings load() throws IOException
    {
        if(!Files.exists(mPath))
        {
            return Settings.defaults();
        }
        if(Files.isSymbolicLink(mPath) || Files.isSymbolicLink(mPath.getParent()) ||
            !Files.isRegularFile(mPath) || Files.size(mPath) > 256_000)
        {
            throw new IOException("Remote-link settings file is invalid");
        }
        ownerOnly(mPath.getParent(), true);
        ownerOnly(mPath, false);
        try
        {
            Settings settings = mMapper.readValue(mPath.toFile(), Settings.class);
            validate(settings);
            return settings;
        }
        catch(JsonProcessingException | RuntimeException exception)
        {
            // Jackson diagnostics can quote a malformed credential value. Never put its cause in receiver logs.
            throw new IOException("Remote-link settings file is invalid");
        }
    }

    public synchronized void save(Settings settings) throws IOException
    {
        validate(settings);
        Files.createDirectories(mPath.getParent());
        if(Files.isSymbolicLink(mPath.getParent())) throw new IOException("Remote-link settings path is invalid");
        ownerOnly(mPath.getParent(), true);
        Path temporary = Files.createTempFile(mPath.getParent(), "settings-", ".tmp");
        try
        {
            ownerOnly(temporary, false);
            mMapper.writeValue(temporary.toFile(), settings);
            try
            {
                Files.move(temporary, mPath, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            }
            catch(AtomicMoveNotSupportedException ignored)
            {
                Files.move(temporary, mPath, StandardCopyOption.REPLACE_EXISTING);
            }
            ownerOnly(mPath, false);
        }
        finally
        {
            Files.deleteIfExists(temporary);
        }
    }

    private static void validate(Settings settings) throws IOException
    {
        if(settings == null || settings.revision() < 1L || !validPort(settings.listenPort()) ||
            settings.bindAddress() == null || settings.bindAddress().length() > 255 ||
            settings.trustedSenders().size() > 256)
        {
            throw new IOException("Remote-link settings are invalid");
        }
        Set<String> ids = new HashSet<>();
        for(TrustedSender sender: settings.trustedSenders())
        {
            if(sender == null || !validUuid(sender.senderId()) || !ids.add(sender.senderId()) ||
                sender.displayName() == null || sender.displayName().isBlank() ||
                sender.displayName().length() > 120 || sender.pairedAtMs() < 0L ||
                !sender.revoked() && (sender.secret() == null || sender.secret().isBlank()) ||
                sender.secret() != null && sender.secret().length() > 256 ||
                sender.defaultAliasListId() != null && sender.defaultAliasListId() <= 0L)
            {
                throw new IOException("Remote-link settings are invalid");
            }
        }
        Outbound outbound = settings.outbound();
        if(outbound == null || !validPort(outbound.port()) || outbound.host() == null ||
            outbound.host().length() > 255 ||
            outbound.exportedChannelIds().size() > RemoteWireMessages.MAXIMUM_FEEDS ||
            outbound.enabled() && (outbound.host().isBlank() || !validUuid(outbound.senderId()) ||
                outbound.secret() == null || outbound.secret().isBlank()) ||
            outbound.senderId() != null && !validUuid(outbound.senderId()) ||
            outbound.secret() != null && outbound.secret().length() > 256)
        {
            throw new IOException("Remote-link settings are invalid");
        }
        Set<String> exports = new HashSet<>();
        for(String id: outbound.exportedChannelIds())
        {
            if(!validUuid(id) || !exports.add(id))
            {
                throw new IOException("Remote-link settings are invalid");
            }
        }
    }

    private static boolean validPort(int port)
    {
        return port >= 1 && port <= 65_535;
    }

    private static boolean validUuid(String value)
    {
        if(value == null) return false;
        try { return UUID.fromString(value).toString().equals(value); }
        catch(IllegalArgumentException ignored) { return false; }
    }

    private static void ownerOnly(Path path, boolean directory) throws IOException
    {
        boolean protectedByFileSystem = false;
        PosixFileAttributeView posix = Files.getFileAttributeView(path, PosixFileAttributeView.class);
        if(posix != null)
        {
            Set<PosixFilePermission> permissions = directory ? Set.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE) : OWNER_ONLY;
            posix.setPermissions(permissions);
            if(!posix.readAttributes().permissions().equals(permissions))
            {
                throw new IOException("Unable to restrict remote-link settings permissions");
            }
            protectedByFileSystem = true;
        }
        AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class);
        if(acl != null)
        {
            UserPrincipal owner = acl.getOwner();
            AclEntry.Builder entry = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner)
                .setPermissions(EnumSet.allOf(AclEntryPermission.class));
            if(directory) entry.setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT);
            acl.setAcl(List.of(entry.build()));
            List<AclEntry> actual = acl.getAcl();
            if(actual.isEmpty() || actual.stream().anyMatch(rule -> !owner.equals(rule.principal())))
            {
                throw new IOException("Unable to restrict remote-link settings access control");
            }
            protectedByFileSystem = true;
        }
        if(!protectedByFileSystem) throw new IOException("Remote-link settings filesystem has no access controls");
    }

    public record TrustedSender(String senderId, String displayName, String secret, boolean autoAdopt,
                                Long defaultAliasListId, long pairedAtMs, boolean revoked)
    {
        @Override
        public String toString()
        {
            return "TrustedSender[senderId=" + senderId + ", secret=REDACTED, revoked=" + revoked + "]";
        }
    }

    public record Outbound(boolean enabled, String host, int port, String senderId, String secret,
                           List<String> exportedChannelIds)
    {
        public Outbound
        {
            exportedChannelIds = exportedChannelIds != null ? List.copyOf(exportedChannelIds) : List.of();
        }

        public static Outbound disabled()
        {
            return new Outbound(false, "", 53_800, null, null, List.of());
        }

        @Override
        public String toString()
        {
            return "Outbound[enabled=" + enabled + ", senderId=" + senderId + ", secret=REDACTED]";
        }
    }

    public record Settings(long revision, boolean listenerEnabled, String bindAddress, int listenPort,
                           List<TrustedSender> trustedSenders, Outbound outbound)
    {
        public Settings
        {
            trustedSenders = trustedSenders != null ? List.copyOf(trustedSenders) : List.of();
            outbound = outbound != null ? outbound : Outbound.disabled();
        }

        public static Settings defaults()
        {
            return new Settings(1, false, DEFAULT_BIND_ADDRESS, 53_800, List.of(), Outbound.disabled());
        }

        @Override
        public String toString()
        {
            return "Settings[revision=" + revision + ", credentials=REDACTED]";
        }
    }
}
