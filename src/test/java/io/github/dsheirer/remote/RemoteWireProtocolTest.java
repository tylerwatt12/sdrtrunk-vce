/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.remote;

import io.github.dsheirer.remote.RemoteWireProtocol.Frame;
import io.github.dsheirer.remote.RemoteWireProtocol.Type;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RemoteWireProtocolTest
{
    @Test
    void framesRoundTripAndRejectInvalidLengths() throws Exception
    {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        RemoteWireProtocol.write(new DataOutputStream(bytes), new Frame(Type.CATALOG, new byte[]{1, 2, 3}));
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()));
        Frame frame = RemoteWireProtocol.read(input);
        assertEquals(Type.CATALOG, frame.type());
        assertArrayEquals(new byte[]{1, 2, 3}, frame.payload());
        assertNull(RemoteWireProtocol.read(input));

        assertThrows(IOException.class, () -> readFrameWithLength(0));
        assertThrows(IOException.class, () -> readFrameWithLength(RemoteWireProtocol.MAXIMUM_FRAME_BYTES + 1));
    }

    @Test
    void dataAndCatalogRoundTripWithBoundedPayloads() throws Exception
    {
        String streamId = UUID.randomUUID().toString();
        RemoteWireMessages.Data data = new RemoteWireMessages.Data(streamId, 9, 12, 1_000,
            new byte[]{0x10, 0x20});
        RemoteWireMessages.Data restored = RemoteWireMessages.decodeData(RemoteWireMessages.encodeData(data));
        assertEquals(streamId, restored.streamId());
        assertEquals(9, restored.generation());
        assertEquals(12, restored.sequence());
        assertEquals(1_000, restored.captureTimestamp());
        assertArrayEquals(new byte[]{0x10, 0x20}, restored.dibits());
        assertThrows(IOException.class, () -> RemoteWireMessages.decodeData(new byte[40]));
        assertThrows(IllegalArgumentException.class, () -> new RemoteWireMessages.Data(streamId, 1, 1, 1,
            new byte[RemoteWireMessages.MAXIMUM_DIBIT_BYTES + 1]));

        RemoteWireMessages.Catalog catalog = new RemoteWireMessages.Catalog(List.of(
            new RemoteWireMessages.Feed(UUID.randomUUID().toString(), "System", "Site", "Control",
                "P25_PHASE1", 851_012_500L, true)));
        assertEquals(catalog, RemoteWireMessages.parse(RemoteWireMessages.json(catalog),
            RemoteWireMessages.Catalog.class));
    }

    private static void readFrameWithLength(int length) throws IOException
    {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        new DataOutputStream(bytes).writeInt(length);
        RemoteWireProtocol.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));
    }
}
