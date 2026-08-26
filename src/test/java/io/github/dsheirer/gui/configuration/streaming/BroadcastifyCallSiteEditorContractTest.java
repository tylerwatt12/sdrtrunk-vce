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

package io.github.dsheirer.gui.configuration.streaming;

import io.github.dsheirer.controller.channel.Channel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BroadcastifyCallSiteEditorContractTest
{
    @Test
    void presentsTheSiteRestrictionInPlainLanguage()
    {
        assertEquals("Send calls only from this trunked site",
            BroadcastifyCallSiteEditor.SITE_FIELD_LABEL);
        assertTrue(BroadcastifyCallSiteEditor.SITE_EXPLANATION.contains("alias routes the call"));
        assertTrue(BroadcastifyCallSiteEditor.SITE_EXPLANATION.contains("selected trunked site observed the call"));
        assertTrue(BroadcastifyCallSiteEditor.SITE_EXPLANATION.contains("prevents calls heard only on other sites"));
    }

    @Test
    void identifiesASelectableChannelBySystemSiteAndChannel()
    {
        Channel channel = new Channel("Control");
        channel.setSystem("County P25");
        channel.setSite("West");

        assertEquals("County P25 / West / Control", BroadcastifyCallSiteEditor.channelLabel(channel));
    }
}
