/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */
package io.github.dsheirer.channel.details;

import com.google.common.eventbus.Subscribe;
import io.github.dsheirer.channel.metadata.activity.SelectedFrequencyContext;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.gui.preference.PreferenceEditorType;
import io.github.dsheirer.gui.preference.ViewUserPreferenceEditorRequest;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.dmr.DecodeConfigDMR;
import io.github.dsheirer.preference.PreferenceType;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.stats.StatsWebNavigationState;
import io.github.dsheirer.stats.StatsWebServerService;
import java.awt.Desktop;
import java.awt.EventQueue;
import java.awt.Font;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.text.DecimalFormat;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.function.Supplier;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingConstants;
import net.miginfocom.swing.MigLayout;

/**
 * Contextual launcher for the embedded web console.  Site identity always comes from the configured owner channel,
 * so a control/traffic retune or temporary processing-chain loss cannot change the destination.
 */
public class ChannelWebLinkPanel extends JPanel implements Listener<SelectedFrequencyContext>
{
    private static final DecimalFormat FREQUENCY_FORMAT = new DecimalFormat("0.00000");
    private final Supplier<StatsWebNavigationState> mStateSupplier;
    private final BrowserLauncher mBrowserLauncher;
    private final Map<Destination,JMenuItem> mDestinationItems = new EnumMap<>(Destination.class);
    private final JLabel mSelectionLabel = new JLabel("Selected: None");
    private final JLabel mMessageLabel = new JLabel(" ");
    private final JPopupMenu mOpenWebMenu = new JPopupMenu();
    private final JMenu mSiteMenu = new JMenu("Site");
    private final JMenu mSystemMenu = new JMenu("System");
    private final JMenu mConventionalMenu = new JMenu("Conventional");
    private final JButton mOpenWebButton = new JButton("Open Web \u25BE");
    private final JButton mOpenConsoleButton = new JButton("Open Full Web Console");
    private SelectedFrequencyContext mSelectedFrequencyContext = SelectedFrequencyContext.clear();
    private boolean mDisposed;

    public ChannelWebLinkPanel(StatsWebServerService statsWebServerService)
    {
        this(statsWebServerService::getNavigationState, ChannelWebLinkPanel::browse);
    }

    ChannelWebLinkPanel(Supplier<StatsWebNavigationState> stateSupplier, BrowserLauncher browserLauncher)
    {
        mStateSupplier = stateSupplier;
        mBrowserLauncher = browserLauncher;
        init();
        MyEventBus.getGlobalEventBus().register(this);
        refresh();
    }

    private void init()
    {
        setLayout(new MigLayout("insets 7 8 7 8, fillx, wrap 1, hidemode 3", "[grow,fill]", "[][][]"));
        mSelectionLabel.setFont(mSelectionLabel.getFont().deriveFont(Font.BOLD));
        add(mSelectionLabel, "growx");

        mMessageLabel.setHorizontalAlignment(SwingConstants.LEFT);
        add(mMessageLabel, "growx");

        addMenuItem(mSiteMenu, Destination.SITE_INFO);
        addMenuItem(mSiteMenu, Destination.TOP_TALKGROUPS);
        addMenuItem(mSiteMenu, Destination.CHANNELS);
        addMenuItem(mSiteMenu, Destination.QUALITY);
        addMenuItem(mSiteMenu, Destination.NEIGHBORS);
        addMenuItem(mSiteMenu, Destination.BAND_PLAN);
        addMenuItem(mSiteMenu, Destination.PATCHES);
        addMenuItem(mSiteMenu, Destination.ACTIVITY_LOG);
        addMenuItem(mSystemMenu, Destination.SYSTEM_OVERVIEW);
        addMenuItem(mSystemMenu, Destination.TALKER_ALIASES);
        addMenuItem(mConventionalMenu, Destination.CONVENTIONAL_INFO);
        addMenuItem(mConventionalMenu, Destination.CONVENTIONAL_ACTIVITY);
        mOpenWebMenu.add(mSiteMenu);
        mOpenWebMenu.add(mSystemMenu);
        mOpenWebMenu.add(mConventionalMenu);

        JPanel controls = new JPanel(new MigLayout("insets 0, fillx", "[][grow][]6[]", "[]"));
        mOpenWebButton.setFocusable(false);
        mOpenWebButton.addActionListener(event ->
            mOpenWebMenu.show(mOpenWebButton, 0, mOpenWebButton.getHeight()));
        controls.add(mOpenWebButton);

        mOpenConsoleButton.setFocusable(false);
        mOpenConsoleButton.addActionListener(event -> open(Destination.FULL_WEB_CONSOLE));
        controls.add(mOpenConsoleButton, "cell 2 0");
        JButton settingsButton = new JButton("Web Settings");
        settingsButton.setFocusable(false);
        settingsButton.addActionListener(event -> MyEventBus.getGlobalEventBus().post(
            new ViewUserPreferenceEditorRequest(PreferenceEditorType.WEB_SERVER)));
        controls.add(settingsButton);
        add(controls, "growx");
    }

