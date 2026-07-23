/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.record;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.audio.call.AudioCallId;
import io.github.dsheirer.audio.call.AudioCallRecordingMetadata;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;
import java.util.zip.CRC32;
import org.junit.jupiter.api.Test;

class RecordedCallManifestTest
{
    @Test
    void roundTripsEveryFieldThroughBinaryBase64AndTaggedRepresentations()
    {
        RecordedCallManifest original = manifest();
        byte[] bytes = original.toBytes();
        String base64 = original.toBase64Url();
        String tagged = original.toTaggedValue();

        assertEquals(original, RecordedCallManifest.fromBytes(bytes));
        assertEquals(original, RecordedCallManifest.fromBase64Url(base64));
        assertEquals(original, RecordedCallManifest.fromTaggedValue(tagged));
        assertFalse(base64.contains("="));
        assertTrue(base64.matches("[A-Za-z0-9_-]+"));
        assertTrue(tagged.startsWith(RecordedCallManifest.TAG_PREFIX));
        assertArrayEquals(bytes, RecordedCallManifest.fromBase64Url(base64).toBytes());
    }

    @Test
    void preservesNullEmptyAndUnicodeMetadataValues()
    {
        AudioCallRecordingMetadata metadata = new AudioCallRecordingMetadata(
            null, "", "São Paulo 🚒", "site-identity", null, "channel-identity", "aliases",
            "APCO25", "100", null, "exact:APCO25:100", false, null, "", "Dispatché");
        RecordedCallManifest original = new RecordedCallManifest(
            new AudioCallId(Long.MIN_VALUE, Long.MAX_VALUE, Integer.MIN_VALUE), metadata,
            1, Long.MAX_VALUE, 0, false, false);

        assertEquals(original, RecordedCallManifest.fromBytes(original.toBytes()));
    }

    @Test
    void checksumDetectsCorruption()
    {
        byte[] encoded = manifest().toBytes();
        encoded[20] ^= 0x40;

        RecordedCallManifest.ManifestFormatException exception = assertThrows(
            RecordedCallManifest.ManifestFormatException.class,
            () -> RecordedCallManifest.fromBytes(encoded));
        assertTrue(exception.getMessage().contains("checksum"));
    }

    @Test
    void rejectsTruncatedAndOutsideBoundedBinaryData()
    {
        byte[] encoded = manifest().toBytes();

        assertThrows(RecordedCallManifest.ManifestFormatException.class,
            () -> RecordedCallManifest.fromBytes(Arrays.copyOf(encoded, encoded.length - 1)));
        assertThrows(RecordedCallManifest.ManifestFormatException.class,
            () -> RecordedCallManifest.fromBytes(new byte[RecordedCallManifest.MAXIMUM_BINARY_BYTES + 1]));
        assertThrows(RecordedCallManifest.ManifestFormatException.class,
            () -> RecordedCallManifest.fromBytes(new byte[1]));
    }

    @Test
    void rejectsOversizedMetadataBeforeEncoding()
    {
        String oversized = "x".repeat(RecordedCallManifest.MAXIMUM_STRING_BYTES + 1);
        AudioCallRecordingMetadata metadata = new AudioCallRecordingMetadata(
            oversized, null, null, null, null, null, null, null, null, null, null,
            false, null, null, null);

        assertThrows(IllegalArgumentException.class,
            () -> new RecordedCallManifest(new AudioCallId(1, 2, 0), metadata,
                1, 2, 1, false, false));
    }

    @Test
    void rejectsForgedOversizedStringBeforeAllocatingIt()
    {
        byte[] encoded = manifest().toBytes();
        int firstStringLengthOffset = 4 + 1 + 1 + Long.BYTES + Long.BYTES + Integer.BYTES +
            Long.BYTES + Long.BYTES + Long.BYTES;
        encoded[firstStringLengthOffset] = 0x04;
        encoded[firstStringLengthOffset + 1] = 0x01;
        replaceChecksum(encoded);

        RecordedCallManifest.ManifestFormatException exception = assertThrows(
            RecordedCallManifest.ManifestFormatException.class,
            () -> RecordedCallManifest.fromBytes(encoded));
        assertTrue(exception.getMessage().contains("size limit"));
    }

