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

package io.github.dsheirer.channel.metadata.activity;

import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Insets;
import java.util.function.Consumer;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * Tab header for a learned trunked site. Active sites show a green status marker and stale sites show a real Swing
 * close button.
 */
class ChannelActivityTabHeader extends JPanel
{
    private static final Color ACTIVE_COLOR = new Color(0, 145, 40);
    private final ChannelActivityTableModel mTableModel;
    private final Consumer<ChannelActivityTableModel> mCloseHandler;
    private final JLabel mTitleLabel = new JLabel();
    private final JLabel mActiveIndicator = new JLabel("\u25cf");
    private final JButton mCloseButton = new JButton("\u00d7");

    ChannelActivityTabHeader(ChannelActivityTableModel tableModel,
                             Consumer<ChannelActivityTableModel> closeHandler)
    {
        super(new FlowLayout(FlowLayout.LEFT, 4, 0));
        mTableModel = tableModel;
        mCloseHandler = closeHandler;
        setOpaque(false);

        mActiveIndicator.setForeground(ACTIVE_COLOR);
        mCloseButton.setFocusable(false);
        mCloseButton.setMargin(new Insets(0, 4, 0, 4));
        mCloseButton.addActionListener(event -> close());

        add(mActiveIndicator);
        add(mCloseButton);
        add(mTitleLabel);
        update();
    }

    void update()
    {
        boolean active = mTableModel.isControlActive();
        String title = mTableModel.getTitle() != null ? mTableModel.getTitle() : "";
        String tooltip = active ? "Control channel active. " + title :
            "Control channel stale or stopped. Click \u00d7 to close this site tab. " + title;

        mTitleLabel.setText(title);
        mActiveIndicator.setVisible(active);
        mCloseButton.setVisible(!active);
        mCloseButton.setEnabled(!active);
        mCloseButton.getAccessibleContext().setAccessibleName("Close " + title);
        setToolTipText(tooltip);
        mTitleLabel.setToolTipText(tooltip);
        mActiveIndicator.setToolTipText(tooltip);
        mCloseButton.setToolTipText(tooltip);
    }

    private void close()
    {
        if(!mTableModel.isControlActive())
        {
            mCloseHandler.accept(mTableModel);
        }
    }

    JLabel getTitleLabel()
    {
        return mTitleLabel;
    }

    JLabel getActiveIndicator()
    {
        return mActiveIndicator;
    }

    JButton getCloseButton()
    {
        return mCloseButton;
    }
}