    private void addMenuItem(JMenu menu, Destination destination)
    {
        JMenuItem item = new JMenuItem(destination.label());
        item.setActionCommand(destination.name());
        item.setToolTipText(destination.description());
        item.getAccessibleContext().setAccessibleDescription(destination.description());
        item.addActionListener(event -> open(destination));
        mDestinationItems.put(destination, item);
        menu.add(item);
    }

    @Override
    public void receive(SelectedFrequencyContext context)
    {
        mSelectedFrequencyContext = context != null ? context : SelectedFrequencyContext.clear();
        EventQueue.invokeLater(this::refresh);
    }

    /**
     * Receives web/logging preference changes after the service has applied them.
     */
    @Subscribe
    public void preferenceUpdated(PreferenceType preferenceType)
    {
        if(preferenceType == PreferenceType.APPLICATION)
        {
            EventQueue.invokeLater(this::refresh);
        }
    }

    private void refresh()
    {
        if(mDisposed)
        {
            return;
        }

        StatsWebNavigationState state = mStateSupplier.get();
        Channel selectedChannel = selectedChannel(mSelectedFrequencyContext);
        NavigationTarget target = navigationTarget(mSelectedFrequencyContext);
        boolean selected = mSelectedFrequencyContext != null && !mSelectedFrequencyContext.clearRequested();
        boolean running = state != null && state.running();
        boolean historyActive = state != null && state.detailedHistoryActive();
        boolean siteAvailable = target.scope() == LinkScope.SITE;
        boolean conventionalAvailable = target.scope() == LinkScope.CONVENTIONAL;

        mSelectionLabel.setText(selectionText(mSelectedFrequencyContext));
        mSiteMenu.setVisible(running && target.supports(Destination.SITE_INFO));
        mSystemMenu.setVisible(running && target.supports(Destination.SYSTEM_OVERVIEW));
        mConventionalMenu.setVisible(running && target.supports(Destination.CONVENTIONAL_INFO));
        mOpenWebButton.setEnabled(running && (siteAvailable || conventionalAvailable));
        mOpenConsoleButton.setEnabled(running);

        if(!running)
        {
            showMessage("Open Web Settings to enable web links.");
        }
        else if(!selected)
        {
            showMessage("Select a channel to open its web pages.");
        }
        else if(siteCapabilities(mSelectedFrequencyContext, selectedChannel).isSite() && !siteAvailable)
        {
            showMessage("The selected channel does not have a site GUID. Site web pages are unavailable.");
        }
        else if(conventionalDecoder(selectedChannel) && !conventionalAvailable)
        {
            showMessage("The selected conventional channel does not have enough identity information " +
                "for a channel-specific web page.");
        }
        else if(!siteAvailable && !conventionalAvailable)
        {
            String decoder = decoderType(selectedChannel) != null ? decoderType(selectedChannel).toString() : "channel";
            showMessage("Channel-specific web pages are not available for " + decoder + ".");
        }
        else
        {
            showMessage(null);
        }

        for(Map.Entry<Destination,JMenuItem> entry: mDestinationItems.entrySet())
        {
            Destination destination = entry.getKey();
            JMenuItem item = entry.getValue();
            boolean supported = target.supports(destination);
            boolean enabled = running && supported &&
                (!destination.requiresDetailedHistory() || historyActive);
            item.setVisible(supported);
            item.setEnabled(enabled);
            item.setToolTipText(destination.requiresDetailedHistory() && !historyActive ?
                "Detailed history logging is not running" : destination.description());
        }

        revalidate();
        repaint();
    }

