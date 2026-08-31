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

package io.github.dsheirer.gui.configuration.channel;

import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.gui.ViewWebP25BandplanOverrideRequest;
import io.github.dsheirer.module.decode.p25.P25SiteIdentity;
import io.github.dsheirer.module.decode.p25.bandplan.P25BandplanOverrideRegistry;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;

/** Shared checkbox and two-state status used by both trunked P25 channel editors. */
final class P25BandplanOverrideControl extends HBox
{
    private static final String ACTIVE = "Override found and in use";
    private static final String FALLBACK = "No override found, falling back to OTA bandplan";
    private final P25BandplanOverrideRegistry mRegistry;
    private final CheckBox mEnabled = new CheckBox("Use P25 bandplan override");
    private final Label mStatus = new Label();
    private final Button mCreate = new Button("Create override");
    private final Runnable mRegistryListener = this::updateStatus;
    private final ChangeListener<P25SiteIdentity> mIdentityListener =
        (observable, oldValue, newValue) -> updateStatus();
    private Channel mChannel;

    P25BandplanOverrideControl(P25BandplanOverrideRegistry registry, Runnable modified)
    {
        mRegistry = registry;
        setAlignment(Pos.CENTER_LEFT);
        setSpacing(12);
        mEnabled.setTooltip(new Tooltip(
            "Use a matching profile from Administration > P25 Bandplan Overrides instead of the OTA bandplan"));
        mEnabled.selectedProperty().addListener((observable, oldValue, newValue) -> {
            updateStatus();
            modified.run();
        });
        mStatus.setStyle("-fx-border-color: -fx-box-border; -fx-border-radius: 3; -fx-padding: 4 8 4 8;");
        mCreate.setTooltip(new Tooltip("Create a site-scoped override in the web editor"));
        mCreate.setOnAction(event -> {
            P25SiteIdentity identity = mChannel != null ? mChannel.getP25SiteIdentity() : null;
            String siteGuid = mChannel != null ? mChannel.radresGuidProperty().get() : null;

            if(identity != null && siteGuid != null)
            {
                MyEventBus.getGlobalEventBus().post(new ViewWebP25BandplanOverrideRequest(identity, siteGuid));
            }
        });
        mRegistry.addChangeListener(mRegistryListener);
        getChildren().addAll(mEnabled, mStatus, mCreate);
        updateStatus();
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

        updateStatus();
    }

    void dispose()
    {
        mRegistry.removeChangeListener(mRegistryListener);
        setChannel(null);
    }

    void setControlDisabled(boolean disabled)
    {
        mEnabled.setDisable(disabled);
        updateStatus();
    }

    void setSelected(boolean selected)
    {
        mEnabled.setSelected(selected);
        updateStatus();
    }

    boolean isSelected()
    {
        return mEnabled.isSelected();
    }

    private void updateStatus()
    {
        Runnable update = () -> {
            boolean visible = !mEnabled.isDisabled() && mEnabled.isSelected();
            mStatus.setManaged(visible);
            mStatus.setVisible(visible);
            boolean matched = visible && mRegistry.hasMatch(
                mChannel != null ? mChannel.getP25SiteIdentity() : null);
            boolean createVisible = visible && !matched;
            mCreate.setManaged(createVisible);
            mCreate.setVisible(createVisible);
            mCreate.setDisable(mChannel == null || mChannel.getP25SiteIdentity() == null ||
                mChannel.radresGuidProperty().get() == null);

            if(visible)
            {
                mStatus.setText(matched ? ACTIVE : FALLBACK);
                mStatus.setTextFill(Color.web(matched ? "#2E9D55" : "#D9534F"));
            }
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
