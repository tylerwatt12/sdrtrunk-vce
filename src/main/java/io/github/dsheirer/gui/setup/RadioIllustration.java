package io.github.dsheirer.gui.setup;

import java.awt.*;
import javax.swing.JPanel;

/** Bundled, resolution-independent dusk-radio artwork; no downloads or additional rendering framework. */
final class RadioIllustration extends JPanel
{
    RadioIllustration() { setPreferredSize(new Dimension(235, 215)); getAccessibleContext().setAccessibleName("Radio tower at dusk"); }
    @Override protected void paintComponent(Graphics graphics)
    {
        super.paintComponent(graphics);
        Graphics2D g=(Graphics2D)graphics.create();
        try
        {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            double scale=getWidth()/235.0; g.scale(scale,getHeight()/215.0);
            g.setPaint(new GradientPaint(0,0,new Color(23,30,68),235,215,new Color(173,89,96))); g.fillRect(0,0,235,215);
            g.setColor(new Color(255,202,153)); g.fillOval(166,48,35,35);
            g.setColor(new Color(53,54,88)); g.fillPolygon(new int[]{0,50,104,166,235,235,0},new int[]{149,97,151,113,154,215,215},7);
            g.setColor(new Color(24,37,63)); g.fillPolygon(new int[]{0,73,139,205,235,235,0},new int[]{170,139,171,144,159,215,215},7);
            g.setStroke(new BasicStroke(3)); g.setColor(new Color(223,222,226));
            g.drawLine(90,157,112,62); g.drawLine(134,157,112,62); g.drawLine(95,134,129,134); g.drawLine(100,113,124,113);
            g.drawLine(95,134,124,113); g.drawLine(100,113,129,134); g.drawLine(112,62,112,47);
            g.setColor(new Color(246,157,123)); g.drawArc(85,31,54,48,135,90); g.drawArc(95,40,34,30,135,90);
            g.drawArc(85,31,54,48,-45,90); g.drawArc(95,40,34,30,-45,90);
            g.setColor(Color.WHITE); g.setFont(getFont().deriveFont(Font.BOLD,19f)); g.drawString("sdrtrunk-vce",19,190);
        }
        finally { g.dispose(); }
    }
}
