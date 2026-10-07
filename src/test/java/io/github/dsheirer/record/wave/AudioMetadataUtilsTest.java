/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
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
 * ****************************************************************************
 */
package io.github.dsheirer.record.wave;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mpatric.mp3agic.ID3v24Tag;
import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.alias.id.AliasID;
import io.github.dsheirer.alias.id.radio.Radio;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.configuration.ChannelNameConfigurationIdentifier;
import io.github.dsheirer.identifier.configuration.DecoderTypeConfigurationIdentifier;
import io.github.dsheirer.identifier.configuration.FrequencyConfigurationIdentifier;
import io.github.dsheirer.identifier.configuration.SiteConfigurationIdentifier;
import io.github.dsheirer.identifier.configuration.SystemConfigurationIdentifier;
import io.github.dsheirer.identifier.decoder.DecoderLogicalChannelNameIdentifier;
import io.github.dsheirer.identifier.patch.PatchGroup;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.p25.identifier.patch.APCO25PatchGroup;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25FullyQualifiedRadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25FullyQualifiedTalkgroupIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.protocol.Protocol;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Frozen external metadata fixtures from DSheirer/sdrtrunk a9120ed65e5b9f91cf460544efdab4863d7e4b45.
 * Expected identifier text deliberately does not use the application's display formatter.
 */
class AudioMetadataUtilsTest
{
    @Test
    void numericIdentifiersKeepUpstreamAliasDelimiters() throws Exception
    {
        AliasList aliases = aliasList();
        addAlias(aliases, "Dispatch", new Talkgroup(Protocol.APCO25, 100));
        addAlias(aliases, "Unit", new Radio(Protocol.APCO25, 200));
        IdentifierCollection identifiers = new IdentifierCollection(List.of(
            APCO25Talkgroup.create(100), APCO25RadioIdentifier.createFrom(200)));

        assertIdentities(AudioMetadataUtils.getMetadataMap(identifiers, aliases), "200 Unit", "100\"Dispatch\"");
    }

    @Test
    void roamingRadioUsesDecimalLocalFirstTextAndTargetKeepsRoamPrefix() throws Exception
    {
        IdentifierCollection identifiers = new IdentifierCollection(List.of(
            APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(501, 0xBEE00, 0x687, 2_115_288),
            APCO25FullyQualifiedRadioIdentifier.createToWithWorkingAddress(777, 0xABCDE, 0x456, 9_001)));

        assertIdentities(AudioMetadataUtils.getMetadataMap(identifiers, aliasList()),
            "501(781824.1671.2115288)", "ROAM 777(703710.1110.9001)");
    }

    @Test
    void equalExplicitWorkingAddressUsesUpstreamUnaliasedText() throws Exception
    {
        IdentifierCollection identifiers = new IdentifierCollection(List.of(
            APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(2_115_288, 0xBEE00, 0x687, 2_115_288),
            APCO25FullyQualifiedRadioIdentifier.createToWithWorkingAddress(9_001, 0xABCDE, 0x456, 9_001)));

        assertIdentities(AudioMetadataUtils.getMetadataMap(identifiers, null),
            "781824.1671.2115288", "703710.1110.9001");
    }

    @Test
    void zeroLocalAddressUsesUpstreamFullyQualifiedText() throws Exception
    {
        IdentifierCollection identifiers = new IdentifierCollection(List.of(
            APCO25FullyQualifiedRadioIdentifier.createFrom(0, 0xBEE00, 0x687, 2_115_288),
            APCO25FullyQualifiedRadioIdentifier.createTo(0, 0xABCDE, 0x456, 9_001)));

        assertIdentities(AudioMetadataUtils.getMetadataMap(identifiers, null),
            "781824.1671.2115288", "703710.1110.9001");
    }

    @Test
    void legacyProjectionDoesNotDependOnCurrentWorkingAddressProvenance() throws Exception
    {
        IdentifierCollection identifiers = new IdentifierCollection(List.of(
            APCO25FullyQualifiedRadioIdentifier.createFrom(501, 0xBEE00, 0x687, 2_115_288),
            APCO25FullyQualifiedRadioIdentifier.createTo(777, 0xABCDE, 0x456, 9_001)));

        assertIdentities(AudioMetadataUtils.getMetadataMap(identifiers, null),
            "501(781824.1671.2115288)", "ROAM 777(703710.1110.9001)");
    }

    @Test
    void qualifiedTalkgroupRetainsLocalPersonaAndRemovesIssiPrefix() throws Exception
    {
        IdentifierCollection identifiers = new IdentifierCollection(List.of(
            APCO25FullyQualifiedTalkgroupIdentifier.createTo(601, 0xBEE00, 0x687, 12_345)));

        assertIdentities(AudioMetadataUtils.getMetadataMap(identifiers, null), null,
            "601(781824.1671.12345)");
    }

