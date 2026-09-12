/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.gui;

/** Opens the web-first Channel manager, optionally focused on one configured Channel. */
public final class ViewWebChannelRequest extends JavaFxWindowRequest
{
    private final String mConfigurationId;

    public ViewWebChannelRequest()
    {
        mConfigurationId = null;
    }

    public ViewWebChannelRequest(String configurationId)
    {
        mConfigurationId = java.util.UUID.fromString(configurationId).toString();
    }

    public String getConfigurationId()
    {
        return mConfigurationId;
    }

    public boolean hasChannel()
    {
        return mConfigurationId != null;
    }
}