    @Test
    void rejectsInvalidUtf8UnknownVersionFlagsAndTrailingDataWithValidChecksums()
    {
        byte[] invalidUtf8 = manifest().toBytes();
        int firstStringLengthOffset = 4 + 1 + 1 + Long.BYTES + Long.BYTES + Integer.BYTES +
            Long.BYTES + Long.BYTES + Long.BYTES;
        int firstStringOffset = firstStringLengthOffset + Short.BYTES;
        invalidUtf8[firstStringOffset] = (byte)0xC0;
        replaceChecksum(invalidUtf8);
        assertThrows(RecordedCallManifest.ManifestFormatException.class,
            () -> RecordedCallManifest.fromBytes(invalidUtf8));

        byte[] unknownVersion = manifest().toBytes();
        unknownVersion[4] = 2;
        replaceChecksum(unknownVersion);
        assertThrows(RecordedCallManifest.ManifestFormatException.class,
            () -> RecordedCallManifest.fromBytes(unknownVersion));

        byte[] unknownFlags = manifest().toBytes();
        unknownFlags[5] |= (byte)0x80;
        replaceChecksum(unknownFlags);
        assertThrows(RecordedCallManifest.ManifestFormatException.class,
            () -> RecordedCallManifest.fromBytes(unknownFlags));

        byte[] original = manifest().toBytes();
        byte[] trailing = Arrays.copyOf(original, original.length + 1);
        trailing[trailing.length - Integer.BYTES - 1] = 1;
        replaceChecksum(trailing);
        assertThrows(RecordedCallManifest.ManifestFormatException.class,
            () -> RecordedCallManifest.fromBytes(trailing));
    }

    @Test
    void rejectsNonCanonicalAndOversizedBase64BeforeBinaryParsing()
    {
        String canonical = manifest().toBase64Url();

        assertThrows(RecordedCallManifest.ManifestFormatException.class,
            () -> RecordedCallManifest.fromBase64Url(canonical + "="));
        assertThrows(RecordedCallManifest.ManifestFormatException.class,
            () -> RecordedCallManifest.fromBase64Url("%%%"));
        assertThrows(RecordedCallManifest.ManifestFormatException.class,
            () -> RecordedCallManifest.fromBase64Url(
                "A".repeat(RecordedCallManifest.MAXIMUM_BASE64URL_CHARACTERS + 1)));
        assertThrows(RecordedCallManifest.ManifestFormatException.class,
            () -> RecordedCallManifest.fromTaggedValue(canonical));
    }

    @Test
    void rejectsInvalidTimeValues()
    {
        AudioCallRecordingMetadata metadata = metadata();

        assertThrows(IllegalArgumentException.class,
            () -> new RecordedCallManifest(new AudioCallId(1, 2, 0), metadata,
                0, 1, 1, false, true));
        assertThrows(IllegalArgumentException.class,
            () -> new RecordedCallManifest(new AudioCallId(1, 2, 0), metadata,
                2, 1, 1, false, true));
        assertThrows(IllegalArgumentException.class,
            () -> new RecordedCallManifest(new AudioCallId(1, 2, 0), metadata,
                1, 2, -1, false, true));
    }

    @Test
    void extractsTheTaggedManifestFromHumanReadableComments()
    {
        RecordedCallManifest manifest = manifest();
        String comments = "System:" + RecordedCallManifest.TAG_PREFIX + "spoofed;" +
            manifest.toTaggedValue() + ";ignored";

        assertEquals(Optional.of(manifest), RecordedCallManifest.extractFromComment(comments));
        assertTrue(RecordedCallManifest.extractFromComment("No recovery marker").isEmpty());
        assertTrue(RecordedCallManifest.extractFromComment(
            "Human text " + RecordedCallManifest.TAG_PREFIX + "invalid").isEmpty());
    }

    private static RecordedCallManifest manifest()
    {
        return new RecordedCallManifest(new AudioCallId(1234567890123L, 4567, 1), metadata(),
            1_721_754_123_456L, 1_721_754_129_876L, 6_420L, true, true);
    }

    private static AudioCallRecordingMetadata metadata()
    {
        return new AudioCallRecordingMetadata(
            "County Radio", "county-radio", "Downtown", "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee",
            "Control One", "11111111-2222-4333-8444-555555555555", "Public Safety",
            "APCO25", "56138", "Fire Dispatch", "p25-fq:781824:42:56138", true,
            "APCO25", "16777201", "Engine 4");
    }

    private static void replaceChecksum(byte[] encoded)
    {
        int checksumOffset = encoded.length - Integer.BYTES;
        CRC32 crc = new CRC32();
        crc.update(encoded, 0, checksumOffset);
        ByteBuffer.wrap(encoded, checksumOffset, Integer.BYTES).putInt((int)crc.getValue());
    }
}
