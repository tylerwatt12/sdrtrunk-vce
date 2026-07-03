/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */

package io.github.dsheirer.channel.metadata;

import com.google.common.eventbus.Subscribe;
import io.github.dsheirer.audio.broadcast.radioresolve.RadioResolveMetadataStatusEvent;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.module.decode.p25.telemetry.P25NetworkConfigurationSnapshot;
import io.github.dsheirer.sample.Listener;
import java.awt.BorderLayout;
import java.awt.Font;
import java.text.DecimalFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import net.miginfocom.swing.MigLayout;

/**
 * Read-only RadioResolve RF metadata diagnostics tab.
 */
public class RadioResolveMetadataPanel extends JPanel implements Listener<Channel>
{
    private static final DecimalFormat FREQUENCY_FORMAT = new DecimalFormat("0.000000");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
        .withZone(ZoneId.systemDefault());
    private final Map<String,JLabel> mValueLabels = new TreeMap<>();
    private final Map<String,RadioResolveMetadataStatusEvent> mLatestByGuid = new HashMap<>();
    private final Map<String,RadioResolveMetadataStatusEvent> mLastSendByGuid = new HashMap<>();
    private final JPanel mContentPanel = new JPanel(new MigLayout("insets 8 10 8 10", "[][grow,fill]", ""));
    private String mSelectedGuid;

