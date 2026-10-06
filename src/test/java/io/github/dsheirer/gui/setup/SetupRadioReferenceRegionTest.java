package io.github.dsheirer.gui.setup;

import io.github.dsheirer.preference.radioreference.RadioReferencePreference;
import io.github.dsheirer.service.radioreference.RadioReferenceDirectoryService.DirectoryOption;
import java.awt.Component;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.prefs.AbstractPreferences;
import java.util.prefs.Preferences;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JPasswordField;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SetupRadioReferenceRegionTest
{
    private static final DirectoryOption US = new DirectoryOption(1, "United States", "US");
    private static final DirectoryOption CA = new DirectoryOption(2, "Canada", "CA");
    private static final DirectoryOption OH = new DirectoryOption(10, "Ohio", "OH");
    private static final DirectoryOption ON = new DirectoryOption(20, "Ontario", "ON");

    @Test void countryIsExplicitAndStatesBelongToThatCountry() throws Exception
    {
        Harness harness = new Harness(-1, -1);
        harness.connect();
        harness.worker.runNext();
        onEdt(() -> {
            assertEquals(-1, selected(harness.country()));
            assertFalse(harness.state().isEnabled());
            harness.country().setSelectedItem(CA);
        });
        harness.worker.runNext();
        onEdt(() -> {
            assertEquals(2, harness.state().getItemCount());
            assertEquals(ON, harness.state().getItemAt(1));
            assertFalse(harness.status().getParent().isVisible(), "Empty feedback does not reserve page space");
            harness.state().setSelectedItem(ON);
            harness.region.save(harness.preferences);
            assertEquals(2, harness.preferences.getPreferredCountryId());
            assertEquals(20, harness.preferences.getPreferredStateId());
            assertEquals(-1, harness.preferences.getPreferredCountyId());
        });
        assertEquals(List.of(2), harness.requestedCountries);
    }

    @Test void changingCountryRejectsAnOldStateAndIgnoresALateReply() throws Exception
    {
        Harness harness = new Harness(1, 10);
        harness.connect();
        harness.worker.runNext();
        harness.worker.runNext();
        onEdt(() -> {
            harness.country().setSelectedItem(CA);
            harness.country().setSelectedItem(US);
            assertThrows(IllegalArgumentException.class, () -> harness.region.save(harness.preferences));
            assertEquals(10, harness.preferences.getPreferredStateId());
            assertFalse(harness.state().isEnabled());
        });
        harness.worker.runNext();
        onEdt(() -> assertFalse(harness.state().isEnabled()));
        harness.worker.runNext();
        onEdt(() -> {
            assertEquals(OH, harness.state().getItemAt(1));
            assertEquals(-1, selected(harness.state()));
        });
        assertEquals(List.of(1, 1), harness.requestedCountries,
            "The obsolete queued Canada lookup never reaches the provider");
    }

    @Test void failuresOfferRetryAndNeverExposeProviderDetails() throws Exception
    {
        Harness harness = new Harness(-1, -1);
        harness.failCountries = true;
        harness.connect();
        harness.worker.runNext();
        onEdt(() -> {
            assertTrue(harness.retry().isVisible());
            assertTrue(harness.status().getText().contains("Countries could not be loaded"));
            assertFalse(harness.status().getText().contains("private-provider-detail"));
            harness.failCountries = false;
            harness.retry().doClick();
        });
        harness.worker.runNext();
        onEdt(() -> {
            assertFalse(harness.retry().isVisible());
            assertTrue(harness.country().isEnabled());
        });
    }

    @Test void stateFailureRetriesTheSelectedCountry() throws Exception
    {
        Harness harness = new Harness(-1, -1);
        harness.connect();
        harness.worker.runNext();
        onEdt(() -> {
            harness.failStates = true;
            harness.country().setSelectedItem(CA);
        });
        harness.worker.runNext();
        onEdt(() -> {
            assertEquals(2, selected(harness.country()));
            assertTrue(harness.retry().isVisible());
            assertTrue(harness.status().getText().contains("States and provinces could not be loaded"));
            assertFalse(harness.status().getText().contains("private-provider-detail"));
            harness.failStates = false;
            harness.retry().doClick();
        });
        harness.worker.runNext();
        onEdt(() -> {
            harness.state().setSelectedItem(ON);
            harness.region.save(harness.preferences);
            assertEquals(2, harness.preferences.getPreferredCountryId());
            assertEquals(20, harness.preferences.getPreferredStateId());
        });
        assertEquals(List.of(2, 2), harness.requestedCountries);
    }

    @Test void savedLocationSurvivesFailureAndSkippingAnIncompleteEdit() throws Exception
    {
        Harness harness = new Harness(1, 10);
        harness.preferences.setPreferredCountyId(100);
        harness.failCountries = true;
        harness.connect();
        harness.worker.runNext();
        onEdt(() -> harness.region.save(harness.preferences));
        assertEquals(100, harness.preferences.getPreferredCountyId());
        onEdt(() -> {
            harness.failCountries = false;
            harness.retry().doClick();
        });
        harness.worker.runNext();
        harness.worker.runNext();
        onEdt(() -> {
            harness.failStates = true;
            harness.country().setSelectedItem(CA);
        });
        harness.worker.runNext();
        onEdt(() -> {
            assertTrue(harness.retry().isVisible());
            harness.later().doClick();
            harness.region.save(harness.preferences);
            assertTrue(harness.region.deferred());
        });
        assertEquals(1, harness.preferences.getPreferredCountryId());
        assertEquals(10, harness.preferences.getPreferredStateId());
        assertEquals(100, harness.preferences.getPreferredCountyId());
    }

    @Test void newPasswordAndValidLocationSaveBeforePasswordCleanupDisconnectsTheLookup() throws Exception
    {
        Harness harness = new Harness(-1, -1);
        harness.connect();
        harness.worker.runNext();
        onEdt(() -> harness.country().setSelectedItem(CA));
        harness.worker.runNext();
        onEdt(() -> {
            harness.state().setSelectedItem(ON);
            JTextField username = new JTextField("account");
            JPasswordField password = new JPasswordField("example-password");
            password.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
                public void insertUpdate(javax.swing.event.DocumentEvent event) { harness.region.disconnected(false); }
                public void removeUpdate(javax.swing.event.DocumentEvent event) { harness.region.disconnected(false); }
                public void changedUpdate(javax.swing.event.DocumentEvent event) { harness.region.disconnected(false); }
            });
            assertTrue(SetupWizard.saveRadioReferenceAccount(username, password, harness.region, harness.preferences));
            assertEquals("account", harness.preferences.getUserName());
            assertEquals("example-password", harness.preferences.getPassword());
            assertEquals(0, password.getPassword().length);
            assertEquals(2, harness.preferences.getPreferredCountryId());
            assertEquals(20, harness.preferences.getPreferredStateId());
            assertFalse(SetupWizard.saveRadioReferenceAccount(username, password, harness.region,
                harness.preferences), "A save can be retried after lookup cleanup without re-entering the location");
        });
    }

    @Test void incompleteLocationKeepsTheAccountDraftAndExplicitSkipCanSaveIt() throws Exception
    {
        Harness harness = new Harness(-1, -1);
        onEdt(() -> {
            JTextField username = new JTextField("account");
            JPasswordField password = new JPasswordField("example-password");
            assertThrows(IllegalArgumentException.class, () ->
                SetupWizard.saveRadioReferenceAccount(username, password, harness.region, harness.preferences));
            assertNull(harness.preferences.getUserName());
            assertEquals("example-password", new String(password.getPassword()));
            harness.region.disconnected(true);
            harness.later().doClick();
            assertTrue(SetupWizard.saveRadioReferenceAccount(username, password, harness.region, harness.preferences));
            assertEquals("account", harness.preferences.getUserName());
            assertEquals(-1, harness.preferences.getPreferredCountryId());
            assertEquals(-1, harness.preferences.getPreferredStateId());
        });
    }

    @Test void navigatingAwayPreventsLateResponsesFromStartingAnotherLookup() throws Exception
    {
        Harness harness = new Harness(1, 10);
        harness.connect();
        onEdt(harness.region::close);
        harness.worker.runNext();
        assertTrue(harness.worker.pending.isEmpty());
        onEdt(() -> assertFalse(harness.country().isEnabled()));
    }

    @Test void busyWizardUpdatesCannotEnableUnloadedControls() throws Exception
    {
        Harness harness = new Harness(-1, -1);
        harness.connect();
        onEdt(() -> {
            harness.region.setSetupEnabled(false);
            assertFalse(harness.later().isEnabled());
            harness.region.setSetupEnabled(true);
            assertTrue(harness.later().isEnabled());
            assertFalse(harness.country().isEnabled());
            assertFalse(harness.state().isEnabled());
        });
    }

    private static int selected(JComboBox<?> selector)
    {
        return ((DirectoryOption)selector.getSelectedItem()).id();
    }

    private static void onEdt(Runnable action) throws Exception { SwingUtilities.invokeAndWait(action); }

    private static final class Harness
    {
        final QueueExecutor worker = new QueueExecutor();
        final RadioReferencePreference preferences;
        final SetupRadioReferenceRegion region;
        final java.util.ArrayList<Integer> requestedCountries = new java.util.ArrayList<>();
        boolean failCountries;
        boolean failStates;

        Harness(int country, int state) throws Exception
        {
            var constructor = RadioReferencePreference.class.getDeclaredConstructor(
                io.github.dsheirer.sample.Listener.class, Preferences.class);
            constructor.setAccessible(true);
            preferences = constructor.newInstance(null, new MemoryPreferences(null, ""));
            preferences.setPreferredCountryId(country);
            preferences.setPreferredStateId(state);
            SetupRadioReferenceRegion[] value = new SetupRadioReferenceRegion[1];
            onEdt(() -> value[0] = new SetupRadioReferenceRegion(country, state, worker));
            region = value[0];
        }

        void connect() throws Exception
        {
            onEdt(() -> region.connected(new SetupRadioReferenceRegion.Directory() {
                public List<DirectoryOption> countries()
                {
                    assertFalse(SwingUtilities.isEventDispatchThread());
                    if(failCountries) throw new IllegalStateException("private-provider-detail");
                    return List.of(CA, US);
                }
                public List<DirectoryOption> states(int countryId)
                {
                    assertFalse(SwingUtilities.isEventDispatchThread());
                    requestedCountries.add(countryId);
                    if(failStates) throw new IllegalStateException("private-provider-detail");
                    return countryId == 2 ? List.of(ON) : List.of(OH);
                }
            }));
        }

        @SuppressWarnings("unchecked") JComboBox<DirectoryOption> country() { return (JComboBox<DirectoryOption>)component("Country"); }
        @SuppressWarnings("unchecked") JComboBox<DirectoryOption> state() { return (JComboBox<DirectoryOption>)component("State or province"); }
        JButton retry() { return (JButton)descendants(region).stream().filter(c -> c instanceof JButton button &&
            "Retry location lookup".equals(button.getText())).findFirst().orElseThrow(); }
        JCheckBox later() { return (JCheckBox)java.util.Arrays.stream(region.getComponents()).filter(c -> c instanceof JCheckBox).findFirst().orElseThrow(); }
        JTextArea status() { return (JTextArea)descendants(region).stream().filter(c -> c instanceof JTextArea).reduce((a, b) -> b).orElseThrow(); }
        Component component(String name)
        {
            return java.util.Arrays.stream(region.getComponents()).filter(c -> c instanceof JComboBox &&
                name.equals(c.getAccessibleContext().getAccessibleName())).findFirst().orElseThrow();
        }
    }

    private static List<Component> descendants(java.awt.Container parent)
    {
        java.util.ArrayList<Component> result = new java.util.ArrayList<>();
        for(Component component: parent.getComponents())
        {
            result.add(component);
            if(component instanceof java.awt.Container child) result.addAll(descendants(child));
        }
        return result;
    }

    private static final class QueueExecutor implements Executor
    {
        final ArrayDeque<Runnable> pending = new ArrayDeque<>();
        public void execute(Runnable task) { pending.add(task); }
        void runNext() throws Exception { pending.remove().run(); onEdt(() -> {}); }
    }

    private static final class MemoryPreferences extends AbstractPreferences
    {
        private final Map<String, String> values = new HashMap<>();
        MemoryPreferences(AbstractPreferences parent, String name) { super(parent, name); }
        protected void putSpi(String key, String value) { values.put(key, value); }
        protected String getSpi(String key) { return values.get(key); }
        protected void removeSpi(String key) { values.remove(key); }
        protected void removeNodeSpi() { values.clear(); }
        protected String[] keysSpi() { return values.keySet().toArray(String[]::new); }
        protected String[] childrenNamesSpi() { return new String[0]; }
        protected AbstractPreferences childSpi(String name) { return new MemoryPreferences(this, name); }
        protected void syncSpi() {}
        protected void flushSpi() {}
    }
}
