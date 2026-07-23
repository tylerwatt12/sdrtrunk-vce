/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */
package io.github.dsheirer.record;

import com.mpatric.mp3agic.ID3v24Tag;
import io.github.dsheirer.audio.call.AudioCallId;
import io.github.dsheirer.audio.call.AudioCallRecordingMetadata;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import java.util.zip.CRC32;

/**
 * Compact, versioned recovery metadata for a completed recorded call.
 *
 * <p>The binary representation is intentionally independent of Java serialization and JSON. It has fixed allocation
 * limits, strict UTF-8 decoding, and a CRC so that startup recovery can safely reject truncated or damaged metadata.
 * The URL-safe Base64 form is suitable for embedding in both WAVE and MP3 metadata without a sidecar file.</p>
 */
public record RecordedCallManifest(AudioCallId callId, AudioCallRecordingMetadata metadata, long startAtMs,
                                   long completedAtMs, long durationMs, boolean encrypted, boolean recordEligible)
{
    public static final int VERSION = 1;
    public static final String TAG_PREFIX = "SDRTRUNK-CALL-V1:";
    public static final int MAXIMUM_STRING_BYTES = 1_024;
    public static final int MAXIMUM_BINARY_BYTES = 16 * 1_024;
    public static final int MAXIMUM_BASE64URL_CHARACTERS = (MAXIMUM_BINARY_BYTES * 4 + 2) / 3;

    private static final byte[] MAGIC = {'S', 'D', 'R', 'M'};
    private static final int FLAG_ENCRYPTED = 1;
    private static final int FLAG_RECORD_ELIGIBLE = 1 << 1;
    private static final int FLAG_DESTINATION_RECORD_ENABLED = 1 << 2;
    private static final int KNOWN_FLAGS =
        FLAG_ENCRYPTED | FLAG_RECORD_ELIGIBLE | FLAG_DESTINATION_RECORD_ENABLED;
    private static final int CHECKSUM_BYTES = Integer.BYTES;
    private static final int ID3_HEADER_BYTES = 10;
    private static final int ID3_MAJOR_VERSION = 4;
    private static final int MAXIMUM_AUDIO_TAG_BYTES = 64 * 1_024;
    private static final int MAXIMUM_WAVE_CHUNKS = 64;
    private static final int MINIMUM_BINARY_BYTES =
        MAGIC.length + 1 + 1 + Long.BYTES + Long.BYTES + Integer.BYTES +
            Long.BYTES + Long.BYTES + Long.BYTES + (14 * Short.BYTES) + CHECKSUM_BYTES;

    public RecordedCallManifest
    {
        Objects.requireNonNull(callId, "Recorded call ID cannot be null");
        Objects.requireNonNull(metadata, "Recorded call metadata cannot be null");

        if(startAtMs <= 0 || completedAtMs < startAtMs || durationMs < 0)
        {
            throw new IllegalArgumentException("Recorded call manifest timestamps and duration are invalid");
        }

        validateMetadataStrings(metadata);
    }

    /**
     * Encodes this manifest to the bounded binary representation.
     */
    public byte[] toBytes()
    {
        try
        {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(512);

            try(DataOutputStream output = new DataOutputStream(bytes))
            {
                output.write(MAGIC);
                output.writeByte(VERSION);
                output.writeByte(flags());
                output.writeLong(callId.producerId());
                output.writeLong(callId.sequence());
                output.writeInt(callId.timeslot());
                output.writeLong(startAtMs);
                output.writeLong(completedAtMs);
                output.writeLong(durationMs);
                writeNullableString(output, metadata.systemName());
                writeNullableString(output, metadata.systemIdentity());
                writeNullableString(output, metadata.siteName());
                writeNullableString(output, metadata.siteIdentity());
                writeNullableString(output, metadata.channelName());
                writeNullableString(output, metadata.channelIdentity());
                writeNullableString(output, metadata.aliasListName());
                writeNullableString(output, metadata.destinationProtocol());
                writeNullableString(output, metadata.destinationValue());
                writeNullableString(output, metadata.destinationAlias());
                writeNullableString(output, metadata.destinationMatcherIdentity());
                writeNullableString(output, metadata.sourceProtocol());
                writeNullableString(output, metadata.sourceValue());
                writeNullableString(output, metadata.sourceAlias());
                output.flush();
            }

            byte[] body = bytes.toByteArray();

            if(body.length + CHECKSUM_BYTES > MAXIMUM_BINARY_BYTES)
            {
                throw new IllegalArgumentException("Recorded call manifest exceeds the binary size limit");
            }

            CRC32 crc = new CRC32();
            crc.update(body);
            ByteBuffer result = ByteBuffer.allocate(body.length + CHECKSUM_BYTES);
            result.put(body);
            result.putInt((int)crc.getValue());
            return result.array();
        }
        catch(IOException ioe)
        {
            throw new IllegalStateException("Unable to encode recorded call manifest", ioe);
        }
    }

    /**
     * Encodes this manifest as unpadded URL-safe Base64.
     */
    public String toBase64Url()
    {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(toBytes());
    }

    /**
     * Encodes this manifest with the stable marker used in audio-file metadata.
     */
    public String toTaggedValue()
    {
        return TAG_PREFIX + toBase64Url();
    }

    /**
     * Decodes and validates the bounded binary representation.
     */
    public static RecordedCallManifest fromBytes(byte[] encoded)
    {
        if(encoded == null)
        {
            throw new ManifestFormatException("Recorded call manifest cannot be null");
        }

        if(encoded.length < MINIMUM_BINARY_BYTES || encoded.length > MAXIMUM_BINARY_BYTES)
        {
            throw new ManifestFormatException("Recorded call manifest size is outside the allowed bounds");
        }

        verifyChecksum(encoded);
        int bodyLength = encoded.length - CHECKSUM_BYTES;

        try(DataInputStream input =
                new DataInputStream(new ByteArrayInputStream(encoded, 0, bodyLength)))
        {
            byte[] magic = input.readNBytes(MAGIC.length);

            if(!java.util.Arrays.equals(MAGIC, magic))
            {
                throw new ManifestFormatException("Recorded call manifest has an invalid header");
            }

            int version = input.readUnsignedByte();

            if(version != VERSION)
            {
                throw new ManifestFormatException("Unsupported recorded call manifest version: " + version);
            }

            int flags = input.readUnsignedByte();

            if((flags & ~KNOWN_FLAGS) != 0)
            {
                throw new ManifestFormatException("Recorded call manifest contains unknown flags");
            }

            AudioCallId callId = new AudioCallId(input.readLong(), input.readLong(), input.readInt());
            long startAtMs = input.readLong();
            long completedAtMs = input.readLong();
            long durationMs = input.readLong();
            AudioCallRecordingMetadata metadata = new AudioCallRecordingMetadata(
                readNullableString(input),
                readNullableString(input),
                readNullableString(input),
                readNullableString(input),
                readNullableString(input),
                readNullableString(input),
                readNullableString(input),
                readNullableString(input),
                readNullableString(input),
                readNullableString(input),
                readNullableString(input),
                (flags & FLAG_DESTINATION_RECORD_ENABLED) != 0,
                readNullableString(input),
                readNullableString(input),
                readNullableString(input));

            if(input.available() != 0)
            {
                throw new ManifestFormatException("Recorded call manifest has unexpected trailing data");
            }

            try
            {
                return new RecordedCallManifest(callId, metadata, startAtMs, completedAtMs, durationMs,
                    (flags & FLAG_ENCRYPTED) != 0, (flags & FLAG_RECORD_ELIGIBLE) != 0);
            }
            catch(IllegalArgumentException iae)
            {
                throw new ManifestFormatException("Recorded call manifest values are invalid", iae);
            }
        }
        catch(ManifestFormatException mfe)
        {
            throw mfe;
        }
        catch(EOFException eofe)
        {
            throw new ManifestFormatException("Recorded call manifest is truncated", eofe);
        }
        catch(IOException ioe)
        {
            throw new ManifestFormatException("Unable to decode recorded call manifest", ioe);
        }
    }

    /**
     * Decodes the canonical unpadded URL-safe Base64 representation.
     */
    public static RecordedCallManifest fromBase64Url(String encoded)
    {
        if(encoded == null || encoded.isEmpty() || encoded.length() > MAXIMUM_BASE64URL_CHARACTERS)
        {
            throw new ManifestFormatException("Recorded call manifest Base64 size is outside the allowed bounds");
        }

        try
        {
            byte[] bytes = Base64.getUrlDecoder().decode(encoded);

            if(!Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(encoded))
            {
                throw new ManifestFormatException("Recorded call manifest Base64 is not canonical");
            }

            return fromBytes(bytes);
        }
        catch(IllegalArgumentException iae)
        {
            if(iae instanceof ManifestFormatException manifestFormatException)
            {
                throw manifestFormatException;
            }

            throw new ManifestFormatException("Recorded call manifest Base64 is invalid", iae);
        }
    }

    /**
     * Decodes the marker and Base64 value embedded in audio-file metadata.
     */
    public static RecordedCallManifest fromTaggedValue(String taggedValue)
    {
        if(taggedValue == null || !taggedValue.startsWith(TAG_PREFIX))
        {
            throw new ManifestFormatException("Recorded call manifest marker is missing");
        }

        return fromBase64Url(taggedValue.substring(TAG_PREFIX.length()));
    }

    /**
     * Reads the embedded manifest without scanning or loading the audio payload. MP3 tags are read only from the
     * bounded leading ID3 block; WAVE chunk headers are walked and only the bounded trailing ID3 chunk is loaded.
     */
    public static Optional<RecordedCallManifest> readFromAudioFile(Path path, RecordFormat format) throws IOException
    {
        Objects.requireNonNull(path, "Recorded audio path cannot be null");
        Objects.requireNonNull(format, "Recorded audio format cannot be null");

        if(!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path))
        {
            return Optional.empty();
        }

        try(FileChannel channel = FileChannel.open(path, StandardOpenOption.READ))
        {
            String comment = switch(format)
            {
                case MP3 -> readMp3Comment(channel);
                case WAVE -> readWaveComment(channel);
            };
            return extractFromComment(comment);
        }
    }

    static Optional<RecordedCallManifest> extractFromComment(String comment)
    {
        if(comment == null)
        {
            return Optional.empty();
        }

        //The writer appends the recovery marker after all human-readable fields.  Use the final marker so an
        //administrator-supplied label containing the marker text cannot hide the real manifest.
        int marker = comment.lastIndexOf(TAG_PREFIX);

        if(marker < 0)
        {
            return Optional.empty();
        }

        int start = marker + TAG_PREFIX.length();
        int end = start;
        int maximumEnd = Math.min(comment.length(), start + MAXIMUM_BASE64URL_CHARACTERS);

        while(end < maximumEnd)
        {
            char character = comment.charAt(end);

            if(!isBase64UrlCharacter(character))
            {
                break;
            }

            end++;
        }

        if(end == start || end == maximumEnd && end < comment.length() && isBase64UrlCharacter(comment.charAt(end)))
        {
            return Optional.empty();
        }

        try
        {
            return Optional.of(fromBase64Url(comment.substring(start, end)));
        }
        catch(ManifestFormatException exception)
        {
            return Optional.empty();
        }
    }

    private static boolean isBase64UrlCharacter(char character)
    {
        return character >= 'A' && character <= 'Z' || character >= 'a' && character <= 'z' ||
            character >= '0' && character <= '9' || character == '_' || character == '-';
    }

    private static String readMp3Comment(FileChannel channel) throws IOException
    {
        ByteBuffer header = ByteBuffer.allocate(ID3_HEADER_BYTES);

        if(!readFully(channel, header, 0))
        {
            return null;
        }

        byte[] bytes = header.array();

        if(bytes[0] != 'I' || bytes[1] != 'D' || bytes[2] != '3' || bytes[3] != ID3_MAJOR_VERSION ||
            (bytes[6] | bytes[7] | bytes[8] | bytes[9]) < 0)
        {
            return null;
        }

        int payloadLength = (bytes[6] << 21) | (bytes[7] << 14) | (bytes[8] << 7) | bytes[9];
        int totalLength = ID3_HEADER_BYTES + payloadLength;

        if(totalLength < ID3_HEADER_BYTES || totalLength > MAXIMUM_AUDIO_TAG_BYTES ||
            totalLength > channel.size())
        {
            return null;
        }

        ByteBuffer tag = ByteBuffer.allocate(totalLength);

        if(!readFully(channel, tag, 0))
        {
            return null;
        }

        return commentFromId3(tag.array());
    }

    private static String readWaveComment(FileChannel channel) throws IOException
    {
        long fileSize = channel.size();
        ByteBuffer riff = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);

        if(!readFully(channel, riff, 0))
        {
            return null;
        }

        byte[] header = riff.array();

        if(header[0] != 'R' || header[1] != 'I' || header[2] != 'F' || header[3] != 'F' ||
            header[8] != 'W' || header[9] != 'A' || header[10] != 'V' || header[11] != 'E')
        {
            return null;
        }

        long offset = 12;

        for(int chunk = 0; chunk < MAXIMUM_WAVE_CHUNKS && offset <= fileSize - 8; chunk++)
        {
            ByteBuffer chunkHeader = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);

            if(!readFully(channel, chunkHeader, offset))
            {
                return null;
            }

            byte[] chunkBytes = chunkHeader.array();
            long length = Integer.toUnsignedLong(chunkHeader.getInt(4));
            long dataOffset = offset + 8;
            long next = dataOffset + length + (length & 1);

            if(next < dataOffset || next > fileSize)
            {
                return null;
            }

            if(chunkBytes[0] == 'i' && chunkBytes[1] == 'd' && chunkBytes[2] == '3' &&
                chunkBytes[3] == ' ' && length >= ID3_HEADER_BYTES && length <= MAXIMUM_AUDIO_TAG_BYTES)
            {
                ByteBuffer tag = ByteBuffer.allocate((int)length);

                if(!readFully(channel, tag, dataOffset))
                {
                    return null;
                }

                return commentFromId3(tag.array());
            }

            offset = next;
        }

        return null;
    }

    private static String commentFromId3(byte[] bytes)
    {
        try
        {
            return new ID3v24Tag(bytes).getComment();
        }
        catch(Exception exception)
        {
            return null;
        }
    }

    private static boolean readFully(FileChannel channel, ByteBuffer buffer, long offset) throws IOException
    {
        while(buffer.hasRemaining())
        {
            int read = channel.read(buffer, offset + buffer.position());

            if(read < 0)
            {
                return false;
            }

            if(read == 0)
            {
                return false;
            }
        }

        return true;
    }

    private int flags()
    {
        int flags = 0;

        if(encrypted)
        {
            flags |= FLAG_ENCRYPTED;
        }

        if(recordEligible)
        {
            flags |= FLAG_RECORD_ELIGIBLE;
        }

        if(metadata.destinationTalkgroupRecordEnabled())
        {
            flags |= FLAG_DESTINATION_RECORD_ENABLED;
        }

        return flags;
    }

    private static void validateMetadataStrings(AudioCallRecordingMetadata metadata)
    {
        validateString(metadata.systemName());
        validateString(metadata.systemIdentity());
        validateString(metadata.siteName());
        validateString(metadata.siteIdentity());
        validateString(metadata.channelName());
        validateString(metadata.channelIdentity());
        validateString(metadata.aliasListName());
        validateString(metadata.destinationProtocol());
        validateString(metadata.destinationValue());
        validateString(metadata.destinationAlias());
        validateString(metadata.destinationMatcherIdentity());
        validateString(metadata.sourceProtocol());
        validateString(metadata.sourceValue());
        validateString(metadata.sourceAlias());
    }

    private static void validateString(String value)
    {
        if(value != null && value.getBytes(StandardCharsets.UTF_8).length > MAXIMUM_STRING_BYTES)
        {
            throw new IllegalArgumentException("Recorded call manifest string exceeds the UTF-8 size limit");
        }
    }

    private static void writeNullableString(DataOutputStream output, String value) throws IOException
    {
        if(value == null)
        {
            output.writeShort(0xFFFF);
            return;
        }

        byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);

        if(utf8.length > MAXIMUM_STRING_BYTES)
        {
            throw new IllegalArgumentException("Recorded call manifest string exceeds the UTF-8 size limit");
        }

        output.writeShort(utf8.length);
        output.write(utf8);
    }

    private static String readNullableString(DataInputStream input) throws IOException
    {
        int length = input.readUnsignedShort();

        if(length == 0xFFFF)
        {
            return null;
        }

        if(length > MAXIMUM_STRING_BYTES)
        {
            throw new ManifestFormatException("Recorded call manifest string exceeds the UTF-8 size limit");
        }

        byte[] utf8 = input.readNBytes(length);

        if(utf8.length != length)
        {
            throw new EOFException("Recorded call manifest string is truncated");
        }

        try
        {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(utf8))
                .toString();
        }
        catch(CharacterCodingException cce)
        {
            throw new ManifestFormatException("Recorded call manifest contains invalid UTF-8", cce);
        }
    }

    private static void verifyChecksum(byte[] encoded)
    {
        int bodyLength = encoded.length - CHECKSUM_BYTES;
        CRC32 crc = new CRC32();
        crc.update(encoded, 0, bodyLength);
        long expected = Integer.toUnsignedLong(ByteBuffer.wrap(encoded, bodyLength, CHECKSUM_BYTES).getInt());

        if(crc.getValue() != expected)
        {
            throw new ManifestFormatException("Recorded call manifest checksum does not match");
        }
    }

    /**
     * Indicates that externally supplied manifest data was malformed or unsupported.
     */
    public static final class ManifestFormatException extends IllegalArgumentException
    {
        private ManifestFormatException(String message)
        {
            super(message);
        }

        private ManifestFormatException(String message, Throwable cause)
        {
            super(message, cause);
        }
    }
}
