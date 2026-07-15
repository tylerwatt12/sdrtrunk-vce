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

import io.github.dsheirer.channel.metadata.activity.SelectedFrequencyContext;
import io.github.dsheirer.channel.state.DecoderState;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.ChannelProcessingManager;
import io.github.dsheirer.identifier.alias.TalkerAliasManager;
import io.github.dsheirer.identifier.alias.TalkerAliasManagerProvider;
import io.github.dsheirer.module.ProcessingChain;
import io.github.dsheirer.sample.Listener;
import java.awt.EventQueue;
import java.text.DecimalFormat;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.text.DefaultCaret;
import net.miginfocom.swing.MigLayout;

/**
 * Selected channel details, including the system-scoped talker-alias cache.
 */
public class ChannelDetailPanel extends JPanel implements Listener<SelectedFrequencyContext>
{
    private static final String EMPTY_DETAILS = "Please select a channel to view details";
    private static final DecimalFormat FREQUENCY_FORMAT = new DecimalFormat("0.00000");
    private final ChannelProcessingManager mChannelProcessingManager;
    private JLabel mSystemLabel;
    private JLabel mSiteLabel;
    private JLabel mNameLabel;
    private JTextArea mDetailTextPane;
    private SelectedFrequencyContext mSelectedFrequencyContext;

    public ChannelDetailPanel(ChannelProcessingManager channelProcessingManager)
    {
        mChannelProcessingManager = channelProcessingManager;
        init();
    }

    private void init()
    {
        setLayout(new MigLayout("insets 0 0 0 0", "[grow,fill]", "[]0[grow,fill]"));
        JPanel buttonPanel = new JPanel(new MigLayout("insets 1 1 1 1",
            "[][grow,fill][][grow,fill][][grow,fill][]", ""));

        buttonPanel.add(new JLabel("System:"));
        mSystemLabel = new JLabel(" ");
        buttonPanel.add(mSystemLabel);
        buttonPanel.add(new JLabel("Site:"));
        mSiteLabel = new JLabel(" ");
        buttonPanel.add(mSiteLabel);
        buttonPanel.add(new JLabel("Channel Name:"));
        mNameLabel = new JLabel(" ");
        buttonPanel.add(mNameLabel);

        JButton refreshButton = new JButton("Refresh");
        refreshButton.addActionListener(event -> receive(mSelectedFrequencyContext));
        buttonPanel.add(refreshButton);
        add(buttonPanel, "wrap");

        mDetailTextPane = new JTextArea(EMPTY_DETAILS);
        mDetailTextPane.setEditable(false);
        ((DefaultCaret)mDetailTextPane.getCaret()).setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
        add(new JScrollPane(mDetailTextPane));
    }

    @Override
    public void receive(SelectedFrequencyContext context)
    {
        mSelectedFrequencyContext = context;
        ProcessingChain processingChain = context != null && !context.clearRequested() ?
            context.processingChain() : null;
        Channel channel = processingChain != null ? mChannelProcessingManager.getChannel(processingChain) : null;

        if(channel == null && context != null)
        {
            channel = context.rowChannel() != null ? context.rowChannel() : context.ownerChannel();
        }

        final String system = channel != null ? channel.getSystem() : null;
        final String site = channel != null ? channel.getSite() : null;
        final String name = channel != null && channel.getChannelType() == Channel.ChannelType.TRAFFIC ?
            "Traffic Channel" : channel != null ? channel.getName() : null;
        final String details = details(processingChain, context);

        EventQueue.invokeLater(() -> {
            mSystemLabel.setText(system);
            mSiteLabel.setText(site);
            mNameLabel.setText(name);
            mDetailTextPane.setText(details);
        });
    }

    private static String details(ProcessingChain processingChain, SelectedFrequencyContext context)
    {
        if(processingChain != null)
        {
            StringBuilder summary = new StringBuilder();
            Set<TalkerAliasManager> managers = Collections.newSetFromMap(new IdentityHashMap<>());

            for(DecoderState decoderState: processingChain.getDecoderStates())
            {
                if(decoderState instanceof TalkerAliasManagerProvider provider &&
                    managers.add(provider.getTalkerAliasManager()))
                {
                    summary.append(provider.getTalkerAliasManager().getAliasSummary());
                }
            }

            return summary.isEmpty() ? "No talker-alias details are available for this channel." : summary.toString();
        }

        if(context != null && context.hasFrequency() && !context.clearRequested())
        {
            StringBuilder summary = new StringBuilder();
            summary.append("Selected Frequency: ")
                .append(FREQUENCY_FORMAT.format(context.frequency() / 1E6d)).append(" MHz\n");

            if(context.timeslot() != null)
            {
                summary.append("Timeslot: ").append(context.timeslot()).append("\n");
            }

            if(context.decoderHint() != null)
            {
                summary.append("Decoder: ").append(context.decoderHint()).append("\n");
            }

            summary.append("\nNo active decoder chain for the selected frequency.");
            return summary.toString();
        }

        return EMPTY_DETAILS;
    }
}
