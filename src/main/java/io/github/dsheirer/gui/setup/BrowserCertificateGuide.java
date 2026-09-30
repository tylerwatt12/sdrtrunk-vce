package io.github.dsheirer.gui.setup;

import com.formdev.flatlaf.util.UIScale;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.LayoutManager;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.net.URI;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.border.Border;

/** An illustration of the two browser controls used to open the local VCE web address. */
final class BrowserCertificateGuide extends JPanel
{
    private static final Color BROWSER = new Color(0x2d2d2d);
    private static final Color TOOLBAR = new Color(0x202020);
    private static final Color ADDRESS = new Color(0x373737);
    private static final Color BROWSER_TEXT = new Color(0xf8f8f8);
    private static final Color BROWSER_MUTED = new Color(0xb9b9b9);
    private static final Color LINK = new Color(0x72bef6);
    private static final Color HIGHLIGHT = new Color(0xffd54f);
    private static final int GAP = 12;

    BrowserCertificateGuide(String webAddress)
    {
        super(new BorderLayout(0, UIScale.scale(8)));
        URI address = URI.create(webAddress);
        String host = address.getHost();
        if(!"https".equalsIgnoreCase(address.getScheme()) || host == null)
            throw new IllegalArgumentException("A full HTTPS web address is required");

        setOpaque(false);
        setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel title = label("Browser warning example  ·  the two highlighted places to click", 13, Font.BOLD,
            WizardStyles.foreground());
        add(title, BorderLayout.NORTH);

        JPanel pair = new ResponsivePair(step(1, "Click “Advanced”", webAddress, host, false),
            step(2, "Click “Continue to " + host + " (unsafe)”", webAddress, host, true));
        pair.setOpaque(false);
        add(pair, BorderLayout.CENTER);

        JLabel note = label("Illustration based on a browser warning. Button wording can vary by browser.",
            12, Font.PLAIN, WizardStyles.muted());
        add(note, BorderLayout.SOUTH);
        getAccessibleContext().setAccessibleName("Browser certificate warning example");
        getAccessibleContext().setAccessibleDescription("Step 1: choose Advanced. Step 2: choose Continue to " +
            host + " (unsafe). Only do this for the VCE address you intentionally opened.");
        addPropertyChangeListener("UI", event -> {
            title.setForeground(WizardStyles.foreground());
            note.setForeground(WizardStyles.muted());
        });
    }

    @Override public Dimension getMaximumSize()
    {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }

    private static JPanel step(int number, String instruction, String address, String host, boolean expanded)
    {
        JPanel step = new JPanel(new BorderLayout(0, UIScale.scale(7)));
        step.setOpaque(false);
        JLabel heading = label(number + "  " + instruction, 13, Font.BOLD, WizardStyles.foreground());
        step.add(heading, BorderLayout.NORTH);
        step.add(browser(address, host, expanded), BorderLayout.CENTER);
        step.getAccessibleContext().setAccessibleName("Step " + number + ": " + instruction);
        step.addPropertyChangeListener("UI", event -> heading.setForeground(WizardStyles.foreground()));
        return step;
    }