    private void showMessage(String message)
    {
        mMessageLabel.setText(message != null ? message : " ");
        mMessageLabel.setVisible(message != null);
    }

    JMenuItem getDestinationMenuItem(Destination destination)
    {
        return mDestinationItems.get(destination);
    }

    String getMessageText()
    {
        return mMessageLabel.isVisible() ? mMessageLabel.getText() : null;
    }

    boolean isGroupVisible(LinkScope scope)
    {
        return switch(scope)
        {
            case SITE -> mSiteMenu.isVisible();
            case CONVENTIONAL -> mConventionalMenu.isVisible();
            case GLOBAL -> false;
        };
    }

    boolean isSystemGroupVisible()
    {
        return mSystemMenu.isVisible();
    }

    private void open(Destination destination)
    {
        StatsWebNavigationState state = mStateSupplier.get();
        NavigationTarget target = navigationTarget(mSelectedFrequencyContext);

        if(state == null || !state.running() || !target.supports(destination) ||
            (destination.requiresDetailedHistory() && !state.detailedHistoryActive()))
        {
            refresh();
            return;
        }

        try
        {
            mBrowserLauncher.open(buildUri(state, destination, target.key()));
        }
        catch(Exception e)
        {
            JOptionPane.showMessageDialog(this, "Unable to open the web browser: " + e.getMessage(),
                "Open Web Page", JOptionPane.ERROR_MESSAGE);
        }
    }

    static URI buildUri(StatsWebNavigationState state, Destination destination, String contextKey)
    {
        if(state == null || state.port() <= 0)
        {
            throw new IllegalArgumentException("A valid web server state is required");
        }

        if(destination.scope() != LinkScope.GLOBAL && (contextKey == null || contextKey.isBlank()))
        {
            throw new IllegalArgumentException("A channel context is required");
        }

        StringJoiner query = new StringJoiner("&");
        query.add("view=" + destination.view());

        if(destination.scope() == LinkScope.SITE)
        {
            query.add("guid=" + contextKey);
        }
        else if(destination.scope() == LinkScope.CONVENTIONAL)
        {
            query.add("context=" + contextKey);
        }

        if(destination.tab() != null)
        {
            query.add("tab=" + destination.tab());
        }

        try
        {
            return new URI("http", null, "127.0.0.1", state.port(), "/", query.toString(), null);
        }
        catch(URISyntaxException e)
        {
            throw new IllegalArgumentException("Unable to create web URL", e);
        }
    }

    private static NavigationTarget navigationTarget(SelectedFrequencyContext context)
    {
        Channel channel = selectedChannel(context);

        if(channel == null)
        {
            return NavigationTarget.NONE;
        }

        NavigationCapabilities siteCapabilities = siteCapabilities(context, channel);

        if(siteCapabilities.isSite() && channel.hasRadresGuid())
        {
            return new NavigationTarget(LinkScope.SITE, channel.getRadresGuid(), siteCapabilities);
        }

        if(conventionalDecoder(channel))
        {
            String contextKey = conventionalContextKey(channel, context);

            if(contextKey != null)
            {
                return new NavigationTarget(LinkScope.CONVENTIONAL, contextKey,
                    NavigationCapabilities.CONVENTIONAL);
            }
        }

        return NavigationTarget.NONE;
    }

