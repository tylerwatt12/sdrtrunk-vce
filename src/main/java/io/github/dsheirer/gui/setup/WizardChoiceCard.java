package io.github.dsheirer.gui.setup;

import com.formdev.flatlaf.util.UIScale;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.border.AbstractBorder;
import javax.swing.plaf.FontUIResource;

/** A single radio choice with readable copy and fields belonging only to the selected choice. */
final class WizardChoiceCard extends JPanel
{
    private final JRadioButton radio;
    private final JPanel content = new JPanel();
    private final JTextArea heading;
    private final JTextArea description;
    private JComponent details;
    private Component detailsGap;

    WizardChoiceCard(ButtonGroup group, String title, String explanation, boolean selected)
    {
        super(new BorderLayout(UIScale.scale(10), 0));
        setOpaque(false);
        setAlignmentX(Component.LEFT_ALIGNMENT);
        setBorder(BorderFactory.createCompoundBorder(new CardBorder(), BorderFactory.createEmptyBorder(
            UIScale.scale(14), UIScale.scale(14), UIScale.scale(14), UIScale.scale(16))));
        radio = new JRadioButton();
        radio.setOpaque(false);
        radio.setSelected(selected);
        radio.getAccessibleContext().setAccessibleName(title);
        radio.getAccessibleContext().setAccessibleDescription(explanation);
        group.add(radio);
        JPanel selector = new JPanel(new BorderLayout());
        selector.setOpaque(false);
        selector.add(radio, BorderLayout.NORTH);
        add(selector, BorderLayout.WEST);

        content.setOpaque(false);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        heading = WizardStyles.prose(title);
        heading.setFocusable(false);
        heading.setFont(new FontUIResource(WizardStyles.bodyFont().deriveFont(Font.BOLD)));
        description = WizardStyles.prose(explanation);
        description.setFocusable(false);
        content.add(heading);
        content.add(Box.createVerticalStrut(UIScale.scale(6)));
        content.add(description);
        add(content, BorderLayout.CENTER);
        getAccessibleContext().setAccessibleName(title);

        MouseAdapter select = new MouseAdapter()
        {
            @Override public void mouseClicked(MouseEvent event)
            {
                if(SwingUtilities.isLeftMouseButton(event) && radio.isEnabled())
                {
                    radio.setSelected(true);
                    radio.requestFocusInWindow();
                }
            }
        };
        addMouseListener(select);
        content.addMouseListener(select);
        selector.addMouseListener(select);
        heading.addMouseListener(select);
        description.addMouseListener(select);
        radio.addItemListener(event -> refreshSelection());
        radio.addFocusListener(new FocusAdapter()
        {
            @Override public void focusGained(FocusEvent event) { repaint(); }
            @Override public void focusLost(FocusEvent event) { repaint(); }
        });
        addPropertyChangeListener("UI", event -> SwingUtilities.invokeLater(this::refreshSelection));
        refreshSelection();
    }

    JRadioButton radio() { return radio; }
    boolean isSelected() { return radio.isSelected(); }

    /** Details retain their entered values while hidden, but take no space and cannot receive focus. */
    void setDetails(JComponent component)
    {
        if(details != null)
        {
            content.remove(details);
            content.remove(detailsGap);
        }
        details = component;
        if(component != null)
        {
            component.setAlignmentX(Component.LEFT_ALIGNMENT);
            detailsGap = Box.createVerticalStrut(UIScale.scale(14));
            content.add(detailsGap);
            content.add(component);
        }
        refreshSelection();
    }

    private void refreshSelection()
    {
        if(details != null)
        {
            details.setVisible(isSelected());
            detailsGap.setVisible(isSelected());
        }
        description.setForeground(WizardStyles.muted());
        heading.setForeground(WizardStyles.foreground());
        revalidate();
        repaint();
    }

    @Override public Dimension getPreferredSize()
    {
        //Measure wrapped copy using the actual page width, not its previous (possibly wider) layout.
        int width = UIScale.scale(560);
        if(getParent() != null && getParent().getWidth() > 0)
        {
            Insets parent = getParent().getInsets();
            width = Math.max(UIScale.scale(160), getParent().getWidth() - parent.left - parent.right);
        }
        Insets insets = getInsets();
        int radioWidth = radio.getPreferredSize().width;
        content.setSize(Math.max(1, width - insets.left - insets.right - radioWidth - UIScale.scale(10)), 1);
        content.invalidate();
        int height = Math.max(radio.getPreferredSize().height, content.getPreferredSize().height);
        return new Dimension(width, height + insets.top + insets.bottom);
    }

    @Override public Dimension getMaximumSize()
    {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }

    @Override protected void paintComponent(Graphics graphics)
    {
        Graphics2D g = (Graphics2D)graphics.create();
        try
        {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color surface = WizardStyles.surface();
            g.setColor(isSelected() ? WizardStyles.blend(WizardStyles.accent(), surface, 0.075f) : surface);
            int arc = UIScale.scale(12);
            g.fillRoundRect(0, 0, getWidth(), getHeight(), arc, arc);
        }
        finally { g.dispose(); }
        super.paintComponent(graphics);
    }

    private final class CardBorder extends AbstractBorder
    {
        @Override public Insets getBorderInsets(Component component, Insets insets)
        {
            int space = UIScale.scale(2);
            insets.set(space, space, space, space);
            return insets;
        }

        @Override public void paintBorder(Component component, Graphics graphics, int x, int y, int width, int height)
        {
            Graphics2D g = (Graphics2D)graphics.create();
            try
            {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                boolean emphasized = isSelected() || radio.hasFocus();
                float stroke = UIScale.scale(emphasized ? 2f : 1f);
                g.setStroke(new BasicStroke(stroke));
                g.setColor(emphasized ? WizardStyles.accent() : WizardStyles.border());
                float inset = stroke / 2;
                g.draw(new java.awt.geom.RoundRectangle2D.Float(x + inset, y + inset, width - stroke,
                    height - stroke, UIScale.scale(12f), UIScale.scale(12f)));
            }
            finally { g.dispose(); }
        }
    }
}