    private static JPanel browser(String address, String host, boolean expanded)
    {
        JPanel browser = new JPanel(new BorderLayout());
        browser.setBackground(BROWSER);
        browser.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(0x555555)),
            BorderFactory.createEmptyBorder(0, 0, 0, 0)));

        JPanel toolbar = new JPanel(new BorderLayout(UIScale.scale(8), 0));
        toolbar.setBackground(TOOLBAR);
        toolbar.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(0x444444)),
            BorderFactory.createEmptyBorder(6, 10, 6, 10)));
        toolbar.add(label("‹    ↻", 12, Font.PLAIN, BROWSER_MUTED), BorderLayout.WEST);
        JLabel location = label(address, 11, Font.PLAIN, BROWSER_TEXT);
        location.setOpaque(true);
        location.setBackground(ADDRESS);
        location.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        toolbar.add(location, BorderLayout.CENTER);
        browser.add(toolbar, BorderLayout.NORTH);

        JPanel page = new JPanel();
        page.setLayout(new BoxLayout(page, BoxLayout.Y_AXIS));
        page.setBackground(BROWSER);
        page.setBorder(BorderFactory.createEmptyBorder(14, 15, 15, 15));

        JPanel warning = new JPanel(new BorderLayout(UIScale.scale(9), 0));
        warning.setOpaque(false);
        JLabel icon = new JLabel(new WarningIcon());
        warning.add(icon, BorderLayout.WEST);
        JLabel warningTitle = label("Your connection isn't private", 16, Font.BOLD, BROWSER_TEXT);
        warning.add(warningTitle, BorderLayout.CENTER);
        warning.setAlignmentX(Component.LEFT_ALIGNMENT);
        page.add(warning);
        page.add(Box.createVerticalStrut(UIScale.scale(9)));
        page.add(browserText("Your browser can't verify the certificate for " + host + ".", 11,
            BROWSER_TEXT, 2));

        if(!expanded)
        {
            page.add(Box.createVerticalStrut(UIScale.scale(3)));
            page.add(browserText("Certificate warning", 10, BROWSER_MUTED, 1));
        }
        page.add(Box.createVerticalStrut(UIScale.scale(12)));

        JPanel actions = new JPanel(new BorderLayout());
        actions.setOpaque(false);
        actions.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel advanced = button(expanded ? "Hide Advanced" : "Advanced", false, !expanded);
        actions.add(advanced, BorderLayout.WEST);
        actions.add(button("Go Back", true, false), BorderLayout.EAST);
        page.add(actions);

        if(expanded)
        {
            page.add(Box.createVerticalStrut(UIScale.scale(14)));
            page.add(browserText("Certificate details are shown here in the browser.", 10,
                BROWSER_TEXT, 2));
            page.add(Box.createVerticalStrut(UIScale.scale(8)));
            JLabel proceed = label("Continue to " + host + " (unsafe)", 11, Font.PLAIN, LINK);
            proceed.setBorder(highlight(BorderFactory.createEmptyBorder(5, 5, 5, 5)));
            proceed.setAlignmentX(Component.LEFT_ALIGNMENT);
            page.add(proceed);
        }

        browser.add(page, BorderLayout.CENTER);
        browser.getAccessibleContext().setAccessibleName(expanded ?
            "Expanded browser warning with Continue link highlighted" :
            "Browser warning with Advanced button highlighted");
        return browser;
    }

    private static JLabel button(String text, boolean primary, boolean highlighted)
    {
        JLabel button = label(text, 11, primary ? Font.BOLD : Font.PLAIN, BROWSER_TEXT);
        button.setOpaque(primary);
        if(primary) button.setBackground(new Color(0x0879d1));
        Border inner = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(primary ? new Color(0x0879d1) : new Color(0x666666)),
            BorderFactory.createEmptyBorder(6, 9, 6, 9));
        button.setBorder(highlighted ? highlight(inner) : inner);
        return button;
    }

    private static Border highlight(Border inner)
    {
        return BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(HIGHLIGHT, 3), inner);
    }

    private static JLabel label(String value, int size, int weight, Color color)
    {
        JLabel label = new JLabel(value);
        label.setForeground(color);
        label.setFont(new Font(Font.SANS_SERIF, weight, UIScale.scale(size)));
        return label;
    }

    private static JTextArea browserText(String value, int size, Color color, int rows)
    {
        JTextArea text = new JTextArea(value);
        text.setEditable(false);
        text.setFocusable(false);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setRows(rows);
        text.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, UIScale.scale(size)));
        text.setForeground(color);
        text.setOpaque(false);
        text.setBorder(null);
        text.setAlignmentX(Component.LEFT_ALIGNMENT);
        return text;
    }

    private static final class WarningIcon implements Icon
    {
        public int getIconWidth() { return UIScale.scale(34); }
        public int getIconHeight() { return UIScale.scale(32); }

        public void paintIcon(Component component, Graphics graphics, int x, int y)
        {
            Graphics2D g = (Graphics2D)graphics.create();
            try
            {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int width = getIconWidth(), height = getIconHeight();
                g.setColor(new Color(0xd70000));
                g.fill(new Polygon(new int[]{x + width / 2, x + width, x},
                    new int[]{y, y + height, y + height}, 3));
                g.setColor(BROWSER);
                g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, UIScale.scale(23)));
                g.drawString("!", x + width / 2 - UIScale.scale(4), y + height - UIScale.scale(4));
            }
            finally { g.dispose(); }
        }
    }

    /** Keeps the screenshots readable on narrower wizard windows by stacking them. */
    private static final class ResponsivePair extends JPanel
    {
        private final JComponent first;
        private final JComponent second;
        private boolean stacked;

        ResponsivePair(JComponent first, JComponent second)
        {
            super((LayoutManager)null);
            this.first = first;
            this.second = second;
            add(first);
            add(second);
        }

        @Override public Dimension getPreferredSize()
        {
            int gap = UIScale.scale(GAP);
            int width = availableWidth();
            Dimension a = first.getPreferredSize(), b = second.getPreferredSize();
            if(width < UIScale.scale(620))
                return new Dimension(Math.max(a.width, b.width), a.height + b.height + gap);
            return new Dimension(a.width + b.width + gap, Math.max(a.height, b.height));
        }

        private int availableWidth()
        {
            if(getWidth() > 0) return getWidth();
            for(Container ancestor = getParent(); ancestor != null; ancestor = ancestor.getParent())
            {
                if(ancestor.getWidth() > 0) return ancestor.getWidth();
            }
            //Before the first layout, allow enough height for the narrower stacked presentation.
            return 0;
        }

        @Override public void doLayout()
        {
            int gap = UIScale.scale(GAP);
            boolean nowStacked = getWidth() < UIScale.scale(620);
            int firstHeight = first.getPreferredSize().height;
            int secondHeight = second.getPreferredSize().height;
            if(nowStacked)
            {
                first.setBounds(0, 0, getWidth(), firstHeight);
                second.setBounds(0, firstHeight + gap, getWidth(), secondHeight);
            }
            else
            {
                int column = (getWidth() - gap) / 2;
                int height = Math.max(firstHeight, secondHeight);
                first.setBounds(0, 0, column, height);
                second.setBounds(column + gap, 0, getWidth() - column - gap, height);
            }
            if(stacked != nowStacked)
            {
                stacked = nowStacked;
                revalidate();
            }
        }
    }
}
