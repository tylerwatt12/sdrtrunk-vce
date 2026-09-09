/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 * ****************************************************************************
 */

package io.github.dsheirer.audio.broadcast.radioresolve;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Builder for a RadioResolve completed-call multipart upload.
 */
public class RadioResolveBuilder
{
    private static final String DASH_DASH = "--";
    private static final String CRLF = "\r\n";
    private final String mBoundary = "sdrtrunk-vce-" + UUID.randomUUID();
    private List<Part> mParts = new ArrayList<>();
    private Path mAudioPath;
    private String mAudioName = "audio.mp3";

    public String getBoundary()
    {
        return mBoundary;
    }

    public RadioResolveBuilder addFile(Path path, String audioName)
    {
        mAudioPath = path;

        if(audioName != null && !audioName.isBlank())
        {
            mAudioName = audioName;
        }

        return this;
    }

    public RadioResolveBuilder addPart(String key, String value)
    {
        if(key != null && !key.isBlank() && value != null && !value.isBlank())
        {
            mParts.add(new Part(key, value, null));
        }

        return this;
    }

    public RadioResolveBuilder addPart(String key, Number value)
    {
        if(value != null)
        {
            addPart(key, value.toString());
        }

        return this;
    }

    /** Adds a UTF-8 JSON form part. */
    public RadioResolveBuilder addJsonPart(String key, String value)
    {
        if(key != null && !key.isBlank() && value != null && !value.isBlank())
        {
            mParts.add(new Part(key, value, "application/json; charset=UTF-8"));
        }

        return this;
    }

    public HttpRequest.BodyPublisher build() throws IOException
    {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        for(Part part: mParts)
        {
            outputStream.write(formatPart(part).getBytes(StandardCharsets.UTF_8));
        }

        if(mAudioPath != null)
        {
            outputStream.write(formatFilePart().getBytes(StandardCharsets.UTF_8));
            HttpRequest.BodyPublisher prefix = HttpRequest.BodyPublishers.ofByteArray(outputStream.toByteArray());
            HttpRequest.BodyPublisher file = HttpRequest.BodyPublishers.ofFile(mAudioPath);
            HttpRequest.BodyPublisher suffix = HttpRequest.BodyPublishers.ofString(CRLF + getClosingBoundary(),
                StandardCharsets.UTF_8);
            return HttpRequest.BodyPublishers.concat(prefix, file, suffix);
        }

        outputStream.write(getClosingBoundary().getBytes(StandardCharsets.UTF_8));

        return HttpRequest.BodyPublishers.ofByteArray(outputStream.toByteArray());
    }

    private String formatPart(Part part)
    {
        StringBuilder sb = new StringBuilder();
        sb.append(DASH_DASH).append(mBoundary).append(CRLF);
        sb.append("Content-Disposition: form-data; name=\"").append(part.mKey).append("\"").append(CRLF);

        if(part.mContentType != null)
        {
            sb.append("Content-Type: ").append(part.mContentType).append(CRLF);
        }

        sb.append(CRLF);
        sb.append(part.mValue).append(CRLF);
        return sb.toString();
    }

    private String formatFilePart()
    {
        StringBuilder sb = new StringBuilder();
        sb.append(DASH_DASH).append(mBoundary).append(CRLF);
        sb.append("Content-Disposition: form-data; name=\"audio\"; filename=\"").append(mAudioName).append("\"").append(CRLF);
        sb.append("Content-Type: audio/mpeg").append(CRLF);
        sb.append(CRLF);
        return sb.toString();
    }

    private String getClosingBoundary()
    {
        return DASH_DASH + mBoundary + DASH_DASH + CRLF;
    }

    private record Part(String mKey, String mValue, String mContentType)
    {
    }
}
