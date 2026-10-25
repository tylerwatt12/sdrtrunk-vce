package io.github.dsheirer.gui.setup;

import io.github.dsheirer.database.upgrade.ApplicationMigrationEstimate;
import io.github.dsheirer.database.upgrade.DatabaseMigrationChain;
import io.github.dsheirer.database.upgrade.MigrationContent;
import java.awt.Component;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Consumer;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JPanel;

/** The same explicit history choice for startup updates and selected-database imports. */
public final class MigrationContentChoices extends JPanel
{
    private final WizardChoiceCard configuration;
    private final WizardChoiceCard full;

    public MigrationContentChoices(Path database, MigrationContent selected,
                                   Consumer<MigrationContent> selectionChanged)
    {
        Objects.requireNonNull(selected);
        Objects.requireNonNull(selectionChanged);
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setOpaque(false);
        setAlignmentX(Component.LEFT_ALIGNMENT);
        ButtonGroup group = new ButtonGroup();
        configuration = new WizardChoiceCard(group, "Configuration only",
            "Past activity, statistics, and alert history start fresh. Channels, aliases, accounts, settings, and recordings are kept.",
            selected == MigrationContent.CONFIGURATION_ONLY);
        full = new WizardChoiceCard(group, "Configuration, activity, and statistics",
            ApplicationMigrationEstimate.fullMigrationWarning(database), selected == MigrationContent.FULL);
        add(configuration);
        add(Box.createVerticalStrut(12));
        add(full);
        configuration.radio().addItemListener(event -> {
            if(configuration.isSelected()) selectionChanged.accept(MigrationContent.CONFIGURATION_ONLY);
        });
        full.radio().addItemListener(event -> {
            if(full.isSelected()) selectionChanged.accept(MigrationContent.FULL);
        });
    }

    /** Current-format repairs do not offer a destructive history reset. */
    public static boolean isAvailable(DatabaseMigrationChain.PreflightReport plan)
    {
        return plan.source().version() < plan.target().version();
    }

    public MigrationContent selectedContent()
    {
        return configuration.isSelected() ? MigrationContent.CONFIGURATION_ONLY : MigrationContent.FULL;
    }
}
