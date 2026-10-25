/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.web.http;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

/** The import form has exactly two ordered parts: bounded JSON options, then streamed UTF-8 CSV. */
final class AliasTransferUpload implements AutoCloseable
{
    static final int MAXIMUM_OPTIONS_BYTES = 2 * 1024 * 1024;
    private static final int MAXIMUM_HEADER_BYTES = 16 * 1024;
    private static final Pattern DISPOSITION_PARAMETER =
        Pattern.compile("\\G\\s*;\\s*(name|filename)=\"((?:[^\"\\\\]|\\\\.)*)\"\\s*");
    private final PushbackInputStream input;
    private final byte[] delimiter;
    private final byte[] options;
    private final Part csv;

    AliasTransferUpload(InputStream body, String contentType, int maximumCsvBytes) throws IOException
    {
        this(body, contentType, MAXIMUM_OPTIONS_BYTES, maximumCsvBytes);
    }

    AliasTransferUpload(InputStream body, String contentType, int maximumOptionsBytes, int maximumCsvBytes)
        throws IOException
    {
        String boundary;
        try { boundary = boundary(contentType); }
        catch(IOException failure) { body.close(); throw failure; }
        delimiter = ("\r\n--" + boundary).getBytes(StandardCharsets.US_ASCII);
        input = new PushbackInputStream(new BufferedInputStream(body, 64 * 1024), delimiter.length + 2);
        try
        {
            if(!line(80).equals("--" + boundary)) throw invalid();
            headers("options");
            Part metadata = new Part(maximumOptionsBytes);
            options = metadata.readAllBytes();
            if(!metadata.ended || !line(2).isEmpty()) throw invalid();
            headers("file");
            csv = new Part(maximumCsvBytes);
        }
        catch(IOException | RuntimeException failure)
        {
            input.close();
            throw failure;
        }
    }

    byte[] options() { return options; }
    InputStream csv() { return csv; }

    void finish() throws IOException
    {
        // The CSV parser must consume the part. Extra parts, premature EOF and trailing payload are refused.
        if(!csv.ended || input.read() != '-' || input.read() != '-') throw invalid();
        int trailing = input.read();
        if(trailing != -1 && (trailing != '\r' || input.read() != '\n' || input.read() != -1)) throw invalid();
    }

    @Override public void close() throws IOException { input.close(); }

    private static String boundary(String contentType) throws IOException
    {
        String[] fields = Objects.toString(contentType, "").split(";", -1);
        if(!fields[0].strip().equalsIgnoreCase("multipart/form-data")) throw invalid();
        String boundary = null;
        for(int index = 1; index < fields.length; index++)
        {
            String[] field = fields[index].strip().split("=", 2);
            if(field.length != 2 || !field[0].strip().equalsIgnoreCase("boundary") || boundary != null) throw invalid();
            boundary = field[1].strip();
            if(boundary.startsWith("\"") && boundary.endsWith("\"") && boundary.length() >= 2)
                boundary = boundary.substring(1, boundary.length() - 1);
        }
        if(boundary == null || !boundary.matches("[0-9A-Za-z'()+_,./:=? -]{1,70}") || boundary.endsWith(" "))
            throw invalid();
        return boundary;
    }

    private void headers(String expectedName) throws IOException
    {
        Map<String,String> headers = new HashMap<>();
        int remaining = MAXIMUM_HEADER_BYTES;
        while(true)
        {
            String line = line(remaining);
            remaining -= line.length() + 2;
            if(line.isEmpty()) break;
            int colon = line.indexOf(':');
            if(colon <= 0) throw invalid();
            String key = line.substring(0, colon).toLowerCase(Locale.ROOT);
            if(!Set.of("content-disposition", "content-type").contains(key) ||
                headers.putIfAbsent(key, line.substring(colon + 1).strip()) != null) throw invalid();
        }
        String disposition = headers.get("content-disposition");
        if(disposition == null || !disposition.regionMatches(true, 0, "form-data", 0, 9)) throw invalid();
        Map<String,String> parameters = new HashMap<>();
        var matcher = DISPOSITION_PARAMETER.matcher(disposition);
        int position = 9;
        while(position < disposition.length())
        {
            matcher.region(position, disposition.length());
            if(!matcher.lookingAt() || parameters.putIfAbsent(matcher.group(1), matcher.group(2)) != null) throw invalid();
            position = matcher.end();
        }
        if(!expectedName.equals(parameters.get("name"))) throw invalid();
    }

    private String line(int maximum) throws IOException
    {
        if(maximum < 0) throw invalid();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.min(maximum, 256));
        while(true)
        {
            int value = input.read();
            if(value < 0) throw invalid();
            if(value == '\r')
            {
                if(input.read() != '\n') throw invalid();
                return bytes.toString(StandardCharsets.ISO_8859_1);
            }
            if(value == '\n' || value == 0 || bytes.size() >= maximum) throw invalid();
            bytes.write(value);
        }
    }

    private final class Part extends InputStream
    {
        private long remaining;
        private boolean ended;
        private Part(int maximum) { remaining = maximum; }
        @Override public int read() throws IOException
        {
            if(ended) return -1;
            int value = input.read();
            if(value < 0) throw invalid();
            if(value == delimiter[0])
            {
                byte[] suffix = input.readNBytes(delimiter.length - 1);
                boolean match = suffix.length == delimiter.length - 1;
                for(int index = 0; match && index < suffix.length; index++)
                    match = suffix[index] == delimiter[index + 1];
                if(match)
                {
                    byte[] ending = input.readNBytes(2);
                    boolean valid = ending.length == 2 && (ending[0] == '\r' && ending[1] == '\n' ||
                        ending[0] == '-' && ending[1] == '-');
                    input.unread(ending);
                    if(valid) { ended = true; return -1; }
                }
                input.unread(suffix);
            }
            if(remaining-- == 0) throw new TooLargeException();
            return value;
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException
        {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            if(length == 0) return 0;
            int count = 0;
            while(count < length)
            {
                int value = read();
                if(value < 0) return count == 0 ? -1 : count;
                bytes[offset + count++] = (byte)value;
            }
            return count;
        }
        @Override public void close() {} // The form owns the request body until final-boundary validation.
    }

    static final class TooLargeException extends IOException {}
    static final class InvalidUploadException extends IOException
    {
        private InvalidUploadException() { super("The alias import upload is incomplete or invalid"); }
    }
    private static IOException invalid() { return new InvalidUploadException(); }
}
