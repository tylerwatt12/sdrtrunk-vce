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
package io.github.dsheirer.debug;

/** Primitive/serialized boundary between the debug HTTP server and receiver control worker. */
interface DebugHarnessControlAdapter
{
    byte[] channelsJson();

    HttpResult createSession(long durationSeconds);

    HttpResult getSession(String token);

    HttpResult endSession(String token);

    HttpResult setChannel(String token, long revision, String configurationId, boolean processing);

    record HttpResult(int status, byte[] body)
    {
        public HttpResult
        {
            body = body != null ? body.clone() : new byte[0];
        }

        @Override
        public byte[] body()
        {
            return body.clone();
        }
    }
}
