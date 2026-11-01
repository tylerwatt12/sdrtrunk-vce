package io.github.dsheirer.gui.setup;

import io.github.dsheirer.database.upgrade.DatabaseFormatCatalog;
import io.github.dsheirer.database.upgrade.DatabaseMigrationChain;
import io.github.dsheirer.database.upgrade.MigrationContent;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JCheckBox;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class MigrationContentChoicesTest
{
    @TempDir Path temporary;

    @Test void historyRequiresAnExplicitSelectionAndExplainsTheLossBeforeItIsSelected() throws Exception
    {
        Path source = temporary.resolve("selected.sqlite");
        Files.writeString(source, "Size-only UI fixture");
        SwingUtilities.invokeAndWait(() -> {
            AtomicReference<MigrationContent> changed = new AtomicReference<>();
            MigrationContentChoices choices = new MigrationContentChoices(source, MigrationContent.CONFIGURATION_ONLY, changed::set);
            assertEquals(MigrationContent.CONFIGURATION_ONLY, choices.selectedContent());
            JCheckBox keep = checkbox(choices);
            assertEquals("Keep learned radio information, activity and statistics", keep.getText());
            assertFalse(keep.isSelected());
            assertNull(changed.get(), "Rendering must not change the approved content");
            String copy = copy(choices);
            assertTrue(copy.contains("Leaving this unchecked discards learned talker aliases (radio names)"));
            assertTrue(copy.contains("signal-quality history, receiver-health history, alert history and unsent notifications."));
            assertTrue(copy.contains("Your configured channels, aliases and Alias Lists, accounts, settings and recordings are kept."));
            keep.doClick();
            assertEquals(MigrationContent.FULL, choices.selectedContent());
            assertEquals(MigrationContent.FULL, changed.get());
            assertEquals("Keeps usable learned radio information, Activity history and statistics.", copy(choices));
            keep.doClick();
            assertEquals(MigrationContent.CONFIGURATION_ONLY, choices.selectedContent());
            assertEquals(MigrationContent.CONFIGURATION_ONLY, changed.get());
            assertEquals(copy, copy(choices));
        });
        assertEquals("Size-only UI fixture", Files.readString(source));
    }

    @Test void retryCanRestoreTheChoiceAndUnavailableSourceDoesNotBlockSelection() throws Exception
    {
        SwingUtilities.invokeAndWait(() -> {
            MigrationContentChoices choices = new MigrationContentChoices(temporary.resolve("not-present.sqlite"),
                MigrationContent.CONFIGURATION_ONLY, ignored -> fail("Initial rendering must not change the choice"));
            assertEquals(MigrationContent.CONFIGURATION_ONLY, choices.selectedContent());
            assertFalse(checkbox(choices).isSelected());
            assertTrue(copy(choices).contains("alert history and unsent notifications"));
            MigrationContentChoices full = new MigrationContentChoices(temporary.resolve("not-present.sqlite"),
                MigrationContent.FULL, ignored -> fail("Initial rendering must not change the choice"));
            assertTrue(checkbox(full).isSelected());
            assertEquals("Keeps usable learned radio information, Activity history and statistics.", copy(full));
        });
    }

    @Test void onlyOlderFormatsOfferTheHistoryChoiceEvenWhenCurrentDataNeedsRepair()
    {
        var current = DatabaseFormatCatalog.current();
        var older = DatabaseFormatCatalog.formats().stream().filter(format -> format.version() == 60)
            .findFirst().orElseThrow();
        var olderPlan = new DatabaseMigrationChain.PreflightReport(
            new DatabaseFormatCatalog.DetectedFormat(older, true), current, List.of());
        assertTrue(MigrationContentChoices.isAvailable(olderPlan));
        var repair = new DatabaseMigrationChain.StepPreflight("repair", "Repair saved settings",
            current.version(), current.version(), List.of());
        var currentRepair = new DatabaseMigrationChain.PreflightReport(
            new DatabaseFormatCatalog.DetectedFormat(current, true), current, List.of(repair));
        assertTrue(currentRepair.requiresMigration());
        assertFalse(MigrationContentChoices.isAvailable(currentRepair));
        var markerAdoption = new DatabaseMigrationChain.PreflightReport(
            new DatabaseFormatCatalog.DetectedFormat(current, false), current, List.of());
        assertTrue(markerAdoption.requiresMigration());
        assertFalse(MigrationContentChoices.isAvailable(markerAdoption));
    }

    private static JCheckBox checkbox(Container parent)
    {
        List<JCheckBox> checkboxes = components(parent).stream().filter(JCheckBox.class::isInstance)
            .map(JCheckBox.class::cast).toList();
        assertEquals(1, checkboxes.size());
        return checkboxes.getFirst();
    }

    private static String copy(Container parent)
    {
        return String.join("\n", components(parent).stream().filter(JTextArea.class::isInstance)
            .map(component -> ((JTextArea)component).getText()).toList());
    }

    private static List<Component> components(Container parent)
    {
        List<Component> result = new ArrayList<>();
        for(Component component: parent.getComponents())
        {
            result.add(component);
            if(component instanceof Container child) result.addAll(components(child));
        }
        return result;
    }
}
