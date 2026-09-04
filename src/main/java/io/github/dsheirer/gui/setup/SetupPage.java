package io.github.dsheirer.gui.setup;

import java.awt.Dimension;
import java.awt.Rectangle;
import javax.swing.JPanel;
import javax.swing.Scrollable;

/** Reflow page content at the viewport width; small screens scroll vertically, never clip controls horizontally. */
final class SetupPage extends JPanel implements Scrollable
{
    public Dimension getPreferredScrollableViewportSize() { return new Dimension(580,420); }
    public int getScrollableUnitIncrement(Rectangle visible,int orientation,int direction) { return 18; }
    public int getScrollableBlockIncrement(Rectangle visible,int orientation,int direction) { return Math.max(18,visible.height-30); }
    public boolean getScrollableTracksViewportWidth() { return true; }
    public boolean getScrollableTracksViewportHeight() { return false; }
}
