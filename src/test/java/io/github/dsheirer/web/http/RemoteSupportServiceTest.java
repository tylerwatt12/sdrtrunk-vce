/* Copyright (C) 2026. Licensed under GPL-3.0-or-later. */
package io.github.dsheirer.web.http;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RemoteSupportServiceTest
{
    @TempDir Path mRoot;
    private static final long NOW = 1800000000000L;
    private static Clock at(long time) { return Clock.fixed(Instant.ofEpochMilli(time), ZoneOffset.UTC); }

    @Test void restartPreservesPermissionAndOriginalDeadlineWithoutExposingCredentials() throws Exception
    {
        try(RemoteSupportService service = new RemoteSupportService(mRoot, null, at(NOW), false))
        {
            service.start(21600);
            Map<String,Object> status = service.status(true);
            assertEquals(true, status.get("enabled"));
            assertEquals(NOW + 21600000, status.get("expires_at_ms"));
            RemoteSupportGrantStore.Grant grant = new RemoteSupportGrantStore(mRoot).read().grant();
            assertFalse(status.toString().contains(grant.secret()));
            assertFalse(status.toString().contains(grant.grant_id()));
            assertFalse(grant.toString().contains(grant.secret()));
        }
        try(RemoteSupportService restarted = new RemoteSupportService(mRoot, null, at(NOW + 3600000), false))
        {
            assertEquals(true, restarted.status(true).get("enabled"));
            assertEquals(NOW + 21600000, restarted.status(true).get("expires_at_ms"));
        }
        try(RemoteSupportService expired = new RemoteSupportService(mRoot, null, at(NOW + 21600000), false))
        { assertEquals(false, expired.status(true).get("enabled")); }
    }

    @Test void unlimitedAndOfflineStopSurviveRestartAndRetainRevocation() throws Exception
    {
        String grantId;
        try(RemoteSupportService service = new RemoteSupportService(mRoot, null, at(NOW), false))
        {
            service.start(0);
            grantId = new RemoteSupportGrantStore(mRoot).read().grant().grant_id();
        }
        try(RemoteSupportService restarted = new RemoteSupportService(mRoot, null, at(NOW + 31536000000L), false))
        {
            assertEquals(true, restarted.status(true).get("enabled"));
            assertEquals(true, restarted.status(true).get("unlimited"));
            restarted.stop();
        }
        try(RemoteSupportService stopped = new RemoteSupportService(mRoot, null, at(NOW), false))
        {
            assertEquals(false, stopped.status(true).get("enabled"));
            RemoteSupportGrantStore.State saved = new RemoteSupportGrantStore(mRoot).read();
            assertNull(saved.grant());
            assertEquals(grantId, saved.pending_revocations().getFirst().grant_id());
        }
    }

    @Test void rejectsUnsupportedDurationAndCorruptStateWithoutReplacingIt() throws Exception
    {
        try(RemoteSupportService service = new RemoteSupportService(mRoot, null, at(NOW), false))
        { assertThrows(IllegalArgumentException.class, () -> service.start(5)); }
        Path directory = Files.createDirectories(mRoot.resolve("remote-support"));
        Path file = directory.resolve("grant.json");
        Files.writeString(file, "{bad state}");
        assertThrows(java.io.IOException.class, () -> new RemoteSupportService(mRoot, null, at(NOW), false));
        assertEquals("{bad state}", Files.readString(file));
    }

    @Test void stateIsPrivateOnPosixFilesystems() throws Exception
    {
        try(RemoteSupportService service = new RemoteSupportService(mRoot, null, at(NOW), false)) { service.start(1800); }
        Path directory = mRoot.resolve("remote-support");
        if(Files.getFileAttributeView(directory, PosixFileAttributeView.class) != null)
        {
            assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(directory));
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(directory.resolve("grant.json")));
        }
    }

    @Test void failedStopCannotReconnectAndSavingStopCanBeRetried() throws Exception
    {
        class FailingStore extends RemoteSupportGrantStore
        {
            boolean fail;
            FailingStore() { super(mRoot); }
            @Override void write(State state) throws java.io.IOException
            { if(fail) throw new java.io.IOException("simulated unavailable storage"); super.write(state); }
            @Override void removeActiveState() throws java.io.IOException
            { if(fail) throw new java.io.IOException("simulated unavailable storage"); super.removeActiveState(); }
        }
        FailingStore store = new FailingStore();
        try(RemoteSupportService service = new RemoteSupportService(store, null, at(NOW), false,
            java.net.URI.create("ws://127.0.0.1/unused"), java.net.URI.create("http://127.0.0.1/unused")))
        {
            service.start(0);
            store.fail = true;
            assertThrows(RemoteSupportService.StopPersistenceException.class, service::stop);
            assertEquals(false, service.status(true).get("enabled"));
            assertEquals(true, service.status(true).get("stop_not_saved"));
            assertEquals(true, service.status(true).get("stop_not_saved"), "status polling must preserve the warning");
            assertTrue(service.status(true).get("message").toString().contains("before restarting"));
            store.fail = false;
            service.stop();
            assertEquals(false, service.status(true).get("stop_not_saved"));
        }
        try(RemoteSupportService restarted = new RemoteSupportService(mRoot, null, at(NOW), false))
        { assertEquals(false, restarted.status(true).get("enabled")); }
    }

    @Test void successfulFailClosedDeletionDoesNotWarnAboutRestart() throws Exception
    {
        class FailingWriteStore extends RemoteSupportGrantStore
        {
            boolean fail;
            FailingWriteStore() { super(mRoot); }
            @Override void write(State state) throws java.io.IOException
            { if(fail) throw new java.io.IOException("simulated full storage"); super.write(state); }
        }
        FailingWriteStore store = new FailingWriteStore();
        try(RemoteSupportService service = new RemoteSupportService(store, null, at(NOW), false,
            java.net.URI.create("ws://127.0.0.1/unused"), java.net.URI.create("http://127.0.0.1/unused")))
        {
            service.start(0);
            store.fail = true;
            service.stop();
            assertEquals(false, service.status(true).get("enabled"));
            assertEquals(false, service.status(true).get("stop_not_saved"));
            assertNull(store.read().grant(), "removing stale permission must keep support disabled after restart");
        }
    }
}
