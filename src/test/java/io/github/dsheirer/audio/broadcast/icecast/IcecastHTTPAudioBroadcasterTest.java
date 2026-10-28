/*
 * Copyright (C) 2026 Dennis Sheirer
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.dsheirer.audio.broadcast.icecast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.application.ApplicationInfo;
import io.github.dsheirer.audio.broadcast.BroadcastState;
import io.github.dsheirer.audio.convert.InputAudioFormat;
import io.github.dsheirer.audio.convert.MP3Setting;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.mina.core.buffer.IoBuffer;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.filter.codec.ProtocolDecoderException;
import org.apache.mina.http.HttpRequestImpl;
import org.apache.mina.http.api.DefaultHttpResponse;
import org.apache.mina.http.api.HttpMethod;
import org.apache.mina.http.api.HttpStatus;
import org.apache.mina.http.api.HttpVersion;
import org.junit.jupiter.api.Test;

class IcecastHTTPAudioBroadcasterTest
{
    @Test
    void putRequestsContinueWithoutChangingMountpointCredentialsOrApplicationIdentity() throws Exception
    {
        Fixture fixture = new Fixture();
        fixture.handler.sessionOpened(fixture.session);

        HttpRequestImpl request = assertInstanceOf(HttpRequestImpl.class, fixture.writes.getFirst());
        assertEquals(HttpMethod.PUT, request.getMethod());
        assertEquals(HttpVersion.HTTP_1_1, request.getProtocolVersion());
        assertEquals("/synthetic-stream", request.getRequestPath());
        assertEquals("100-continue", request.getHeader("Expect"));
        assertEquals(fixture.configuration.getBase64EncodedCredentials(), request.getHeader("Authorization"));
        assertEquals(ApplicationInfo.getDisplayName(), request.getHeader("User-Agent"));
        assertEquals("audio/mpeg", request.getHeader("Content-Type"));
    }

    @Test
    void continueAllowsAudioBeforeIcecast25SendsItsFinalPutResponse() throws Exception
    {
        Fixture fixture = new Fixture();
        fixture.broadcaster.broadcastAudio(new byte[]{1}, null);
        assertTrue(fixture.writes.isEmpty(), "The connecting stream must wait for the server");

        fixture.respond(HttpVersion.HTTP_1_1, HttpStatus.INFORMATIONAL_CONTINUE);
        fixture.assertConnected();
        fixture.assertAudioWritten(new byte[]{2, 3});

        fixture.respond(HttpVersion.HTTP_1_1, HttpStatus.SUCCESS_OK);
        fixture.assertConnected();
        fixture.assertAudioWritten(new byte[]{4, 5});
        assertEquals(0, fixture.closes);
    }

    @Test
    void icecast24CanStillStartStreamingWithAnImmediateOk() throws Exception
    {
        Fixture fixture = new Fixture();
        fixture.respond(HttpVersion.HTTP_1_0, HttpStatus.SUCCESS_OK);

        fixture.assertConnected();
        fixture.assertAudioWritten(new byte[]{6, 7});
        assertEquals(0, fixture.closes);
    }

    @Test
    void authenticationAndConfigurationFailuresAfterContinueStillCloseTheStream() throws Exception
    {
        for(HttpStatus status: List.of(HttpStatus.CLIENT_ERROR_UNAUTHORIZED, HttpStatus.CLIENT_ERROR_FORBIDDEN))
        {
            Fixture fixture = new Fixture();
            fixture.respond(HttpVersion.HTTP_1_1, HttpStatus.INFORMATIONAL_CONTINUE);
            fixture.respond(HttpVersion.HTTP_1_1, status);

            BroadcastState expected = status == HttpStatus.CLIENT_ERROR_UNAUTHORIZED ?
                BroadcastState.INVALID_CREDENTIALS : BroadcastState.CONFIGURATION_ERROR;
            assertEquals(expected, fixture.broadcaster.getBroadcastState());
            assertEquals(expected, fixture.broadcaster.getLastBadBroadcastState());
            assertFalse(fixture.broadcaster.canConnect());
            assertEquals(1, fixture.closes);
            fixture.broadcaster.broadcastAudio(new byte[]{8}, null);
            assertTrue(fixture.writes.isEmpty());
        }
    }

    @Test
    void headerlessMinaResponsesAcceptBothHttpVersionsAndReleaseConnectionAdmission() throws Exception
    {
        for(String response: List.of("HTTP/1.0 200 OK", "HTTP/1.1 100 Continue", "HTTP/1.1 200 OK"))
        {
            Fixture fixture = new Fixture();
            fixture.fallback(response);

            fixture.assertConnected();
            fixture.assertAudioWritten(new byte[]{9, 10});
            assertEquals(0, fixture.closes, response);
        }
    }

    @Test
    void headerlessAuthenticationAndMountpointFailuresKeepTheirExistingStates() throws Exception
    {
        for(String version: List.of("HTTP/1.0", "HTTP/1.1"))
        {
            for(String failure: List.of("401 Authentication Required", "403 Mountpoint in use"))
            {
                Fixture fixture = new Fixture();
                fixture.fallback(version + " " + failure);

                BroadcastState expected = failure.startsWith("401") ?
                    BroadcastState.INVALID_CREDENTIALS : BroadcastState.MOUNT_POINT_IN_USE;
                assertEquals(expected == BroadcastState.MOUNT_POINT_IN_USE ? BroadcastState.DISCONNECTED : expected,
                    fixture.broadcaster.getBroadcastState());
                assertEquals(expected, fixture.broadcaster.getLastBadBroadcastState());
                assertEquals(expected == BroadcastState.MOUNT_POINT_IN_USE, fixture.broadcaster.canConnect(),
                    "A busy mountpoint remains retryable after the existing reconnect delay");
                assertFalse(fixture.flag("mConnecting").get());
                assertEquals(1, fixture.closes);
                fixture.broadcaster.broadcastAudio(new byte[]{11}, null);
                assertTrue(fixture.writes.isEmpty(), "A retryable failure must still honor the reconnect delay");
            }
        }
    }

    @Test
    void unknownHeaderlessFailureRemainsDisconnectedAndKeepsReconnectDelay() throws Exception
    {
        Fixture fixture = new Fixture();
        long before = System.currentTimeMillis();
        fixture.fallback("HTTP/1.1 503 Service Unavailable");

        assertEquals(BroadcastState.DISCONNECTED, fixture.broadcaster.getBroadcastState());
        assertTrue(fixture.broadcaster.canConnect());
        assertFalse(fixture.flag("mConnecting").get());
        assertEquals(1, fixture.closes);
        long lastAttempt = (long)fixture.field("mLastConnectionAttempt");
        assertTrue(lastAttempt >= before && lastAttempt <= System.currentTimeMillis());
        fixture.broadcaster.broadcastAudio(new byte[]{11}, null);
        assertEquals(BroadcastState.DISCONNECTED, fixture.broadcaster.getBroadcastState());
        assertTrue(fixture.writes.isEmpty());
    }

    @Test
    void fallbackParserRequiresHttp10Or11RatherThanAnyCharacterBeforeTheMinorVersion() throws Exception
    {
        Fixture fixture = new Fixture();
        assertFalse(fixture.broadcaster.new HttpDumpMessage(hex("HTTP/1x0 200 OK")).hasHttpResponseCode());
    }

    private static String hex(String text)
    {
        return HexFormat.ofDelimiter(" ").withUpperCase().formatHex(text.getBytes(StandardCharsets.US_ASCII));
    }

    private static final class Fixture
    {
        private final IcecastHTTPConfiguration configuration = new IcecastHTTPConfiguration();
        private final List<Object> writes = new ArrayList<>();
        private final IoSession session;
        private final IcecastHTTPAudioBroadcaster broadcaster;
        private final IcecastHTTPAudioBroadcaster.IcecastHTTPIOHandler handler;
        private int closes;

        private Fixture() throws Exception
        {
            configuration.setName("Synthetic Icecast handshake");
            configuration.setMountPoint("/synthetic-stream");
            configuration.setUserName("synthetic-source");
            configuration.setPassword("synthetic-password");
            configuration.setInline(false);
            broadcaster = new IcecastHTTPAudioBroadcaster(configuration, InputAudioFormat.SR_8000,
                MP3Setting.CBR_16, new AliasModel());
            handler = broadcaster.new IcecastHTTPIOHandler();
            session = (IoSession)Proxy.newProxyInstance(IoSession.class.getClassLoader(),
                new Class<?>[]{IoSession.class}, (proxy, method, arguments) -> {
                    return switch(method.getName())
                    {
                        case "write" -> { writes.add(arguments[0]); yield null; }
                        case "isConnected" -> closes == 0;
                        case "closeNow" -> { closes++; yield null; }
                        default -> throw new AssertionError("Unexpected session call: " + method.getName());
                    };
                });
            Field sessionField = IcecastHTTPAudioBroadcaster.class.getDeclaredField("mStreamingSession");
            sessionField.setAccessible(true);
            sessionField.set(broadcaster, session);
            broadcaster.setBroadcastState(BroadcastState.CONNECTING);
            flag("mConnecting").set(true);
            flag("mConnectionFailureLogged").set(true);
        }

        private void respond(HttpVersion version, HttpStatus status) throws Exception
        {
            handler.messageReceived(session, new DefaultHttpResponse(version, status, Map.of()));
        }

        private void fallback(String response) throws Exception
        {
            ProtocolDecoderException exception = new ProtocolDecoderException(new ArrayIndexOutOfBoundsException());
            exception.setHexdump(hex(response));
            handler.exceptionCaught(session, exception);
        }

        private Object field(String name) throws Exception
        {
            Field field = IcecastHTTPAudioBroadcaster.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(broadcaster);
        }

        private AtomicBoolean flag(String name) throws Exception
        {
            return (AtomicBoolean)field(name);
        }

        private void assertConnected() throws Exception
        {
            assertEquals(BroadcastState.CONNECTED, broadcaster.getBroadcastState());
            assertFalse(flag("mConnecting").get());
            assertFalse(flag("mConnectionFailureLogged").get());
        }

        private void assertAudioWritten(byte[] expected)
        {
            int before = writes.size();
            broadcaster.broadcastAudio(expected, null);
            assertEquals(before + 1, writes.size());
            IoBuffer buffer = assertInstanceOf(IoBuffer.class, writes.getLast());
            byte[] actual = new byte[buffer.remaining()];
            buffer.get(actual);
            assertArrayEquals(expected, actual);
        }
    }
}