    @Test
    void nestedQualifiedPatchUsesLegacyTextForEveryMember() throws Exception
    {
        PatchGroup patch = new PatchGroup(
            APCO25FullyQualifiedTalkgroupIdentifier.createTo(801, 0xBEE00, 0x687, 5_001));
        patch.addPatchedTalkgroup(APCO25FullyQualifiedTalkgroupIdentifier.createTo(802, 0xBEE00, 0x687, 5_002));
        patch.addPatchedTalkgroup(APCO25Talkgroup.create(5_003));
        patch.addPatchedRadio(
            APCO25FullyQualifiedRadioIdentifier.createToWithWorkingAddress(901, 0xBEE00, 0x687, 9_001));
        patch.addPatchedRadio(APCO25RadioIdentifier.createTo(902));
        IdentifierCollection identifiers = new IdentifierCollection(List.of(
            APCO25PatchGroup.create(patch), APCO25RadioIdentifier.createFrom(700)));

        assertIdentities(AudioMetadataUtils.getMetadataMap(identifiers, null), "700",
            "P:801(781824.1671.5001) [802(781824.1671.5002), 5003] [ROAM 901(781824.1671.9001), 902]");
    }

    @Test
    void patchAliasesKeepQuotedCommaSeparatedLookupOrder() throws Exception
    {
        PatchGroup patch = new PatchGroup(APCO25Talkgroup.create(100));
        patch.addPatchedTalkgroup(APCO25Talkgroup.create(101));
        patch.addPatchedTalkgroup(APCO25Talkgroup.create(102));
        AliasList aliases = aliasList();
        addAlias(aliases, "Dispatch", new Talkgroup(Protocol.APCO25, 100));
        addAlias(aliases, "Backup", new Talkgroup(Protocol.APCO25, 101));
        addAlias(aliases, "Mutual Aid", new Talkgroup(Protocol.APCO25, 102));
        IdentifierCollection identifiers = new IdentifierCollection(List.of(APCO25PatchGroup.create(patch)));

        assertIdentities(AudioMetadataUtils.getMetadataMap(identifiers, aliases), null,
            "P:100 [101, 102]\"Dispatch\",\"Backup\",\"Mutual Aid\"");
    }

    @Test
    void completeMetadataPreservesStandardKeysAndCommentOrder()
    {
        IdentifierCollection identifiers = new IdentifierCollection(List.of(
            APCO25Talkgroup.create(100), APCO25RadioIdentifier.createFrom(200),
            SystemConfigurationIdentifier.create("Regional Police"), SiteConfigurationIdentifier.create("East"),
            ChannelNameConfigurationIdentifier.create("Dispatch"),
            DecoderTypeConfigurationIdentifier.create(DecoderType.P25_PHASE1),
            DecoderLogicalChannelNameIdentifier.create("1-1234", Protocol.APCO25),
            FrequencyConfigurationIdentifier.create(851_012_500L)));

        Map<AudioMetadata, String> metadata = AudioMetadataUtils.getMetadataMap(identifiers, null);

        assertEquals(List.of(AudioMetadata.ARTIST_NAME, AudioMetadata.ALBUM_TITLE, AudioMetadata.GROUPING,
            AudioMetadata.TRACK_TITLE, AudioMetadata.COMMENTS, AudioMetadata.DATE_CREATED, AudioMetadata.GENRE,
            AudioMetadata.YEAR, AudioMetadata.COMPOSER), new ArrayList<>(metadata.keySet()));
        assertEquals("200", metadata.get(AudioMetadata.ARTIST_NAME));
        assertEquals("100", metadata.get(AudioMetadata.TRACK_TITLE));
        assertEquals("Regional Police", metadata.get(AudioMetadata.GROUPING));
        assertEquals("Dispatch", metadata.get(AudioMetadata.ALBUM_TITLE));
        assertEquals("Scanner Audio", metadata.get(AudioMetadata.GENRE));
        assertEquals("sdrtrunk", metadata.get(AudioMetadata.COMPOSER));
        String date = metadata.get(AudioMetadata.DATE_CREATED);
        assertTrue(date.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}"));
        assertEquals(date.substring(0, 4), metadata.get(AudioMetadata.YEAR));
        assertEquals("Date:" + date + ";System:Regional Police;Site:East;Name:Dispatch;Decoder:P25 Phase 1;" +
            "Channel:1-1234;Frequency:851012500;", metadata.get(AudioMetadata.COMMENTS));
    }

