package io.github.dsheirer.gui.setup;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.JTextArea;

/** Persistent, readable setup feedback; color supplements the explicit status text. */
final class WizardNotice extends JPanel
{
    enum Tone { INFO, SUCCESS, WARNING, ERROR }

    WizardNotice(String heading, String message, Tone tone)
    {
        super(new BorderLayout(0, 12));
        setAlignmentX(Component.LEFT_ALIGNMENT);
        JTextArea title = WizardStyles.prose((tone == Tone.SUCCESS ? "✓  " : "") + heading, Font.BOLD, 16f);
        JTextArea body = WizardStyles.prose(message);
        add(title, BorderLayout.NORTH);
        add(body, BorderLayout.CENTER);
        getAccessibleContext().setAccessibleName(heading);
        getAccessibleContext().setAccessibleDescription(message);
        putClientProperty("wizard.noticeTone", tone);
        Runnable colors = () -> {
            setBackground(background(tone));
            setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 4, 0, 0, accent(tone)),
                BorderFactory.createEmptyBorder(18, 18, 16, 18)));
            title.setForeground(accent(tone));
            body.setForeground(WizardStyles.foreground());
        };
        addPropertyChangeListener("UI", event -> colors.run());
        colors.run();
    }

    @Override public Dimension getMaximumSize()
    {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }

    static void styleError(JTextArea message)
    {
        message.setOpaque(true);
        message.setForeground(accent(Tone.ERROR));
        message.setBackground(background(Tone.ERROR));
        message.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 4, 0, 0, accent(Tone.ERROR)),
            BorderFactory.createEmptyBorder(18, 18, 16, 18)));
    }

    static Color accent(Tone tone)
    {
        boolean dark = dark();
        return switch(tone) {
            case SUCCESS -> new Color(dark ? 0x5ecd99 : 0x1c744e);
            case WARNING -> new Color(dark ? 0xffcb77 : 0x805500);
            case ERROR -> new Color(dark ? 0xff8791 : 0xae1c2a);
            case INFO -> WizardStyles.accent();
        };
    }

    static Color background(Tone tone)
    {
        boolean dark = dark();
        return switch(tone) {
            case SUCCESS -> new Color(dark ? 0x1f382f : 0xebf7f0);
            case WARNING -> new Color(dark ? 0x413724 : 0xfff6df);
            case ERROR -> new Color(dark ? 0x402a31 : 0xfff0f2);
            case INFO -> WizardStyles.surface();
        };
    }

    private static boolean dark()
    {
        Color background = WizardStyles.background();
        return background.getRed() + background.getGreen() + background.getBlue() < 384;
    }
}