    private static String conventionalContextKey(Channel channel, SelectedFrequencyContext context)
    {
        if(channel.hasRadresGuid())
        {
            return "GUID:" + channel.getRadresGuid();
        }

        DecoderType decoder = decoderType(channel);

        if(decoder == null)
        {
            return null;
        }

        String kind = decoder == DecoderType.P25_CONVENTIONAL ? "CONVENTIONAL_P25" : "CONVENTIONAL_ANALOG";
        String protocol = decoder.getProtocol() != null && decoder.getProtocol() != io.github.dsheirer.protocol.Protocol.UNKNOWN ?
            decoder.getProtocol().name() : decoder.name();

        if(context != null && context.hasFrequency())
        {
            return kind + ":" + protocol + ":" + context.frequency();
        }

        return channel.getName() != null && !channel.getName().isBlank() ?
            kind + ":" + protocol + ":" + channel.getName() : null;
    }

    private static Channel selectedChannel(SelectedFrequencyContext context)
    {
        if(context == null || context.clearRequested())
        {
            return null;
        }

        return context.ownerChannel() != null ? context.ownerChannel() : context.rowChannel();
    }

    /**
     * Resolves the desktop destinations supported by the same protocol capabilities exposed by the web site view.
     * P25 retains its complete existing navigation. DMR must be explicitly configured as trunked. NXDN has no
     * configured conventional/trunked mode, so the owner supplied by a Systems activity table is the positive
     * trunking evidence. Using the owner also preserves site navigation when a traffic row has exact-frequency scope.
     */
    private static NavigationCapabilities siteCapabilities(SelectedFrequencyContext context, Channel channel)
    {
        DecoderType decoder = decoderType(channel);

        if(decoder == DecoderType.P25_PHASE1 || decoder == DecoderType.P25_PHASE2)
        {
            return NavigationCapabilities.P25_SITE;
        }

        if(decoder == DecoderType.DMR && channel.getDecodeConfiguration() instanceof DecodeConfigDMR dmr &&
            dmr.isTrunked())
        {
            return NavigationCapabilities.TRUNKED_SITE;
        }

        if(decoder == DecoderType.NXDN && context != null && context.ownerChannel() == channel)
        {
            return NavigationCapabilities.TRUNKED_SITE;
        }

        return NavigationCapabilities.NONE;
    }

    private static boolean conventionalDecoder(Channel channel)
    {
        DecoderType decoder = decoderType(channel);
        return decoder == DecoderType.P25_CONVENTIONAL || decoder == DecoderType.NBFM;
    }

    private static DecoderType decoderType(Channel channel)
    {
        return channel != null && channel.getDecodeConfiguration() != null ?
            channel.getDecodeConfiguration().getDecoderType() : null;
    }

    private static String selectionText(SelectedFrequencyContext context)
    {
        if(context == null || context.clearRequested())
        {
            return "Selected: None";
        }

        Channel channel = selectedChannel(context);
        StringJoiner selection = new StringJoiner(" · ");

        if(channel != null)
        {
            addIfPresent(selection, channel.getSystem());
            addIfPresent(selection, channel.getSite());
            addIfPresent(selection, channel.getName());
        }

        if(context.hasFrequency())
        {
            selection.add(FREQUENCY_FORMAT.format(context.frequency() / 1E6d) + " MHz");
        }

        return selection.length() > 0 ? "Selected: " + selection : "Selected channel";
    }

    private static void addIfPresent(StringJoiner joiner, String value)
    {
        if(value != null && !value.isBlank())
        {
            joiner.add(value);
        }
    }

    private static void browse(URI uri) throws IOException
    {
        if(!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE))
        {
            throw new IOException("Desktop web browsing is not supported");
        }

