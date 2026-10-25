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
import javax.swing.JRadioButton;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class MigrationContentChoicesTest
{
    @TempDir Path temporary;

    @Test void fullHistoryStartsSelectedAndBothChoicesDescribeTheirConsequences() throws Exception
    {
        Path source = temporary.resolve("selected.sqlite");
        Files.writeString(source, "Size-only UI fixture");
        SwingUtilities.invokeAndWait(() -> {
            AtomicReference<MigrationContent> changed = new AtomicReference<>();
            MigrationContentChoices choices = new MigrationContentChoices(source, MigrationContent.FULL, changed::set);
            assertEquals(MigrationContent.FULL, choices.selectedContent());
            assertTrue(radio(choices, "Configuration, activity, and statistics").isSelected());
            assertFalse(radio(choices, "Configuration only").isSelected());
            String copy = copy(choices);
            assertTrue(copy.contains("Past activity, statistics, and alert history start fresh."));
            assertTrue(copy.contains("Channels, aliases, accounts, settings, and recordings are kept."));
            assertTrue(copy.contains("Allow a few minutes."));
            assertTrue(copy.contains("It may take longer."));
            radio(choices, "Configuration only").doClick();
            assertEquals(MigrationContent.CONFIGURATION_ONLY, choices.selectedContent());
            assertEquals(MigrationContent.CONFIGURATION_ONLY, changed.get());
            radio(choices, "Configuration, activity, and statistics").doClick();
            assertEquals(MigrationContent.FULL, choices.selectedContent());
            assertEquals(MigrationContent.FULL, changed.get());
        });
        assertEquals("Size-only UI fixture", Files.readString(source));
    }

    @Test void retryCanRestoreTheChoiceAndUnavailableSizeDoesNotBlockSelection() throws Exception
    {
        SwingUtilities.invokeAndWait(() -> {
            MigrationContentChoices choices = new MigrationContentChoices(temporary.resolve("not-present.sqlite"),
                MigrationContent.CONFIGURATION_ONLY, ignored -> fail("Initial rendering must not change the choice"));
            assertEquals(MigrationContent.CONFIGURATION_ONLY, choices.selectedContent());
            assertTrue(radio(choices, "Configuration only").isSelected());
            assertTrue(copy(choices).contains("Time estimate unavailable."));
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

    private static JRadioButton radio(Container parent, String name)
    {
        return components(parent).stream().filter(component -> component instanceof JRadioButton radio &&
            name.equals(radio.getAccessibleContext().getAccessibleName()))
            .map(component -> (JRadioButton)component).findFirst().orElseThrow();
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
