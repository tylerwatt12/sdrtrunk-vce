package io.github.dsheirer.gui.setup;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.util.UIScale;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Insets;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.swing.JButton;
import javax.swing.JFormattedTextField;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import javax.swing.plaf.FontUIResource;

/** Local, theme-aware presentation choices for setup; never changes application-wide defaults. */
final class WizardStyles
{
    private WizardStyles() {}

    static Color surface() { return color("TextField.background", new Color(0xffffff)); }
    static Color background() { return color("Panel.background", new Color(0xf5f6f8)); }
    static Color border() { return color("Component.borderColor", new Color(0xc8ccd2)); }
    static Color foreground() { return color("Label.foreground", new Color(0x222a35)); }
    static Color muted()
    {
        Color surface = surface();
        for(float weight = 0.75f; weight < 1; weight += 0.05f)
        {
            Color candidate = blend(foreground(), surface, weight);
            if(contrast(candidate, surface) >= 4.5) return candidate;
        }
        return foreground();
    }
    static Color accent()
    {
        return color("Component.accentColor", color("Button.default.background",
            color("List.selectionBackground", new Color(0x2675bf))));
    }

    static Font bodyFont()
    {
        Font base = UIManager.getFont("Label.font");
        if(base == null) base = new Font(Font.SANS_SERIF, Font.PLAIN, 14);
        return new FontUIResource(base.deriveFont(Math.max(base.getSize2D(), UIScale.scale(14f))));
    }

    static JTextArea prose(String value)
    {
        return prose(value, Font.PLAIN, 14f);
    }

    static JTextArea prose(String value, int style, float minimumSize)
    {
        JTextArea area = new SetupText(value);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setOpaque(false);
        Runnable font = () -> area.setFont(new FontUIResource(bodyFont().deriveFont(style,
            Math.max(bodyFont().getSize2D(), UIScale.scale(minimumSize)))));
        area.addPropertyChangeListener("UI", event -> font.run());
        font.run();
        area.setForeground(foreground());
        area.setBorder(null);
        area.setMargin(new Insets(0, 0, 0, 0));
        area.setAlignmentX(Component.LEFT_ALIGNMENT);
        return area;
    }

    static void bodyFont(javax.swing.JComponent component, int style)
    {
        Runnable font = () -> component.setFont(new FontUIResource(bodyFont().deriveFont(style)));
        component.addPropertyChangeListener("UI", event -> font.run());
        font.run();
    }

    static JButton primary(JButton button)
    {
        refreshWithTheme(button,"primary");
        Map<String,Object> style = buttonStyle();
        Color accent = accent();
        Color darkText = new Color(0x14202b);
        Color text = contrast(Color.WHITE, accent) >= contrast(darkText, accent) ? Color.WHITE : darkText;
        Color hover = blend(accent, text, 0.88f);
        Color pressed = blend(accent, text, 0.78f);
        style.put("background", accent);
        style.put("foreground", text);
        style.put("focusedBackground", accent);
        style.put("hoverBackground", hover);
        style.put("pressedBackground", pressed);
        style.put("borderColor", accent);
        style.put("focusedBorderColor", accent);
        style.put("hoverBorderColor", hover);
        style.put("default.background", accent);
        style.put("default.foreground", text);
        style.put("default.focusedBackground", accent);
        style.put("default.hoverBackground", hover);
        style.put("default.pressedBackground", pressed);
        style.put("default.borderColor", accent);
        style.put("default.hoverBorderColor", hover);
        style.put("default.boldText", false);
        button.putClientProperty(FlatClientProperties.STYLE, style);
        button.setFont(new FontUIResource(bodyFont().deriveFont(Font.BOLD)));
        return button;
    }

    static JButton secondary(JButton button)
    {
        refreshWithTheme(button,"secondary");
        Map<String,Object> style = buttonStyle();
        style.put("background", surface());
        style.put("foreground", foreground());
        style.put("borderColor", border());
        button.putClientProperty(FlatClientProperties.STYLE, style);
        button.setFont(bodyFont());
        return button;
    }

    static JButton quiet(JButton button)
    {
        refreshWithTheme(button,"quiet");
        Map<String,Object> style = buttonStyle();
        style.put("buttonType", "borderless");
        style.put("foreground", muted());
        style.put("minimumWidth", 0);
        button.putClientProperty(FlatClientProperties.STYLE, style);
        button.setFont(bodyFont());
        return button;
    }

    private static void refreshWithTheme(JButton button,String kind)
    {
        if(button.getClientProperty("wizard.buttonStyle")==null)
            button.addPropertyChangeListener("UI",event -> {
                Object current=button.getClientProperty("wizard.buttonStyle");
                if("primary".equals(current)) primary(button);
                else if("quiet".equals(current)) quiet(button);
                else if("secondary".equals(current)) secondary(button);
            });
        button.putClientProperty("wizard.buttonStyle",kind);
    }

    private static Map<String,Object> buttonStyle()
    {
        Map<String,Object> style = new LinkedHashMap<>();
        style.put("arc", 10);
        style.put("margin", new Insets(12, 16, 12, 16));
        style.put("minimumHeight", 38);
        style.put("minimumWidth", 96);
        return style;
    }

    /** Integer identifiers such as ports must not acquire locale-specific grouping separators. */
    static JSpinner integerSpinner(int value, int min, int max)
    {
        JSpinner spinner = new JSpinner(new SpinnerNumberModel(value, min, max, 1));
        JSpinner.NumberEditor editor = new JSpinner.NumberEditor(spinner, "#0");
        editor.getFormat().setGroupingUsed(false);
        JFormattedTextField field = editor.getTextField();
        field.setColumns(7);
        field.setHorizontalAlignment(SwingConstants.LEFT);
        field.addPropertyChangeListener("UI", event -> field.setHorizontalAlignment(SwingConstants.LEFT));
        bodyFont(field, Font.PLAIN);
        spinner.setEditor(editor);
        spinner.putClientProperty(FlatClientProperties.STYLE, "minimumWidth: 132; padding: 6,8,6,8");
        Dimension size = new Dimension(UIScale.scale(140), Math.max(UIScale.scale(38), spinner.getPreferredSize().height));
        spinner.setPreferredSize(size);
        spinner.setMaximumSize(size);
        spinner.setAlignmentX(Component.LEFT_ALIGNMENT);
        return spinner;
    }

    static Color blend(Color first, Color second, float firstWeight)
    {
        float secondWeight = 1.0f - firstWeight;
        return new Color(Math.round(first.getRed() * firstWeight + second.getRed() * secondWeight),
            Math.round(first.getGreen() * firstWeight + second.getGreen() * secondWeight),
            Math.round(first.getBlue() * firstWeight + second.getBlue() * secondWeight));
    }

    private static Color color(String key, Color fallback)
    {
        Color value = UIManager.getColor(key);
        return value == null ? fallback : value;
    }

    static double contrast(Color first, Color second)
    {
        double one = luminance(first), two = luminance(second);
        return (Math.max(one, two) + 0.05) / (Math.min(one, two) + 0.05);
    }

    private static double luminance(Color color)
    {
        return 0.2126 * linear(color.getRed()) + 0.7152 * linear(color.getGreen()) + 0.0722 * linear(color.getBlue());
    }

    private static double linear(int value)
    {
        double channel = value / 255.0;
        return channel <= 0.04045 ? channel / 12.92 : Math.pow((channel + 0.055) / 1.055, 2.4);
    }
}
