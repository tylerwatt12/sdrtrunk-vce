/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */

package io.github.dsheirer.gui.configuration.channel;

import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.module.decode.p25.P25SiteIdentity;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.geometry.HPos;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;

/**
 * Read-only learned site identity shown by the P25 trunked channel editors.
 */
final class P25SiteIdentityView extends GridPane
{
    private final TextField mIdentityField = new TextField();
    private final ChangeListener<P25SiteIdentity> mIdentityListener =
        (observable, oldValue, newValue) -> update();
    private Channel mChannel;

    P25SiteIdentityView()
    {
        setHgap(10);
        setMaxWidth(Double.MAX_VALUE);

        Label label = new Label("Learned Site Identity");
        GridPane.setHalignment(label, HPos.RIGHT);
        add(label, 0, 0);

        mIdentityField.setEditable(false);
        mIdentityField.setMaxWidth(Double.MAX_VALUE);
        mIdentityField.setTooltip(new Tooltip(
            "WACN-System / RFSS-Site identity verified from the active P25 control channel"));
        GridPane.setHgrow(mIdentityField, Priority.ALWAYS);
        add(mIdentityField, 1, 0);
    }

    void setChannel(Channel channel)
    {
        if(mChannel != null)
        {
            mChannel.p25SiteIdentityProperty().removeListener(mIdentityListener);
        }

        mChannel = channel;

        if(mChannel != null)
        {
            mChannel.p25SiteIdentityProperty().addListener(mIdentityListener);
        }

        update();
    }

    void dispose()
    {
        setChannel(null);
    }

    private void update()
    {
        Runnable update = () -> {
            P25SiteIdentity identity = mChannel != null ? mChannel.getP25SiteIdentity() : null;
            mIdentityField.setText(identity != null ? identity.display() : null);
        };

        if(Platform.isFxApplicationThread())
        {
            update.run();
        }
        else
        {
            Platform.runLater(update);
        }
    }
}
