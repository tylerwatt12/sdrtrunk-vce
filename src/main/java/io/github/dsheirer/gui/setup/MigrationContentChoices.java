package io.github.dsheirer.gui.setup;

import io.github.dsheirer.database.upgrade.DatabaseMigrationChain;
import io.github.dsheirer.database.upgrade.MigrationContent;
import java.awt.Component;
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
    private final JCheckBox keepHistory = WizardStyles.checkBox(
        "Keep learned radio information, activity and statistics", false);

    public MigrationContentChoices(Path database, MigrationContent selected,
                                   Consumer<MigrationContent> selectionChanged)
    {
        Objects.requireNonNull(selected);
        Objects.requireNonNull(selectionChanged);
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setOpaque(false);
        setAlignmentX(Component.LEFT_ALIGNMENT);
        keepHistory.setSelected(selected == MigrationContent.FULL);
        String retained = "Keeps usable learned radio information, Activity history and statistics.";
        String loss = "Leaving this unchecked discards learned talker aliases (radio names) and discovered radio, " +
            "system and site information, including learned frequencies, bandplans and neighbors.\n\n" +
            "It also clears Activity events, traffic statistics, signal-quality history, receiver-health history, " +
            "alert history and unsent notifications.\n\n" +
            "Your configured channels, aliases and Alias Lists, accounts, settings and recordings are kept.";
        JTextArea help = WizardStyles.prose(keepHistory.isSelected() ? retained : loss);
        add(keepHistory);
        add(Box.createVerticalStrut(8));
        add(help);
        keepHistory.addItemListener(event -> {
            help.setText(keepHistory.isSelected() ? retained : loss);
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
