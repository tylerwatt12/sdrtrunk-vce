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

package io.github.dsheirer.gui.configuration.radioreference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.alias.id.talkgroup.StreamAsTalkgroup;
import io.github.dsheirer.alias.id.talkgroup.TalkgroupRange;
import io.github.dsheirer.identifier.talkgroup.TalkgroupIdentifier;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.protocol.Protocol;
import io.github.dsheirer.rrapi.type.System;
import io.github.dsheirer.rrapi.type.Talkgroup;
import io.github.dsheirer.rrapi.type.TalkgroupCategory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SystemTalkgroupSelectionEditorTest
{
    @Test
    void coalescesAliasMatchRefreshUntilAfterNotificationsComplete()
    {
        SystemTalkgroupSelectionEditor.DeferredRefresh deferredRefresh =
            new SystemTalkgroupSelectionEditor.DeferredRefresh();
        List<Runnable> scheduled = new ArrayList<>();
        AtomicInteger refreshCount = new AtomicInteger();

        deferredRefresh.request(scheduled::add, refreshCount::incrementAndGet);
        deferredRefresh.request(scheduled::add, refreshCount::incrementAndGet);

        assertEquals(1, scheduled.size());
        assertEquals(0, refreshCount.get());

        scheduled.remove(0).run();
        assertEquals(1, refreshCount.get());

        deferredRefresh.request(scheduled::add, refreshCount::incrementAndGet);
        assertEquals(1, scheduled.size());
    }

    @Test
    void reportsImportCompletionCounts()
    {
        assertEquals("Import complete: 12 added, 3 updated, 7 already current",
            SystemTalkgroupSelectionEditor.formatImportCompletion(12, 3, 7));
    }

    @Test
    void snapshotsRowsBeforeImport()
    {
        List<Integer> selectedRows = new ArrayList<>(List.of(2, 4));
        List<Integer> snapshot = SystemTalkgroupSelectionEditor.snapshotRows(selectedRows);

        selectedRows.clear();

        assertEquals(List.of(2, 4), snapshot);
    }

    @Test
    void radioReferenceRequiresMatchingProtocolFamily()
    {
        AliasListDefinition p25 = new AliasListDefinition("County P25", AliasListFamily.P25);

        assertTrue(SystemTalkgroupSelectionEditor.isRadioReferenceListCompatible(
            p25, DecoderType.P25_PHASE1));
        assertTrue(SystemTalkgroupSelectionEditor.isRadioReferenceListCompatible(
            p25, DecoderType.P25_PHASE2));
        assertFalse(SystemTalkgroupSelectionEditor.isRadioReferenceListCompatible(
            p25, DecoderType.DMR));
    }

    @Test
    void identifiesMissingIdenticalAndDifferentImports()
    {
        Talkgroup talkgroup = new Talkgroup();
        talkgroup.setAlphaTag("LORAIN DISP");
        talkgroup.setDescription("Lorain County dispatch");

        TalkgroupCategory category = new TalkgroupCategory();
        category.setName("Law Dispatch");

        assertEquals(SystemTalkgroupSelectionEditor.ImportStatus.NOT_PRESENT,
            SystemTalkgroupSelectionEditor.getImportStatus(null, talkgroup, category));

        Alias alias = new Alias("LORAIN DISP");
        alias.setDescription("Lorain County dispatch");
        alias.setGroup("Law Dispatch");
        assertEquals(SystemTalkgroupSelectionEditor.ImportStatus.IDENTICAL,
            SystemTalkgroupSelectionEditor.getImportStatus(alias, talkgroup, category));

        alias.setDescription("Locally edited description");
        assertEquals(SystemTalkgroupSelectionEditor.ImportStatus.DIFFERENT,
            SystemTalkgroupSelectionEditor.getImportStatus(alias, talkgroup, category));
    }

    @Test
    void marksUnsupportedSystemTalkgroupsAsNotCompatible()
    {
        Talkgroup talkgroup = new Talkgroup();
        talkgroup.setAlphaTag("RETIRED SYSTEM");
        Alias alias = new Alias("RETIRED SYSTEM");

        assertEquals(SystemTalkgroupSelectionEditor.ImportStatus.NOT_COMPATIBLE,
            SystemTalkgroupSelectionEditor.getImportStatus(false, null, talkgroup, null));
        assertEquals(SystemTalkgroupSelectionEditor.ImportStatus.NOT_COMPATIBLE,
            SystemTalkgroupSelectionEditor.getImportStatus(false, alias, talkgroup, null));
    }

    @Test
    void ignoresLocalFieldsAndUnavailableCategoryEnrichment()
    {
        Talkgroup talkgroup = new Talkgroup();
        talkgroup.setAlphaTag("FIRE TAC");
        talkgroup.setDescription("Fire tactical");

        Alias alias = new Alias(" FIRE TAC ");
        alias.setDescription("Fire tactical");
        alias.setGroup("Locally organized");
        alias.setColor(0x123456);

        assertEquals(SystemTalkgroupSelectionEditor.ImportStatus.IDENTICAL,
            SystemTalkgroupSelectionEditor.getImportStatus(alias, talkgroup, null));
    }

    @Test
    void requiresAnExactTalkgroupIdentifier()
    {
        io.github.dsheirer.alias.id.talkgroup.Talkgroup expected =
            new io.github.dsheirer.alias.id.talkgroup.Talkgroup(Protocol.APCO25, 13501);
        Alias alias = new Alias("LORAIN DISP");
        alias.setMatchIdentifier(new TalkgroupRange(Protocol.APCO25, 13000, 14000));

        assertFalse(SystemTalkgroupSelectionEditor.hasExactTalkgroup(alias, expected));

        alias.setMatchIdentifier(
            new io.github.dsheirer.alias.id.talkgroup.Talkgroup(Protocol.APCO25, 13501));
        assertTrue(SystemTalkgroupSelectionEditor.hasExactTalkgroup(alias, expected));
    }

    @Test
    void currentExactAliasWinsEvenWhenARangeAlsoMatches()
    {
        AliasListDefinition definition = new AliasListDefinition("County P25", AliasListFamily.P25);
        Alias range = new Alias("Range");
        range.setMatchIdentifier(new TalkgroupRange(Protocol.APCO25, 100, 200));
        Alias exact = new Alias("Exact");
        exact.setMatchIdentifier(new io.github.dsheirer.alias.id.talkgroup.Talkgroup(Protocol.APCO25, 125));
        AliasList aliasList = new AliasList(definition);
        aliasList.addAliases(List.of(range, exact));
        Talkgroup talkgroup = new Talkgroup();
        talkgroup.setDecimalValue(125);
        RadioReferenceDecoder decoder = new RadioReferenceDecoder(null, Map.of(), Map.of(), Map.of(), Map.of())
        {
            @Override
            public TalkgroupIdentifier getIdentifier(Talkgroup ignored, System system)
            {
                return APCO25Talkgroup.create(125);
            }

            @Override
            public io.github.dsheirer.alias.id.talkgroup.Talkgroup getTalkgroupAliasId(
                Talkgroup ignored, System system)
            {
                return new io.github.dsheirer.alias.id.talkgroup.Talkgroup(Protocol.APCO25, 125);
            }
        };

        assertEquals(exact,
            SystemTalkgroupSelectionEditor.findExactAlias(aliasList, decoder, talkgroup, new System()));
    }

    @Test
    void importActionsResolveCurrentAliasesBeforeStaging() throws Exception
    {
        Path directory = Path.of("src/main/java/io/github/dsheirer/gui/configuration/radioreference");
        String bulkEditor = Files.readString(directory.resolve("SystemTalkgroupSelectionEditor.java"));
        String itemEditor = Files.readString(directory.resolve("TalkgroupEditor.java"));

        assertTrue(bulkEditor.contains("Alias currentAlias = getAlias(talkgroup)"));
        assertTrue(itemEditor.contains("Alias currentAlias = resolveCurrentAlias()"));
        assertTrue(itemEditor.contains("Alias alias = resolveCurrentAlias()"));
        assertTrue(itemEditor.contains(".createAlias(alias, revision)"));
        assertFalse(bulkEditor.contains("RadioReferenceAliasPlaybackPolicy.apply("));
        assertFalse(itemEditor.contains("RadioReferenceAliasPlaybackPolicy.apply("));
        assertFalse(bulkEditor.contains("Set Encrypted Talkgroups To Muted"));
        assertFalse(bulkEditor.contains("isEncryptedTalkgroupDoNotMonitor()"));
        assertFalse(bulkEditor.contains("setEncryptedTalkgroupDoNotMonitor("));
    }

    @Test
    void reportsAndUpdatesOnlyRadioReferenceOwnedFields()
    {
        Talkgroup talkgroup = new Talkgroup();
        talkgroup.setAlphaTag("NEW NAME");
        talkgroup.setDescription("New description");
        TalkgroupCategory category = new TalkgroupCategory();
        category.setName("New group");

        Alias alias = new Alias("Local name");
        alias.setId(41L);
        alias.setDescription("Local description");
        alias.setGroup("Local group");
        alias.setColor(0x123456);
        alias.setIconName("Local icon");
        alias.setRecordable(true);
        alias.addBroadcastChannel("Local Stream");
        alias.setStreamTalkgroupAlias(new StreamAsTalkgroup(9001));
        alias.setMatchIdentifier(new io.github.dsheirer.alias.id.talkgroup.Talkgroup(Protocol.APCO25, 125));

        List<SystemTalkgroupSelectionEditor.ImportedFieldChange> changes =
            SystemTalkgroupSelectionEditor.getImportedFieldChanges(alias, talkgroup, category);
        assertEquals(List.of("Name", "Description", "Group"),
            changes.stream().map(SystemTalkgroupSelectionEditor.ImportedFieldChange::field).toList());

        Alias replacement = SystemTalkgroupSelectionEditor.createRadioReferenceReplacement(alias, talkgroup,
            category);

        assertEquals("Local name", alias.getName());
        assertEquals("Local description", alias.getDescription());
        assertEquals("Local group", alias.getGroup());
        assertEquals(41L, replacement.getId());
        assertEquals("NEW NAME", replacement.getName());
        assertEquals("New description", replacement.getDescription());
        assertEquals("New group", replacement.getGroup());
        assertEquals(0x123456, replacement.getColor());
        assertEquals("Local icon", replacement.getIconName());
        assertTrue(replacement.isRecordable());
        assertTrue(replacement.hasBroadcastChannel("Local Stream"));
        assertEquals(9001, replacement.getStreamTalkgroupAlias().getValue());
        assertTrue(alias.getMatchIdentifier().matches(replacement.getMatchIdentifier()));
    }

    @Test
    void radioReferenceReplacementPreservesGroupWithoutCategoryEnrichment()
    {
        Talkgroup talkgroup = new Talkgroup();
        talkgroup.setAlphaTag("UPDATED");
        talkgroup.setDescription("Updated description");
        Alias alias = new Alias("Local name");
        alias.setId(42L);
        alias.setGroup("Local group");

        Alias replacement = SystemTalkgroupSelectionEditor.createRadioReferenceReplacement(alias, talkgroup, null);

        assertEquals("UPDATED", replacement.getName());
        assertEquals("Updated description", replacement.getDescription());
        assertEquals("Local group", replacement.getGroup());
    }
}
