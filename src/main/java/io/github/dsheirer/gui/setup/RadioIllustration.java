package io.github.dsheirer.gui.setup;

import java.awt.*;
import java.net.URL;
import javax.swing.ImageIcon;
import javax.swing.JPanel;

/** Bundled setup artwork that matches the web sign-in scene. */
final class RadioIllustration extends JPanel
{
    private static final Image WIREFRAME = loadImage("/images/app/vce-wizard-wireframe.png");
    private static final Image VCE_WORDMARK = loadImage("/images/app/vce-wordmark.png");

    RadioIllustration() { setPreferredSize(new Dimension(235, 215)); getAccessibleContext().setAccessibleName("VCE geometric wireframe artwork"); }
    @Override protected void paintComponent(Graphics graphics)
    {
        super.paintComponent(graphics);
        Graphics2D g=(Graphics2D)graphics.create();
        try
        {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            double scale=getWidth()/235.0; g.scale(scale,getHeight()/215.0);
            if(WIREFRAME != null) g.drawImage(WIREFRAME,0,0,235,215,this);
            else { g.setColor(new Color(7,17,29)); g.fillRect(0,0,235,215); }
            if(VCE_WORDMARK != null) g.drawImage(VCE_WORDMARK,19,174,73,22,this);
        }
        finally { g.dispose(); }
    }

    private static Image loadImage(String path)
    {
        URL resource = RadioIllustration.class.getResource(path);
        return resource != null ? new ImageIcon(resource).getImage() : null;
    }
}
