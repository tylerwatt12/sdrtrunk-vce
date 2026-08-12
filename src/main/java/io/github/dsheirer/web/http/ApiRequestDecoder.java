/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.web.http;

import com.sun.net.httpserver.HttpExchange;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Strict single-pass UTF-8 decoding shared by version-one path and query adapters. */
public final class ApiRequestDecoder
{
    private ApiRequestDecoder()
    {
    }

    public static String decodeComponent(String value, boolean plusAsSpace)
    {
        String encoded = value != null ? value : "";
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(encoded.length());

        try
        {
            for(int index = 0; index < encoded.length(); index++)
            {
                char character = encoded.charAt(index);

                if(character == '%')
                {
                    if(index + 2 >= encoded.length())
                    {
                        throw new IllegalArgumentException("Invalid percent encoding");
                    }

                    int high = Character.digit(encoded.charAt(index + 1), 16);
                    int low = Character.digit(encoded.charAt(index + 2), 16);

                    if(high < 0 || low < 0)
                    {
                        throw new IllegalArgumentException("Invalid percent encoding");
                    }

                    bytes.write(high << 4 | low);
                    index += 2;
                }
                else if(character == '+' && plusAsSpace)
                {
                    bytes.write(' ');
                }
                else
                {
                    bytes.writeBytes(String.valueOf(character).getBytes(StandardCharsets.UTF_8));
                }
            }

            String decoded = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes.toByteArray())).toString();

            if(decoded.codePoints().anyMatch(Character::isISOControl))
            {
                throw new IllegalArgumentException("Control characters are not allowed");
            }

            return decoded;
        }
        catch(CharacterCodingException exception)
        {
            throw new IllegalArgumentException("Invalid UTF-8 encoding", exception);
        }
    }

    /** Reads at most {@code maximumBytes + 1} bytes so controllers can return request-too-large without buffering. */
    public static byte[] readBody(HttpExchange exchange, int maximumBytes) throws IOException
    {
        Objects.requireNonNull(exchange, "HTTP exchange cannot be null");

        if(maximumBytes < 0 || maximumBytes == Integer.MAX_VALUE)
        {
            throw new IllegalArgumentException("Maximum request body bytes is invalid");
        }

        try(InputStream inputStream = exchange.getRequestBody())
        {
            return inputStream.readNBytes(Math.addExact(maximumBytes, 1));
        }
    }
}
