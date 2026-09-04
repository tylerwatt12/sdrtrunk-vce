package io.github.dsheirer.gui.setup;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;
import com.formdev.flatlaf.ui.FlatStylingSupport;
import java.awt.Component;
import java.awt.event.MouseEvent;
import java.util.Locale;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WizardComponentsTest
{
    @Test void sourceDetailsOnlyAppearForTheirSelectedOption() throws Exception
    {
        SwingUtilities.invokeAndWait(() -> {
            FlatLightLaf.setup();
            ButtonGroup group = new ButtonGroup();
            WizardChoiceCard fresh = new WizardChoiceCard(group, "Start fresh", "For your first installation.", true);
            WizardChoiceCard previous = new WizardChoiceCard(group, "Bring my settings", "Choose a previous installation.", false);
            JTextField source = new JTextField("old folder");
            JPanel details = new JPanel();
            details.add(source);
            previous.setDetails(details);
            assertFalse(details.isVisible());
            previous.radio().setSelected(true);
            assertTrue(details.isVisible());
            assertFalse(fresh.isSelected());
            fresh.radio().setSelected(true);
            assertFalse(details.isVisible());
            assertEquals("old folder", source.getText());
            assertEquals("Bring my settings", previous.radio().getAccessibleContext().getAccessibleName());
            assertFalse(previous.radio().getInputMap(JComponent.WHEN_FOCUSED).allKeys().length == 0,
                "The selector retains native keyboard actions");
        });
    }

    @Test void fullCardSelectionHonorsDisabledState() throws Exception
    {
        SwingUtilities.invokeAndWait(() -> {
            FlatLightLaf.setup();
            WizardChoiceCard card = new WizardChoiceCard(new ButtonGroup(), "Choose", "Explanation", false);
            card.radio().setEnabled(false);
            click(card);
            assertFalse(card.isSelected());
            card.radio().setEnabled(true);
            click(card);
            assertTrue(card.isSelected());
        });
    }

    @Test void narrowCardsReflowWithoutStretchingIntoUnusedPageSpace() throws Exception
    {
        SwingUtilities.invokeAndWait(() -> {
            FlatLightLaf.setup();
            JPanel page = new SetupPage();
            page.setLayout(new BoxLayout(page, BoxLayout.Y_AXIS));
            WizardChoiceCard card = new WizardChoiceCard(new ButtonGroup(),
                "Bring settings from an earlier installation", "Copy your channels and aliases, digital audio support, " +
                "and saved preferences. Your previous installation stays unchanged and your recordings are not copied.", true);
            page.add(card);
            page.setSize(800, 700);
            int wideHeight = card.getPreferredSize().height;
            page.setSize(320, 700);
            int narrowHeight = card.getPreferredSize().height;
            assertTrue(narrowHeight > wideHeight, "Copy grows vertically as the window narrows");
            assertEquals(narrowHeight, card.getMaximumSize().height);
            assertEquals(320, card.getPreferredSize().width);
            page.doLayout();
            card.doLayout();
            assertTrue(card.getHeight() < page.getHeight(), "A single option does not grow to fill the page");
            assertTrue(card.getWidth() <= page.getWidth());
        });
    }

    @Test void integerIdentifiersNeverHaveGroupingSeparators() throws Exception
    {
        Locale original = Locale.getDefault(Locale.Category.FORMAT);
        try
        {
            for(Locale locale : new Locale[]{Locale.US, Locale.GERMANY, Locale.FRANCE})
            {
                Locale.setDefault(Locale.Category.FORMAT, locale);
                SwingUtilities.invokeAndWait(() -> {
                    FlatLightLaf.setup();
                    JSpinner spinner = WizardStyles.integerSpinner(8090, 1024, 65535);
                    JSpinner.NumberEditor editor = (JSpinner.NumberEditor)spinner.getEditor();
                    assertEquals("8090", editor.getTextField().getText());
                    assertFalse(editor.getFormat().isGroupingUsed());
                    assertEquals(spinner.getPreferredSize(), spinner.getMaximumSize());
                    assertTrue(spinner.getPreferredSize().width < 300);
                });
            }
        }
        finally { Locale.setDefault(Locale.Category.FORMAT, original); }
    }

    @Test void buttonStylesAreSupportedAndKeepPrimaryContrastInBothThemes() throws Exception
    {
        SwingUtilities.invokeAndWait(() -> {
            for(boolean dark : new boolean[]{false, true})
            {
                if(dark) FlatDarkLaf.setup(); else FlatLightLaf.setup();
                JButton primary = WizardStyles.primary(new JButton("Continue"));
                JButton secondary = WizardStyles.secondary(new JButton("Back"));
                JButton quiet = WizardStyles.quiet(new JButton("Skip for now"));
                for(JButton button : new JButton[]{primary, secondary, quiet})
                {
                    var ui = (FlatStylingSupport.StyleableUI)button.getUI();
                    var properties = ui.getStyleableInfos(button);
                    @SuppressWarnings("unchecked")
                    var applied = (java.util.Map<String,Object>)button.getClientProperty("FlatLaf.style");
                    for(String property : applied.keySet())
                    {
                        //Standard Swing bean properties are also supported by FlatLaf's styling adapter.
                        assertTrue(properties.containsKey(property) || property.equals("margin") ||
                            property.equals("background") || property.equals("foreground"), property);
                    }
                    assertTrue(button.getPreferredSize().height >= 38);
                }
                assertNotEquals(primary.getForeground(), primary.getBackground());
                assertTrue(WizardStyles.contrast(primary.getForeground(), primary.getBackground()) >= 4.5);
                assertTrue(WizardStyles.contrast(WizardStyles.muted(), WizardStyles.surface()) >= 4.5);
                var text = WizardStyles.prose("Clear, readable setup instructions.");
                assertTrue(text.getFont().getSize2D() >= 14);
                assertFalse(text.isOpaque());
            }
        });
    }

    private static void click(Component component)
    {
        component.dispatchEvent(new MouseEvent(component, MouseEvent.MOUSE_CLICKED,
            System.currentTimeMillis(), 0, 5, 5, 1, false, MouseEvent.BUTTON1));
    }
}
