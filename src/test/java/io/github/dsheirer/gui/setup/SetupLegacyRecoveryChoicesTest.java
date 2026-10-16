/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.gui.setup;

import io.github.dsheirer.database.settings.ReceiverSettingsSchema;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.JCheckBox;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SetupLegacyRecoveryChoicesTest
{
    @Test void validOrdinaryStartupNeverReadsUnusedLegacyDocuments()
    {
        var result = SetupWizard.discoverLegacyRecovery(false, false, () -> {
            throw new AssertionError("Normal startup must not inspect even a malformed legacy file");
        });
        assertEquals(Set.of(), result.keys());
        assertFalse(result.unavailable());
    }

    @Test void optionalDiscoveryFailuresCannotBlockCurrentSettingsAndExplicitVisitsCanFindRecovery()
    {
        var available = SetupWizard.discoverLegacyRecovery(false, true,
            () -> Set.of(ReceiverSettingsSchema.REMOTE_LINKS_KEY));
        assertEquals(Set.of(ReceiverSettingsSchema.REMOTE_LINKS_KEY), available.keys());
        assertFalse(available.unavailable());
        for(boolean migrationRequired: new boolean[]{true, false})
        {
            var failed = SetupWizard.discoverLegacyRecovery(migrationRequired, true,
                () -> { throw new java.io.IOException("Unusable legacy settings"); });
            assertEquals(Set.of(), failed.keys());
            assertTrue(failed.unavailable());
        }
    }

    @Test void validatedChoicesStartUncheckedAndOnlyExplicitSelectionsAuthorizeReplacement() throws Exception
    {
        SwingUtilities.invokeAndWait(() -> {
            var choices = new SetupWizard.LegacyRecoveryChoices(Set.copyOf(ReceiverSettingsSchema.KEYS));
            assertEquals(2, choices.choices().size());
            assertTrue(choices.choices().values().stream().noneMatch(JCheckBox::isSelected));
            assertFalse(choices.anySelected());
            assertEquals(Map.of(), choices.selectedDefaults());

            choices.choices().get(ReceiverSettingsSchema.REMOTE_LINKS_KEY).doClick();
            assertTrue(choices.anySelected());
            Map<String,String> selected = choices.selectedDefaults();
            assertEquals(Set.of(ReceiverSettingsSchema.REMOTE_LINKS_KEY), selected.keySet());
            try
            {
                assertEquals(ReceiverSettingsSchema.defaultJson(ReceiverSettingsSchema.REMOTE_LINKS_KEY),
                    selected.get(ReceiverSettingsSchema.REMOTE_LINKS_KEY));
            }
            catch(java.sql.SQLException failure) { throw new AssertionError(failure); }

            choices.choices().get(ReceiverSettingsSchema.REMOTE_LINKS_KEY).doClick();
            assertFalse(choices.anySelected());
            assertEquals(Map.of(), choices.selectedDefaults());
            assertEquals(1, selected.size(), "A reviewed selection must be detached from later checkbox changes");
            assertThrows(UnsupportedOperationException.class, () -> selected.put("other", "{}"));
        });
    }

    @Test void retiredAndUnknownSettingsCannotBeRecoveredThroughTheWizard() throws Exception
    {
        SwingUtilities.invokeAndWait(() -> {
            var choices = new SetupWizard.LegacyRecoveryChoices(Set.of(
                ReceiverSettingsSchema.PAGING_TONES_KEY, ReceiverSettingsSchema.VOICE_ENHANCEMENT_KEY, "unknown"));
            assertEquals(Set.of(ReceiverSettingsSchema.PAGING_TONES_KEY), choices.choices().keySet());
            choices.choices().get(ReceiverSettingsSchema.PAGING_TONES_KEY).doClick();
            assertEquals(Map.of(ReceiverSettingsSchema.PAGING_TONES_KEY, "{\"enabled\":true}"),
                choices.selectedDefaults());
            var empty = new SetupWizard.LegacyRecoveryChoices(Set.of(
                ReceiverSettingsSchema.VOICE_ENHANCEMENT_KEY, "unknown"));
            assertEquals(Map.of(), empty.selectedDefaults());
            assertFalse(empty.anySelected());
        });
    }

    @Test void recoveryControlsExposeFriendlyLabelsWithoutLegacyValues() throws Exception
    {
        SwingUtilities.invokeAndWait(() -> {
            var choices = new SetupWizard.LegacyRecoveryChoices(Set.copyOf(ReceiverSettingsSchema.KEYS));
            List<String> labels = new ArrayList<>();
            collectLabels(choices.panel(), labels);
            assertEquals(List.of("Recover saved Remote Links", "Recover saved paging-tone detection"), labels);
            assertTrue(labels.stream().noneMatch(label -> label.contains("{") || label.contains("senderId") ||
                label.contains("credential") || label.contains("token")));
        });
    }

    @Test void wizardUsesReviewedDefaultsForRecoveryAndSelectionScopeForFolderApproval() throws Exception
    {
        String source = Files.readString(Path.of("src/main/java/io/github/dsheirer/gui/setup/SetupWizard.java"));
        assertTrue(source.contains("ApplicationMigrationService.availableRecoveryKeys(wizard.root)"));
        assertTrue(source.contains("selectedRecovery.isEmpty() ?"));
        assertTrue(source.contains("service.migrateCurrent(root, createUpgradeBackup, selectedRecovery, this::migrationProgress)"));
        assertTrue(source.contains("new Object[]{\"Recover selected settings\", \"Cancel\"}, \"Cancel\""));
        assertTrue(source.contains("readMigrationApproval(\n                selection, root.getParent())"));
        assertFalse(source.contains("readMigrationApproval(\n                selection.database(), root.getParent())"));
    }

    private static void collectLabels(Container container, List<String> labels)
    {
        for(Component component: container.getComponents())
        {
            if(component instanceof JCheckBox choice) labels.add(choice.getText());
            else if(component instanceof Container child) collectLabels(child, labels);
        }
    }
}
