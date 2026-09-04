package io.github.dsheirer.gui.setup;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.SwingConstants;

/** A full-width clickable header and a padded, visibly grouped expanded body. */
final class WizardDisclosure extends JPanel
{
    private final JButton toggle;
    private final JTextArea content;

    WizardDisclosure(String caption, String message)
    {
        super(new BorderLayout());
        setAlignmentX(Component.LEFT_ALIGNMENT);
        toggle = WizardStyles.secondary(new JButton("▸  " + caption));
        toggle.setHorizontalAlignment(SwingConstants.LEFT);
        toggle.getAccessibleContext().setAccessibleName(caption);
        toggle.getAccessibleContext().setAccessibleDescription("Expand details");
        content = WizardStyles.prose(message);
        content.setOpaque(true);
        content.setVisible(false);
        content.setBorder(BorderFactory.createEmptyBorder(16, 18, 18, 18));
        add(toggle, BorderLayout.NORTH);
        add(content, BorderLayout.CENTER);
        toggle.addActionListener(event -> {
            boolean expanded = !content.isVisible();
            content.setVisible(expanded);
            toggle.setText((expanded ? "▾  " : "▸  ") + caption);
            toggle.getAccessibleContext().setAccessibleDescription(expanded ? "Collapse details" : "Expand details");
            revalidate();
            repaint();
        });
        Runnable colors = () -> {
            setBackground(WizardStyles.surface());
            content.setBackground(WizardStyles.surface());
            content.setForeground(WizardStyles.foreground());
        };
        addPropertyChangeListener("UI", event -> colors.run());
        content.addPropertyChangeListener("UI", event -> colors.run());
        colors.run();
    }

    @Override public Dimension getMaximumSize()
    {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }
}