        Desktop.getDesktop().browse(uri);
    }

    public void dispose()
    {
        if(!mDisposed)
        {
            mDisposed = true;
            MyEventBus.getGlobalEventBus().unregister(this);
        }
    }

    @FunctionalInterface
    interface BrowserLauncher
    {
        void open(URI uri) throws IOException;
    }

    enum LinkScope
    {
        SITE,
        CONVENTIONAL,
        GLOBAL
    }

    private record NavigationTarget(LinkScope scope, String key, NavigationCapabilities capabilities)
    {
        private static final NavigationTarget NONE =
            new NavigationTarget(null, null, NavigationCapabilities.NONE);

        boolean supports(Destination destination)
        {
            return destination != null && (destination.scope() == LinkScope.GLOBAL ||
                capabilities.supports(destination));
        }
    }

    enum Destination
    {
        SITE_INFO("Site Info", "Identity and current status", "site", "info", LinkScope.SITE, false),
        TOP_TALKGROUPS("Top Talkgroups", "Most active talkgroups", "site", "talkgroups", LinkScope.SITE, false),
        CHANNELS("Channels", "Control, voice, and data", "site", "channels", LinkScope.SITE, false),
        QUALITY("Signal Quality", "Signal and decode history", "site", "quality", LinkScope.SITE, false),
        NEIGHBORS("Neighbors", "Adjacent site status", "site", "neighbors", LinkScope.SITE, false),
        BAND_PLAN("Band Plan", "Base, offset, and TDMA", "site", "band-plan", LinkScope.SITE, false),
        PATCHES("Patches", "Patch groups and members", "site", "patches", LinkScope.SITE, false),
        ACTIVITY_LOG("Activity Log", "Detailed site events", "site", "activity", LinkScope.SITE, true),
        SYSTEM_OVERVIEW("System Overview", "System-wide information", "system", "info", LinkScope.SITE, false),
        TALKER_ALIASES("Talker Aliases", "Observed radio aliases", "system", "talker-aliases", LinkScope.SITE, false),
        CONVENTIONAL_INFO("Channel Info", "Identity and frequency summary", "conventional-detail", "info",
            LinkScope.CONVENTIONAL, false),
        CONVENTIONAL_ACTIVITY("Activity", "Detailed channel events", "conventional-detail", "activity",
            LinkScope.CONVENTIONAL, true),
        FULL_WEB_CONSOLE("Full Web Console", "Dashboard and live views", "dashboard", null, LinkScope.GLOBAL, false);

        private final String mLabel;
        private final String mDescription;
        private final String mView;
        private final String mTab;
        private final LinkScope mScope;
        private final boolean mRequiresDetailedHistory;

        Destination(String label, String description, String view, String tab, LinkScope scope,
                    boolean requiresDetailedHistory)
        {
            mLabel = label;
            mDescription = description;
            mView = view;
            mTab = tab;
            mScope = scope;
            mRequiresDetailedHistory = requiresDetailedHistory;
        }

        String label()
        {
            return mLabel;
        }

        String description()
        {
            return mDescription;
        }

        String view()
        {
            return mView;
        }

        String tab()
        {
            return mTab;
        }

        LinkScope scope()
        {
            return mScope;
        }

        boolean requiresDetailedHistory()
        {
            return mRequiresDetailedHistory;
        }
    }

    /**
     * Desktop navigation mirrors the site capabilities returned by the web backend. Non-P25 trunked sites currently
     * expose protocol-neutral site identity, channels, quality, and neighbors. P25-only summaries and system views
     * stay unavailable instead of opening an empty or unrelated page.
     */
    private enum NavigationCapabilities
    {
        P25_SITE(EnumSet.of(Destination.SITE_INFO, Destination.TOP_TALKGROUPS, Destination.CHANNELS,
            Destination.QUALITY, Destination.NEIGHBORS, Destination.BAND_PLAN, Destination.PATCHES,
            Destination.ACTIVITY_LOG, Destination.SYSTEM_OVERVIEW, Destination.TALKER_ALIASES)),
        TRUNKED_SITE(EnumSet.of(Destination.SITE_INFO, Destination.CHANNELS, Destination.QUALITY,
            Destination.NEIGHBORS)),
        CONVENTIONAL(EnumSet.of(Destination.CONVENTIONAL_INFO, Destination.CONVENTIONAL_ACTIVITY)),
        NONE(EnumSet.noneOf(Destination.class));

        private final Set<Destination> mDestinations;

        NavigationCapabilities(EnumSet<Destination> destinations)
        {
            mDestinations = Set.copyOf(destinations);
        }

        boolean supports(Destination destination)
        {
            return mDestinations.contains(destination);
        }

        boolean isSite()
        {
            return this == P25_SITE || this == TRUNKED_SITE;
        }
    }
}
