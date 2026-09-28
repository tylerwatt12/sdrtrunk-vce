/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.remote;

import io.github.dsheirer.remote.RemoteWireProtocol.Frame;
import io.github.dsheirer.remote.RemoteWireProtocol.Type;
import java.io.IOException;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteLinkTransportTest
{
    @Test
    void authenticatesAndSeparatesConcurrentSendersAndRejectsUnknownKeys() throws Exception
    {
        String firstId = UUID.randomUUID().toString();
        String secondId = UUID.randomUUID().toString();
        Map<String,String> keys = Map.of(firstId, "first-shared-key", secondId, "second-shared-key");
        CountDownLatch received = new CountDownLatch(2);
        Map<String,Byte> payloads = new ConcurrentHashMap<>();
        RemoteLinkTransport.Listener hostListener = new RemoteLinkTransport.Listener()
        {
            @Override
            public void connected(String senderId, RemoteLinkTransport.Connection connection)
            {
            }

            @Override
            public void frame(String senderId, RemoteLinkTransport.Connection connection, Frame frame)
            {
                if(frame.type() == Type.CATALOG)
                {
                    payloads.put(senderId, frame.payload()[0]);
                    received.countDown();
                }
            }

            @Override
            public void disconnected(String senderId, RemoteLinkTransport.Connection connection)
            {
            }
        };

        try(RemoteLinkTransport.Server server = RemoteLinkTransport.listen("127.0.0.1", 0, keys::get,
            hostListener))
        {
            assertThrows(IOException.class, () -> RemoteLinkTransport.connect("127.0.0.1", server.port(),
                firstId, "wrong-key", NO_OP_LISTENER));
            assertThrows(IOException.class, () -> RemoteLinkTransport.connect("127.0.0.1", server.port(),
                UUID.randomUUID().toString(), "first-shared-key", NO_OP_LISTENER));

            try(RemoteLinkTransport.Connection first = RemoteLinkTransport.connect("127.0.0.1", server.port(),
                firstId, keys.get(firstId), NO_OP_LISTENER);
                RemoteLinkTransport.Connection second = RemoteLinkTransport.connect("127.0.0.1", server.port(),
                    secondId, keys.get(secondId), NO_OP_LISTENER))
            {
                assertNotEquals(first.sessionId(), second.sessionId());
                assertTrue(first.offer(new Frame(Type.CATALOG, new byte[]{1})));
                assertTrue(second.offer(new Frame(Type.CATALOG, new byte[]{2})));
                assertTrue(received.await(2, TimeUnit.SECONDS));
                assertEquals((byte)1, payloads.get(firstId));
                assertEquals((byte)2, payloads.get(secondId));
                assertThrows(IOException.class, () -> RemoteLinkTransport.connect("127.0.0.1", server.port(),
                    firstId, keys.get(firstId), NO_OP_LISTENER));
            }
        }
    }

    @Test
    void rejectsAHostThatCannotProveItKnowsTheSharedKey() throws Exception
    {
        String senderId = UUID.randomUUID().toString();
        try(ServerSocket imposter = new ServerSocket(0, 1, InetAddress.getLoopbackAddress()))
        {
            Thread responder = Thread.ofVirtual().start(() ->
            {
                try(Socket socket = imposter.accept())
                {
                    socket.setSoTimeout(2_000);
                    DataInputStream input = new DataInputStream(socket.getInputStream());
                    DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                    RemoteWireProtocol.read(input); // HELLO
                    RemoteWireProtocol.write(output, new Frame(Type.CHALLENGE, new byte[32]));
                    output.flush();
                    RemoteWireProtocol.read(input); // AUTH
                    RemoteWireProtocol.write(output, new Frame(Type.ACCEPT, RemoteWireMessages.json(
                        new RemoteWireMessages.Accepted(UUID.randomUUID().toString(), "bad-proof"))));
                    output.flush();
                }
                catch(IOException ignored)
                {
                    // The client is expected to reject the imposter and close its socket.
                }
            });
            assertThrows(IOException.class, () -> RemoteLinkTransport.connect("127.0.0.1", imposter.getLocalPort(),
                senderId, "sender-secret", NO_OP_LISTENER));
            responder.join(2_000);
        }
    }

    private static final RemoteLinkTransport.Listener NO_OP_LISTENER = new RemoteLinkTransport.Listener()
    {
        @Override
        public void connected(String senderId, RemoteLinkTransport.Connection connection)
        {
        }

        @Override
        public void frame(String senderId, RemoteLinkTransport.Connection connection, Frame frame)
        {
        }

        @Override
        public void disconnected(String senderId, RemoteLinkTransport.Connection connection)
        {
        }
    };
}