    @Test
    void id3BytesKeepUpstreamFrameIdsValuesAndOrder() throws Exception
    {
        byte[] bytes = AudioMetadataUtils.getMP3ID3(fixedMetadata());
        ID3v24Tag tag = new ID3v24Tag(bytes);

        assertEquals(List.of("COMM", "TALB", "TCOM", "TCON", "TDAT", "TIT1", "TIT2", "TPE1", "TYER"),
            id3FrameIds(bytes));
        assertEquals("501(781824.1671.2115288) Unit", tag.getArtist());
        assertEquals("ROAM 777(703710.1110.9001)\"Dispatch\",\"Backup\"", tag.getTitle());
        assertEquals("Dispatch", tag.getAlbum());
        assertEquals("Regional Police", tag.getGrouping());
        assertEquals("sdrtrunk v0.6.1", tag.getComposer());
        assertEquals("Scanner Audio", tag.getGenreDescription());
        assertEquals("2026-10-07 12:34:56.789", tag.getDate());
        assertEquals("2026", tag.getYear());
        assertNull(tag.getRecordingTime(), "Upstream setDate writes TDAT, not TDRC");
        assertEquals("Date:2026-10-07 12:34:56.789;System:Regional Police;Site:East;Name:Dispatch;" +
            "Decoder:P25 Phase 1;Channel:1-1234;Frequency:851012500;", tag.getComment());
    }

    @Test
    void waveListAndId3ChunkPreserveUpstreamValuesAndOrder() throws Exception
    {
        Map<AudioMetadata, String> metadata = fixedMetadata();
        Map<String, String> fields = waveListFields(AudioMetadataUtils.getLISTChunk(metadata));

        assertEquals(List.of("IART", "IPRD", "ISBJ", "INAM", "ICMT", "ICRD", "IGNR", "ICOP", "ISFT"),
            new ArrayList<>(fields.keySet()));
        assertEquals("501(781824.1671.2115288) Unit", fields.get("IART"));
        assertEquals("Dispatch", fields.get("IPRD"));
        assertEquals("Regional Police", fields.get("ISBJ"));
        assertEquals("ROAM 777(703710.1110.9001)\"Dispatch\",\"Backup\"", fields.get("INAM"));
        assertEquals("Date:2026-10-07 12:34:56.789;System:Regional Police;Site:East;Name:Dispatch;" +
            "Decoder:P25 Phase 1;Channel:1-1234;Frequency:851012500;", fields.get("ICMT"));
        assertEquals("2026-10-07 12:34:56.789", fields.get("ICRD"));
        assertEquals("Scanner Audio", fields.get("IGNR"));
        assertEquals("2026", fields.get("ICOP"));
        assertEquals("sdrtrunk v0.6.1", fields.get("ISFT"));

        byte[] id3 = AudioMetadataUtils.getMP3ID3(metadata);
        ByteBuffer chunk = AudioMetadataUtils.getID3Chunk(id3).duplicate().order(ByteOrder.LITTLE_ENDIAN);
        chunk.rewind();
        assertEquals("id3 ", readFourCharacters(chunk));
        int length = chunk.getInt();
        assertEquals(chunk.remaining(), length);
        assertEquals(0, length % 4);
        byte[] embeddedId3 = new byte[id3.length];
        chunk.get(embeddedId3);
        assertArrayEquals(id3, embeddedId3);
        assertEquals("sdrtrunk v0.6.1", new ID3v24Tag(embeddedId3).getComposer());
        while(chunk.hasRemaining()) assertEquals((byte)0, chunk.get());
    }

    @Test
    void nullIdentifiersAndAliasListStillProduceStandardMetadata() throws Exception
    {
        Map<AudioMetadata, String> metadata = AudioMetadataUtils.getMetadataMap(null, null);

        assertEquals(List.of(AudioMetadata.COMMENTS, AudioMetadata.DATE_CREATED, AudioMetadata.GENRE,
            AudioMetadata.YEAR, AudioMetadata.COMPOSER), new ArrayList<>(metadata.keySet()));
        assertEquals("Date:" + metadata.get(AudioMetadata.DATE_CREATED) + ";", metadata.get(AudioMetadata.COMMENTS));
        assertEquals("sdrtrunk", metadata.get(AudioMetadata.COMPOSER));
        assertIdentities(metadata, null, null);
    }

    @Test
    void absentNullAndEmptyOptionalFieldsDoNotBreakWaveListGeneration()
    {
        Map<AudioMetadata, String> metadata = new EnumMap<>(AudioMetadata.class);
        metadata.put(AudioMetadata.ARTIST_NAME, null);
        metadata.put(AudioMetadata.ALBUM_TITLE, "");
        metadata.put(AudioMetadata.GROUPING, "");
        metadata.put(AudioMetadata.TRACK_TITLE, null);
        metadata.put(AudioMetadata.COMPOSER, "sdrtrunk");

        assertEquals(Map.of("ISFT", "sdrtrunk"), waveListFields(AudioMetadataUtils.getLISTChunk(metadata)));
        assertEquals(Map.of(), waveListFields(AudioMetadataUtils.getLISTChunk(new EnumMap<>(AudioMetadata.class))));
    }

