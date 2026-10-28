package io.github.dsheirer.gui.setup;

import java.awt.Dimension;
import java.awt.Insets;
import javax.swing.Icon;
import javax.swing.JCheckBox;
import javax.swing.UIManager;
import javax.swing.plaf.basic.BasicHTML;
import javax.swing.text.View;

/** A native checkbox whose label wraps only when the available page width requires it. */
final class SetupCheckBox extends JCheckBox
{
    SetupCheckBox(String caption, boolean selected)
    {
        super("<html>" + caption.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") + "</html>", selected);
        getAccessibleContext().setAccessibleName(caption);
    }

    @Override public Dimension getPreferredSize()
    {
        if(getParent() == null || getParent().getWidth() <= 0 ||
            !(getClientProperty(BasicHTML.propertyKey) instanceof View view))
            return super.getPreferredSize();
        Insets parent = getParent().getInsets(), own = getInsets();
        int width = Math.max(40, getParent().getWidth() - parent.left - parent.right);
        Icon icon = getIcon() == null ? UIManager.getIcon("CheckBox.icon") : getIcon();
        int iconWidth = icon == null ? 0 : icon.getIconWidth();
        int iconHeight = icon == null ? 0 : icon.getIconHeight();
        view.setSize(Math.max(1, width - own.left - own.right - iconWidth - getIconTextGap()), Integer.MAX_VALUE);
        return new Dimension(width, Math.max(iconHeight, (int)Math.ceil(view.getPreferredSpan(View.Y_AXIS))) +
            own.top + own.bottom);
    }

    @Override public Dimension getMaximumSize()
    {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }
}
