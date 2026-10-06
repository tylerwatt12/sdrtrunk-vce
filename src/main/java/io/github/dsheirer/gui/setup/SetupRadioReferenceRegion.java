package io.github.dsheirer.gui.setup;

import io.github.dsheirer.preference.radioreference.RadioReferencePreference;
import io.github.dsheirer.service.radioreference.RadioReferenceDirectoryService.DirectoryOption;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.util.List;
import java.util.concurrent.Executor;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

/** Optional setup location, using the same country/state identifiers as the RadioReference browser. */
final class SetupRadioReferenceRegion extends JPanel implements AutoCloseable
{
    interface Directory
    {
        List<DirectoryOption> countries() throws Exception;
        List<DirectoryOption> states(int countryId) throws Exception;
    }

    private final Executor worker;
    private int savedCountry;
    private int savedState;
    private final JComboBox<DirectoryOption> country = selector("Country");
    private final JComboBox<DirectoryOption> state = selector("State or province");
    private final JTextArea status = WizardStyles.prose("");
    private final JButton retry = WizardStyles.secondary(new JButton("Retry location lookup"));
    private final JCheckBox later = new JCheckBox("Choose a location later");
    private final JPanel feedback = new JPanel();
    private final Component retryGap = Box.createVerticalStrut(12);
    private final Component feedbackGap = Box.createVerticalStrut(12);
    private Directory directory;
    private boolean changing;
    private boolean countriesReady;
    private boolean statesReady;
    private boolean loading;
    private boolean enabled = true;
    private boolean edited;
    private volatile boolean closed;
    private volatile long generation;
    private Runnable retryAction = () -> {};

    SetupRadioReferenceRegion(int countryId, int stateId, Executor worker)
    {
        this.worker = worker;
        savedCountry = countryId;
        savedState = stateId;
        setOpaque(false);
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        add(WizardStyles.prose("Choose your country and state or province to help match nearby radio systems."));
        add(Box.createVerticalStrut(16));
        labelled("Country", country);
        labelled("State or province", state);
        feedback.setOpaque(false);
        feedback.setLayout(new BoxLayout(feedback, BoxLayout.Y_AXIS));
        feedback.setAlignmentX(Component.LEFT_ALIGNMENT);
        feedback.add(status);
        feedback.add(retryGap);
        retry.setAlignmentX(Component.LEFT_ALIGNMENT);
        retry.addActionListener(event -> retryAction.run());
        feedback.add(retry);
        add(feedback);
        add(feedbackGap);
        later.setOpaque(false);
        later.setAlignmentX(Component.LEFT_ALIGNMENT);
        WizardStyles.bodyFont(later, Font.PLAIN);
        later.addActionListener(event -> refreshControls());
        add(later);
        resetOptions(country, countryId > 0 ? "Saved country" : "Choose a country", countryId);
        resetOptions(state, stateId > 0 ? "Saved state or province" : "Choose a state or province", stateId);
        disconnected(false);
        country.addActionListener(event -> {
            if(changing || !countriesReady) return;
            edited = true;
            loadStates(selected(country), -1);
        });
        state.addActionListener(event -> { if(!changing && statesReady) edited = true; });
    }

    private void labelled(String name, JComboBox<DirectoryOption> field)
    {
        JLabel label = new JLabel(name);
        label.setLabelFor(field);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        WizardStyles.bodyFont(label, Font.BOLD);
        add(label);
        add(Box.createVerticalStrut(8));
        add(field);
        add(Box.createVerticalStrut(16));
    }

