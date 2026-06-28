/*******************************************************************************
 * sdrtrunk
 * Copyright (C) 2014-2017 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 *
 ******************************************************************************/
package io.github.dsheirer.channel.details;

import io.github.dsheirer.channel.metadata.activity.SelectedFrequencyContext;
import io.github.dsheirer.channel.state.DecoderState;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.ChannelProcessingManager;
import io.github.dsheirer.module.ProcessingChain;
import io.github.dsheirer.sample.Listener;
import net.miginfocom.swing.MigLayout;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.text.DefaultCaret;
import java.awt.EventQueue;
import java.text.DecimalFormat;

public class ChannelDetailPanel extends JPanel implements Listener<SelectedFrequencyContext>
{
    private static final String EMPTY_DETAILS = "Please select a channel to view details";
    private static final DecimalFormat FREQUENCY_FORMAT = new DecimalFormat("0.00000");

    private JLabel mSystemLabel;
    private JLabel mSiteLabel;
    private JLabel mNameLabel;
    private JTextArea mDetailTextPane;

    private ChannelProcessingManager mChannelProcessingManager;
    private SelectedFrequencyContext mSelectedFrequencyContext;

    public ChannelDetailPanel(ChannelProcessingManager channelProcessingManager)
    {
        mChannelProcessingManager = channelProcessingManager;

        init();
    }

    private void init()
    {
        setLayout(new MigLayout("insets 0 0 0 0", "[grow,fill]", "[]0[grow,fill]"));

        JPanel buttonPanel = new JPanel();
        buttonPanel.setLayout(new MigLayout("insets 1 1 1 1", "[][grow,fill][][grow,fill][][grow,fill][]", ""));

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
        refreshButton.addActionListener(e -> receive(mSelectedFrequencyContext));
        buttonPanel.add(refreshButton);

        add(buttonPanel, "wrap");

        mDetailTextPane = new JTextArea(EMPTY_DETAILS);
        DefaultCaret caret = (DefaultCaret)mDetailTextPane.getCaret();
        caret.setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
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
        final String name;

        if(channel != null)
        {
            name = channel.getChannelType() == Channel.ChannelType.TRAFFIC ? "Traffic Channel" : channel.getName();
        }
        else
        {
            name = null;
        }

        final String details;

        if(processingChain != null)
        {
            StringBuilder sb = new StringBuilder();

            for(DecoderState decoderState : processingChain.getDecoderStates())
            {
                sb.append(decoderState.getActivitySummary());
            }

            details = sb.toString();
        }
        else if(context != null && context.hasFrequency() && !context.clearRequested())
        {
            StringBuilder sb = new StringBuilder();
            sb.append("Selected Frequency: ").append(FREQUENCY_FORMAT.format(context.frequency() / 1E6d)).append(" MHz\n");

            if(context.timeslot() != null)
            {
                sb.append("Timeslot: ").append(context.timeslot()).append("\n");
            }

            if(context.decoderHint() != null)
            {
                sb.append("Decoder: ").append(context.decoderHint()).append("\n");
            }

            sb.append("\nNo active decoder chain for the selected frequency.");
            details = sb.toString();
        }
        else
        {
            details = EMPTY_DETAILS;
        }

        EventQueue.invokeLater(() -> {
            mSystemLabel.setText(system);
            mSiteLabel.setText(site);
            mNameLabel.setText(name);
            mDetailTextPane.setText(details);
        });
    }
}