    public RadioResolveMetadataPanel()
    {
        setLayout(new BorderLayout());
        JScrollPane scrollPane = new JScrollPane(mContentPanel, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        add(scrollPane, BorderLayout.CENTER);

        addSection("Current Stabilized RF Metadata");
        addRow("Channel", "channel");
        addRow("GUID", "guid");
        addRow("Alias List", "aliasList");
        addRow("Node", "node");
        addRow("Timezone", "timezone");
        addRow("Host", "host");
        addRow("Decoder", "decoder");
        addRow("WACN", "wacn");
        addRow("System", "system");
        addRow("NAC", "nac");
        addRow("RFSS", "rfss");
        addRow("Site", "site");
        addRow("Primary Control", "primaryControl");
        addRow("Alternate Controls", "alternateControls");
        addRow("Channels", "channels");
        addRow("Neighbors", "neighbors");
        addRow("Frequency Bands", "bands");
        addRow("Patch Groups", "patchGroups");
        addRow("Last Observed", "lastObserved");

        addSection("Send Readiness");
        addRow("Ready To Send", "ready");
        addRow("Reason", "readiness");
        addRow("Summary Hash", "summaryHash");

        addSection("Last Send");
        addRow("Stage", "stage");
        addRow("Attempt Time", "attemptTime");
        addRow("HTTP Status", "httpStatus");
        addRow("Payload Bytes", "payloadBytes");
        addRow("Result", "result");

        MyEventBus.getGlobalEventBus().register(this);
    }

    public void dispose()
    {
        try
        {
            MyEventBus.getGlobalEventBus().unregister(this);
        }
        catch(IllegalArgumentException ignored)
        {
            // Already unregistered.
        }
    }

    @Subscribe
    public void receive(RadioResolveMetadataStatusEvent event)
    {
        if(event == null || event.guid() == null || event.guid().isBlank())
        {
            return;
        }

        synchronized(this)
        {
            mLatestByGuid.put(event.guid(), event);

            if(event.stage() != RadioResolveMetadataStatusEvent.Stage.KNOWN)
            {
                mLastSendByGuid.put(event.guid(), event);
            }
        }

        if(!event.guid().equals(mSelectedGuid))
        {
            return;
        }

        if(SwingUtilities.isEventDispatchThread())
        {
            update(event);
        }
        else
        {
            SwingUtilities.invokeLater(() -> update(event));
        }
    }

    @Override
    public void receive(Channel channel)
    {
        String selectedGuid = channel != null ? channel.getRadresGuid() : null;

        if(selectedGuid != null && selectedGuid.isBlank())
        {
            selectedGuid = null;
        }

        String guid = selectedGuid;

        if(SwingUtilities.isEventDispatchThread())
        {
            setSelectedGuid(guid);
        }
        else
        {
            SwingUtilities.invokeLater(() -> setSelectedGuid(guid));
        }
    }

    private void setSelectedGuid(String guid)
    {
        mSelectedGuid = guid;
        clearValues();

        if(guid == null)
        {
            return;
        }

        RadioResolveMetadataStatusEvent latest;
        RadioResolveMetadataStatusEvent lastSend;

        synchronized(this)
        {
            latest = mLatestByGuid.get(guid);
            lastSend = mLastSendByGuid.get(guid);
        }

        if(latest != null)
        {
            update(latest);
        }

        if(lastSend != null && lastSend != latest)
        {
            update(lastSend);
        }
    }

    private void update(RadioResolveMetadataStatusEvent event)
    {
        P25NetworkConfigurationSnapshot snapshot = event.snapshot();
        P25NetworkConfigurationSnapshot.Network network = snapshot != null ? snapshot.network() : null;
        P25NetworkConfigurationSnapshot.CurrentSite currentSite = snapshot != null ? snapshot.currentSite() : null;

        setValue("channel", event.channelName());
        setValue("guid", event.guid());
        setValue("aliasList", event.aliasListName());
        setValue("node", event.nodeName());
        setValue("timezone", event.timezone());
        setValue("host", event.host());
        setValue("decoder", snapshot != null ? snapshot.decoder() : null);
        setValue("wacn", formatP25Identifier(network != null ? network.wacn() : null, 5));
        setValue("system", formatP25Identifier(network != null ? network.system() : null, 3));
        setValue("nac", formatP25Identifier(value(currentSite != null ? currentSite.nac() : null,
            network != null ? network.nac() : null), 3));
        setValue("rfss", formatP25Identifier(currentSite != null ? currentSite.rfss() : null, 2));
        setValue("site", formatP25Identifier(currentSite != null ? currentSite.site() : null, 2));
        setValue("primaryControl", primaryControl(snapshot));
        setValue("alternateControls", alternateControls(snapshot));
        setValue("channels", count(snapshot != null ? snapshot.channels() : null));
        setValue("neighbors", count(snapshot != null ? snapshot.neighborSites() : null));
        setValue("bands", count(snapshot != null ? snapshot.frequencyBands() : null));
        setValue("patchGroups", count(snapshot != null ? snapshot.patchGroups() : null));
        setValue("lastObserved", formatTime(event.timestamp()));

        setValue("ready", event.readyToSend() ? "Yes" : "No");
        setValue("readiness", event.readinessMessage());
        setValue("summaryHash", event.summaryHash());

        if(event.stage() != RadioResolveMetadataStatusEvent.Stage.KNOWN)
        {
            setValue("stage", event.stage().toString());
            setValue("attemptTime", formatTime(event.timestamp()));
            setValue("httpStatus", event.httpStatus() != null ? event.httpStatus().toString() : null);
            setValue("payloadBytes", event.payloadBytes() != null ? event.payloadBytes().toString() : null);
            setValue("result", event.resultMessage());
        }
    }

    private void addSection(String title)
    {
        JLabel label = new JLabel(title);
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        mContentPanel.add(label, "span 2, gapy 8 2, wrap");
    }

    private void addRow(String label, String key)
    {
        JLabel name = new JLabel(label);
        JLabel value = new JLabel(" ");
        value.setFont(Font.decode(Font.MONOSPACED));
        value.setBorder(BorderFactory.createEmptyBorder(1, 8, 1, 0));
        mValueLabels.put(key, value);
        mContentPanel.add(name, "align right");
        mContentPanel.add(value, "growx, wrap");
    }

    private void setValue(String key, String value)
    {
        JLabel label = mValueLabels.get(key);

        if(label != null)
        {
            label.setText(value == null || value.isBlank() ? " " : value);
        }
    }

    private void clearValues()
    {
        for(JLabel label: mValueLabels.values())
        {
            label.setText(" ");
        }
    }

    private static Integer value(Integer preferred, Integer fallback)
    {
        return preferred != null ? preferred : fallback;
    }

    private static String count(List<?> values)
    {
        return values != null ? String.valueOf(values.size()) : "0";
    }

    private static String formatTime(long timestamp)
    {
        return timestamp > 0 ? TIME_FORMAT.format(Instant.ofEpochMilli(timestamp)) : null;
    }

    private static String formatP25Identifier(Integer value, int width)
    {
        if(value == null)
        {
            return null;
        }

        String hex = Integer.toHexString(value).toUpperCase(Locale.US);

        while(hex.length() < width)
        {
            hex = "0" + hex;
        }

        return hex + " (" + value + ")";
    }

    private static String primaryControl(P25NetworkConfigurationSnapshot snapshot)
    {
        if(snapshot == null || snapshot.channels() == null)
        {
            return null;
        }

        for(P25NetworkConfigurationSnapshot.Channel channel: snapshot.channels())
        {
            if(channel != null && "primary_control".equals(channel.role()))
            {
                return channelText(channel);
            }
        }

        return null;
    }

    private static String alternateControls(P25NetworkConfigurationSnapshot snapshot)
    {
        if(snapshot == null || snapshot.channels() == null)
        {
            return null;
        }

        List<String> controls = new ArrayList<>();

        for(P25NetworkConfigurationSnapshot.Channel channel: snapshot.channels())
        {
            if(channel != null && "secondary_control".equals(channel.role()))
            {
                controls.add(channelText(channel));
            }
        }

        return controls.isEmpty() ? null : String.join(", ", controls);
    }

    private static String channelText(P25NetworkConfigurationSnapshot.Channel channel)
    {
        StringBuilder sb = new StringBuilder();

        if(channel.descriptor() != null && !channel.descriptor().isBlank())
        {
            sb.append(channel.descriptor());
        }

        if(channel.downlink() != null && channel.downlink() > 0)
        {
            if(!sb.isEmpty())
            {
                sb.append("  ");
            }

            sb.append(FREQUENCY_FORMAT.format(channel.downlink() / 1_000_000.0)).append(" MHz");
        }

        return sb.isEmpty() ? "" : sb.toString();
    }
}