    private static AliasList aliasList()
    {
        return new AliasList(new AliasListDefinition("P25", AliasListFamily.P25));
    }

    private static void addAlias(AliasList aliases, String name, AliasID matcher)
    {
        Alias alias = new Alias(name);
        alias.setMatchIdentifier(matcher);
        aliases.addAlias(alias);
    }

    private static void assertIdentities(Map<AudioMetadata, String> metadata, String artist, String title)
        throws Exception
    {
        assertEquals(artist, metadata.get(AudioMetadata.ARTIST_NAME));
        assertEquals(title, metadata.get(AudioMetadata.TRACK_TITLE));
        ID3v24Tag id3 = new ID3v24Tag(AudioMetadataUtils.getMP3ID3(metadata));
        assertEquals(artist, id3.getArtist());
        assertEquals(title, id3.getTitle());
        Map<String, String> fields = waveListFields(AudioMetadataUtils.getLISTChunk(metadata));
        assertEquals(artist, fields.get("IART"));
        assertEquals(title, fields.get("INAM"));
    }

    private static Map<AudioMetadata, String> fixedMetadata()
    {
        Map<AudioMetadata, String> metadata = new EnumMap<>(AudioMetadata.class);
        metadata.put(AudioMetadata.ARTIST_NAME, "501(781824.1671.2115288) Unit");
        metadata.put(AudioMetadata.ALBUM_TITLE, "Dispatch");
        metadata.put(AudioMetadata.GROUPING, "Regional Police");
        metadata.put(AudioMetadata.TRACK_TITLE, "ROAM 777(703710.1110.9001)\"Dispatch\",\"Backup\"");
        metadata.put(AudioMetadata.COMMENTS, "Date:2026-10-07 12:34:56.789;System:Regional Police;Site:East;" +
            "Name:Dispatch;Decoder:P25 Phase 1;Channel:1-1234;Frequency:851012500;");
        metadata.put(AudioMetadata.DATE_CREATED, "2026-10-07 12:34:56.789");
        metadata.put(AudioMetadata.GENRE, "Scanner Audio");
        metadata.put(AudioMetadata.YEAR, "2026");
        metadata.put(AudioMetadata.COMPOSER, "sdrtrunk v0.6.1");
        return metadata;
    }

    private static List<String> id3FrameIds(byte[] bytes)
    {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        assertEquals("ID3", new String(bytes, 0, 3, StandardCharsets.US_ASCII));
        assertEquals(4, bytes[3]);
        buffer.position(10);
        List<String> identifiers = new ArrayList<>();
        while(buffer.remaining() >= 10)
        {
            String identifier = readFourCharacters(buffer);
            int length = 0;
            for(int index = 0; index < 4; index++)
            {
                int value = Byte.toUnsignedInt(buffer.get());
                assertEquals(0, value & 0x80, "ID3v2.4 frame sizes are synchsafe");
                length = (length << 7) | value;
            }
            buffer.getShort();
            assertTrue(length > 0 && length <= buffer.remaining());
            identifiers.add(identifier);
            buffer.position(buffer.position() + length);
        }
        assertFalse(buffer.hasRemaining());
        return identifiers;
    }

    private static Map<String, String> waveListFields(ByteBuffer bytes)
    {
        ByteBuffer buffer = bytes.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        buffer.rewind();
        assertEquals("LIST", readFourCharacters(buffer));
        assertEquals(buffer.remaining() - 4, buffer.getInt());
        assertEquals("INFO", readFourCharacters(buffer));
        Map<String, String> fields = new LinkedHashMap<>();
        while(buffer.remaining() >= 8)
        {
            String identifier = readFourCharacters(buffer);
            int length = buffer.getInt();
            assertTrue(length > 0 && length <= buffer.remaining());
            byte[] value = new byte[length];
            buffer.get(value);
            //Upstream stores UTF8.encode's capacity, which can include extra zero bytes after the terminator.
            int textLength = 0;
            while(textLength < length && value[textLength] != 0) textLength++;
            assertTrue(textLength < length, "LIST values must retain their null terminator");
            for(int index = textLength; index < length; index++) assertEquals((byte)0, value[index]);
            fields.put(identifier, new String(value, 0, textLength, StandardCharsets.UTF_8));
        }
        while(buffer.hasRemaining()) assertEquals((byte)0, buffer.get());
        return fields;
    }

    private static String readFourCharacters(ByteBuffer buffer)
    {
        byte[] bytes = new byte[4];
        buffer.get(bytes);
        return new String(bytes, StandardCharsets.US_ASCII);
    }
}
