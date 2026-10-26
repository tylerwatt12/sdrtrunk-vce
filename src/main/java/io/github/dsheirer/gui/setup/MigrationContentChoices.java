package io.github.dsheirer.gui.setup;

import io.github.dsheirer.database.upgrade.ApplicationMigrationEstimate;
import io.github.dsheirer.database.upgrade.DatabaseMigrationChain;
import io.github.dsheirer.database.upgrade.MigrationContent;
import java.awt.Component;
import java.awt.Font;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Consumer;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JPanel;
import javax.swing.JTextArea;

/** The same explicit history choice for startup updates and selected-database imports. */
public final class MigrationContentChoices extends JPanel
{
    private final JCheckBox keepHistory = new JCheckBox("Keep activity and statistics");

    public MigrationContentChoices(Path database, MigrationContent selected,
                                   Consumer<MigrationContent> selectionChanged)
    {
        Objects.requireNonNull(selected);
        Objects.requireNonNull(selectionChanged);
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setOpaque(false);
        setAlignmentX(Component.LEFT_ALIGNMENT);
        keepHistory.setSelected(selected == MigrationContent.FULL);
        keepHistory.setOpaque(false);
        keepHistory.setAlignmentX(Component.LEFT_ALIGNMENT);
        WizardStyles.bodyFont(keepHistory, Font.PLAIN);
        String estimate = ApplicationMigrationEstimate.fullMigrationWarning(database);
        String loss = "Past activity, statistics, and alert history will start fresh. " +
            "Your channels, aliases, accounts, and settings are kept.";
        JTextArea help = WizardStyles.prose(keepHistory.isSelected() ? estimate : loss);
        add(keepHistory);
        add(Box.createVerticalStrut(8));
        add(help);
        keepHistory.addItemListener(event -> {
            help.setText(keepHistory.isSelected() ? estimate : loss);
            selectionChanged.accept(selectedContent());
            revalidate();
        });
    }

    /** Current-format repairs do not offer a destructive history reset. */
    public static boolean isAvailable(DatabaseMigrationChain.PreflightReport plan)
    {
        return plan.source().version() < plan.target().version();
    }

    public MigrationContent selectedContent()
    {
        return keepHistory.isSelected() ? MigrationContent.FULL : MigrationContent.CONFIGURATION_ONLY;
    }
}