    private static JComboBox<DirectoryOption> selector(String name)
    {
        JComboBox<DirectoryOption> selector = new JComboBox<>();
        selector.getAccessibleContext().setAccessibleName(name);
        selector.setAlignmentX(Component.LEFT_ALIGNMENT);
        selector.setMaximumSize(new Dimension(560, 40));
        WizardStyles.bodyFont(selector, Font.PLAIN);
        selector.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                                     boolean selected, boolean focus)
            {
                return super.getListCellRendererComponent(list,
                    value instanceof DirectoryOption option ? option.name() : value, index, selected, focus);
            }
        });
        return selector;
    }

    void connected(Directory source)
    {
        directory = source;
        loadCountries();
    }

    void disconnected(boolean premiumUnavailable)
    {
        ++generation;
        directory = null;
        loading = false;
        countriesReady = false;
        statesReady = false;
        retry.setVisible(false);
        status.setText(premiumUnavailable ? "Premium access is needed to choose a location. You can choose it later." :
            "Test your connection to load countries and states or provinces.");
        refreshControls();
    }

    private void loadCountries()
    {
        if(closed || directory == null) return;
        countriesReady = false;
        statesReady = false;
        load("Loading countries…", directory::countries, options -> {
            countriesReady = true;
            setOptions(country, "Choose a country", options, savedCountry);
            int selected = selected(country);
            if(selected > 0) loadStates(selected, savedState);
            else
            {
                resetOptions(state, "Choose a state or province", -1);
                status.setText(options.isEmpty() ? "No countries were returned. Retry, or choose a location later." : "");
                retryAction = this::loadCountries;
                retry.setVisible(options.isEmpty());
                refreshControls();
            }
        }, "Countries could not be loaded. Retry, or choose a location later.", this::loadCountries);
    }

    private void loadStates(int countryId, int preferredState)
    {
        statesReady = false;
        resetOptions(state, "Choose a state or province", -1);
        if(countryId <= 0 || directory == null)
        {
            ++generation;
            loading = false;
            retry.setVisible(false);
            status.setText("");
            refreshControls();
            return;
        }
        Directory source = directory;
        load("Loading states and provinces…", () -> source.states(countryId), options -> {
            statesReady = true;
            setOptions(state, "Choose a state or province", options, preferredState);
            status.setText(options.isEmpty() ? "No states or provinces were returned. Retry, or choose a location later." : "");
            retryAction = () -> loadStates(countryId, preferredState);
            retry.setVisible(options.isEmpty());
            refreshControls();
        }, "States and provinces could not be loaded. Retry, or choose a location later.",
            () -> loadStates(countryId, preferredState));
    }

    private void load(String message, java.util.concurrent.Callable<List<DirectoryOption>> work,
                      java.util.function.Consumer<List<DirectoryOption>> success, String failure, Runnable again)
    {
        long attempt = ++generation;
        loading = true;
        status.setText(message);
        retry.setVisible(false);
        refreshControls();
        worker.execute(() -> {
            if(closed || attempt != generation) return;
            List<DirectoryOption> value = null;
            Exception problem = null;
            try { value = List.copyOf(work.call()); } catch(Exception exception) { problem = exception; }
            List<DirectoryOption> result = value;
            Exception error = problem;
            SwingUtilities.invokeLater(() -> {
                if(closed || attempt != generation) return;
                loading = false;
                if(error == null) success.accept(result);
                else
                {
                    //Provider errors can contain credentials or URLs; only a value-free message is displayed.
                    status.setText(failure);
                    retryAction = again;
                    retry.setVisible(true);
                    refreshControls();
                }
                revalidate();
                repaint();
            });
        });
    }

    private void setOptions(JComboBox<DirectoryOption> control, String prompt, List<DirectoryOption> options, int preferred)
    {
        changing = true;
        DefaultComboBoxModel<DirectoryOption> model = new DefaultComboBoxModel<>();
        model.addElement(new DirectoryOption(-1, prompt, ""));
        options.forEach(model::addElement);
        control.setModel(model);
        for(DirectoryOption option: options) if(option.id() == preferred) control.setSelectedItem(option);
        changing = false;
    }

    private void resetOptions(JComboBox<DirectoryOption> control, String label, int id)
    {
        changing = true;
        control.setModel(new DefaultComboBoxModel<>(new DirectoryOption[]{new DirectoryOption(id, label, "")}));
        changing = false;
    }

    private static int selected(JComboBox<DirectoryOption> control)
    {
        return control.getSelectedItem() instanceof DirectoryOption option ? option.id() : -1;
    }

    void save(RadioReferencePreference preferences)
    {
        if(later.isSelected()) return;
        if(!edited && savedCountry > 0 && savedState > 0) return;
        if(!countriesReady || !statesReady || loading || selected(country) <= 0 || selected(state) <= 0)
            throw new IllegalArgumentException("Choose a country and state or province, or choose a location later.");
        int countryId = selected(country);
        int stateId = selected(state);
        if(countryId != preferences.getPreferredCountryId() || stateId != preferences.getPreferredStateId())
        {
            preferences.setPreferredCountryId(countryId);
            preferences.setPreferredStateId(stateId);
            preferences.setPreferredCountyId(RadioReferencePreference.INVALID_ID);
        }
        //A later preference flush can be retried after clearing the password invalidates the lookup session.
        savedCountry = countryId;
        savedState = stateId;
        edited = false;
    }

    boolean deferred() { return later.isSelected(); }

    void setSetupEnabled(boolean available)
    {
        enabled = available;
        refreshControls();
    }

    private void refreshControls()
    {
        boolean hasMessage = !status.getText().isBlank();
        feedback.setVisible(hasMessage);
        feedbackGap.setVisible(hasMessage);
        retryGap.setVisible(retry.isVisible());
        country.setEnabled(enabled && !later.isSelected() && countriesReady);
        state.setEnabled(enabled && !later.isSelected() && statesReady && !loading);
        retry.setEnabled(enabled && !later.isSelected() && !loading);
        later.setEnabled(enabled);
    }

    @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }

    @Override public void close()
    {
        closed = true;
        ++generation;
        directory = null;
    }
}
