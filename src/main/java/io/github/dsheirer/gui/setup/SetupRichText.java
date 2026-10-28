package io.github.dsheirer.gui.setup;

import java.awt.Dimension;
import java.awt.Insets;
import javax.swing.JTextPane;
import javax.swing.text.View;

/** Selectable instructions with emphasis that reflow at the available page width. */
final class SetupRichText extends JTextPane
{
    @Override public Dimension getPreferredSize()
    {
        if(getParent() == null || getParent().getWidth() <= 0 || getUI() == null)
            return super.getPreferredSize();
        Insets parent = getParent().getInsets(), own = getInsets();
        int width = Math.max(40, getParent().getWidth() - parent.left - parent.right);
        View view = getUI().getRootView(this);
        view.setSize(Math.max(1, width - own.left - own.right), Integer.MAX_VALUE);
        return new Dimension(width, (int)Math.ceil(view.getPreferredSpan(View.Y_AXIS)) + own.top + own.bottom);
    }

    @Override public Dimension getMaximumSize()
    {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }
}
