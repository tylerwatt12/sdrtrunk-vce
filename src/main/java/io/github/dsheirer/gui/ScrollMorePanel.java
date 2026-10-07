package io.github.dsheirer.gui;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.util.UIScale;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.Rectangle;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLayer;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.plaf.LayerUI;
import jiconfont.icons.font_awesome.FontAwesome;
import jiconfont.swing.IconFontSwing;

/** A viewport-only fade and a quiet scroll action above a separately owned dialog footer. */
public final class ScrollMorePanel extends JPanel
{
    private final JScrollPane mScrollPane;
    private final JButton mMore = new JButton("More below");
    private final JLayer<JScrollPane> mLayer;
    private boolean mHasMore;

    public ScrollMorePanel(JScrollPane scrollPane)
    {
        super(new BorderLayout());
        mScrollPane = scrollPane;
        mScrollPane.setFocusable(true);
        mLayer = new JLayer<>(scrollPane, new FadeUI());
        add(mLayer, BorderLayout.CENTER);

        mMore.putClientProperty(FlatClientProperties.STYLE,
            "background: null; borderWidth: 0; focusWidth: 1; margin: 3,8,3,8");
        mMore.setContentAreaFilled(false);
        mMore.getAccessibleContext().setAccessibleName("Scroll down for more");
        mMore.getAccessibleContext().setAccessibleDescription("Show the next part of this page.");
        mMore.addPropertyChangeListener("UI", event -> updateAppearance());
        mMore.addActionListener(event -> {
            var bar = mScrollPane.getVerticalScrollBar();
            bar.setValue(bar.getValue() + Math.max(1, bar.getVisibleAmount() * 4 / 5));
        });
        JPanel cue = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        //Reserve this space at the bottom even when the cue is hidden, keeping navigation stationary.
        cue.setPreferredSize(new Dimension(0, UIScale.scale(34)));
        cue.add(mMore);
        add(cue, BorderLayout.SOUTH);

        mScrollPane.getVerticalScrollBar().getModel().addChangeListener(event -> updateMore());
        mScrollPane.getViewport().addChangeListener(event -> updateMore());
        updateAppearance();
        updateMore();
    }

    private void updateAppearance()
    {
        Color foreground = UIManager.getColor("Label.foreground");
        mMore.setForeground(foreground);
        Font font = UIManager.getFont("Label.font");
        if(font != null) mMore.setFont(font.deriveFont(Math.max(font.getSize2D(), UIScale.scale(12f))));
        IconFontSwing.register(FontAwesome.getIconFont());
        mMore.setIcon(IconFontSwing.buildIcon(FontAwesome.CHEVRON_DOWN, UIScale.scale(12), foreground));
        mLayer.repaint();
    }

    private void updateMore()
    {
        var model = mScrollPane.getVerticalScrollBar().getModel();
        mHasMore = mScrollPane.getViewport().getExtentSize().height > 0 &&
            model.getMaximum() - model.getExtent() - model.getValue() > 2;
        if(!mHasMore && mMore.isFocusOwner()) mScrollPane.requestFocusInWindow();
        mMore.setVisible(mHasMore);
        mLayer.repaint();
    }

    private final class FadeUI extends LayerUI<JScrollPane>
    {
        @Override
        public void paint(Graphics graphics, JComponent component)
        {
            super.paint(graphics, component);
            if(!mHasMore) return;

            var viewport = mScrollPane.getViewport();
            Rectangle area = SwingUtilities.convertRectangle(viewport.getParent(), viewport.getBounds(), component);
            int height = Math.min(UIScale.scale(28), area.height);
            if(area.width <= 0 || height <= 0) return;
            Color background = viewport.getBackground();
            int top = area.y + area.height - height;
            Graphics2D g = (Graphics2D)graphics.create();
            try
            {
                g.setPaint(new LinearGradientPaint(0, top, 0, top + height, new float[]{0, 1},
                    new Color[]{new Color(background.getRed(), background.getGreen(), background.getBlue(), 0),
                        new Color(background.getRed(), background.getGreen(), background.getBlue())}));
                g.fillRect(area.x, top, area.width, height);
            }
            finally { g.dispose(); }
        }
    }
}
