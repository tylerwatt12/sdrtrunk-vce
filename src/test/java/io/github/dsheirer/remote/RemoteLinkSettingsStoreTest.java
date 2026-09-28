/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.remote;

import io.github.dsheirer.remote.RemoteLinkSettingsStore.Outbound;
import io.github.dsheirer.remote.RemoteLinkSettingsStore.Settings;
import io.github.dsheirer.remote.RemoteLinkSettingsStore.TrustedSender;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RemoteLinkSettingsStoreTest
{
    @TempDir
    Path mTemp;

    @Test
    void savesVersionedSenderCredentialsOutsideTheChannelDatabase() throws Exception
    {
        RemoteLinkSettingsStore store = new RemoteLinkSettingsStore(mTemp);
        assertFalse(store.load().listenerEnabled());
        String senderId = UUID.randomUUID().toString();
        String channelId = UUID.randomUUID().toString();
        Settings settings = new Settings(2, true, "127.0.0.1", 53_800,
            List.of(new TrustedSender(senderId, "West site", "generated-key", true, 14L, 123L, false)),
            new Outbound(true, "vpn-host", 53_800, senderId, "generated-key", List.of(channelId)));

        store.save(settings);

        assertEquals(settings, store.load());
        assertFalse(settings.toString().contains("generated-key"));
        assertFalse(settings.trustedSenders().getFirst().toString().contains("generated-key"));
        assertFalse(settings.outbound().toString().contains("generated-key"));
        Path saved = mTemp.resolve("remote-links/settings.json");
        assertTrue(Files.isRegularFile(saved));
        assertTrue(Files.size(saved) > 0L);
        try
        {
            assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(saved));
            assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE), Files.getPosixFilePermissions(saved.getParent()));
        }
        catch(UnsupportedOperationException ignored)
        {
            // ACL-backed platforms do not expose POSIX permissions.
        }
    }

    @Test
    void malformedCredentialNeverAppearsInASettingsError() throws Exception
    {
        Path saved = mTemp.resolve("remote-links/settings.json");
        Files.createDirectories(saved.getParent());
        Files.writeString(saved, "{\"revision\":2,\"listenerEnabled\":false," +
            "\"bindAddress\":\"127.0.0.1\",\"listenPort\":53800," +
            "\"trustedSenders\":[],\"outbound\":{\"enabled\":false," +
            "\"host\":\"\",\"port\":53800,\"secret\":{\"sensitive-sentinel\":1}}}");

        IOException error = assertThrows(IOException.class, () -> new RemoteLinkSettingsStore(mTemp).load());
        assertFalse(error.toString().contains("sensitive-sentinel"));
        assertNull(error.getCause());
    }
}
