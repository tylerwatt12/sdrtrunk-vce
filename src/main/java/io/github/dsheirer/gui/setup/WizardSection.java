package io.github.dsheirer.gui.setup;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSeparator;

/** A compact, theme-aware heading and divider for related wizard form fields. */
final class WizardSection extends JPanel
{
    WizardSection(String caption, JComponent content)
    {
        setOpaque(false);
        setAlignmentX(Component.LEFT_ALIGNMENT);
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        getAccessibleContext().setAccessibleName(caption);
        Box heading = Box.createHorizontalBox();
        heading.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel title = new JLabel(caption);
        WizardStyles.bodyFont(title, Font.BOLD);
        heading.add(title);
        heading.add(Box.createHorizontalStrut(12));
        JSeparator divider = new JSeparator();
        divider.setMaximumSize(new Dimension(Integer.MAX_VALUE, 2));
        heading.add(divider);
        add(heading);
        add(Box.createVerticalStrut(8));
        content.setAlignmentX(Component.LEFT_ALIGNMENT);
        add(content);
    }

    @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
}
