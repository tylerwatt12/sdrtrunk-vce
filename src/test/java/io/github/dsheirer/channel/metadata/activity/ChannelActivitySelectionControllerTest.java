/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.channel.metadata.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.controller.channel.Channel;
import org.junit.jupiter.api.Test;

class ChannelActivitySelectionControllerTest
{
    @Test
    void modelInsertionCannotChangeLogicalSelection()
    {
        ChannelActivityTableModel model = model();
        ChannelActivityRow selected = row(model, "selected", ChannelActivityRow.Role.TRAFFIC, 852_000_000L);
        ChannelActivitySelectionController controller = new ChannelActivitySelectionController();
        controller.select(model, selected);

        row(model, "inserted-before", ChannelActivityRow.Role.TRAFFIC, 851_000_000L);

        assertSame(selected, controller.resolveSelectedRow());
        assertEquals("selected", controller.getSelection().rowKey());
    }

    @Test
    void newerUserSelectionWinsBeforePassiveModelRender()
    {
        ChannelActivityTableModel model = model();
        ChannelActivityRow previous = row(model, "previous", ChannelActivityRow.Role.TRAFFIC, 852_000_000L);
        ChannelActivityRow clicked = row(model, "clicked", ChannelActivityRow.Role.TRAFFIC, 853_000_000L);
        ChannelActivitySelectionController controller = new ChannelActivitySelectionController();
        controller.select(model, previous);

        //Represents a queued table redraw followed by a user click before that redraw executes.
        row(model, "model-update", ChannelActivityRow.Role.TRAFFIC, 851_000_000L);
        controller.select(model, clicked);

        assertSame(clicked, controller.resolveSelectedRow());
        assertEquals("clicked", controller.getSelection().rowKey());
    }

    @Test
    void removedExactFrequencySelectionClears()
    {
        ChannelActivityTableModel model = model();
        ChannelActivityRow selected = row(model, "traffic", ChannelActivityRow.Role.TRAFFIC, 852_000_000L);
        ChannelActivitySelectionController controller = new ChannelActivitySelectionController();
        controller.select(model, selected);

        model.remove(selected);

        assertNull(controller.resolveSelectedRow());
        assertNull(controller.getSelection());
    }

    @Test
    void siteSelectionSurvivesEmptyModelAndBindsReplacementControl()
    {
        ChannelActivityTableModel model = model();
        ChannelActivityRow selected = row(model, "old-control", ChannelActivityRow.Role.CURRENT_CONTROL,
            851_000_000L);
        ChannelActivitySelectionController controller = new ChannelActivitySelectionController();
        controller.select(model, selected);
        model.remove(selected);

        assertNull(controller.resolveSelectedRow());
        assertNotNull(controller.getSelection());
        assertTrue(controller.getSelection().isSite());

        ChannelActivityRow replacement = row(model, "new-control", ChannelActivityRow.Role.CURRENT_CONTROL,
            852_000_000L);

        assertSame(replacement, controller.resolveSelectedRow());
        assertEquals("new-control", controller.getSelection().rowKey());
        assertEquals(852_000_000L, replacement.getFrequency());
    }

    @Test
    void siteSelectionFollowsCurrentWhenPreviousControlIsDemotedButRetained()
    {
        ChannelActivityTableModel model = model();
        ChannelActivityRow previous = row(model, "old-control", ChannelActivityRow.Role.CURRENT_CONTROL,
            851_000_000L);
        ChannelActivitySelectionController controller = new ChannelActivitySelectionController();
        controller.select(model, previous);

        //Role changes are authoritative even though display tags retain historical control evidence.
        previous.setRole(ChannelActivityRow.Role.CONFIGURED_CONTROL);
        ChannelActivityRow current = row(model, "new-control", ChannelActivityRow.Role.CURRENT_CONTROL,
            852_000_000L);

        assertSame(current, controller.resolveSelectedRow());
        assertEquals("new-control", controller.getSelection().rowKey());
    }

    @Test
    void clickingAlternateControlImmediatelyBindsCurrentControl()
    {
        ChannelActivityTableModel model = model();
        ChannelActivityRow alternate = row(model, "alternate", ChannelActivityRow.Role.ALTERNATE_CONTROL,
            851_000_000L);
        ChannelActivityRow current = row(model, "current", ChannelActivityRow.Role.CURRENT_CONTROL,
            852_000_000L);
        ChannelActivitySelectionController controller = new ChannelActivitySelectionController();

        controller.select(model, alternate);

        assertSame(current, controller.resolveSelectedRow());
        assertEquals(ChannelActivitySelectionScope.SITE, controller.getSelection().scope());
    }

    @Test
    void siteSelectionWaitsWithoutHighlightingConfiguredOrAlternateControl()
    {
        ChannelActivityTableModel model = model();
        ChannelActivityRow configured = row(model, "configured", ChannelActivityRow.Role.CONFIGURED_CONTROL,
            851_000_000L);
        row(model, "alternate", ChannelActivityRow.Role.ALTERNATE_CONTROL, 852_000_000L);
        ChannelActivitySelectionController controller = new ChannelActivitySelectionController();

        controller.select(model, configured);

        assertNull(controller.resolveSelectedRow());
        assertNotNull(controller.getSelection());
        assertTrue(controller.getSelection().isSite());

        ChannelActivityRow current = row(model, "current", ChannelActivityRow.Role.CURRENT_CONTROL,
            853_000_000L);

        assertSame(current, controller.resolveSelectedRow());
    }

    @Test
    void trafficRoleWithControlEvidenceRemainsExact()
    {
        ChannelActivityTableModel model = model();
        ChannelActivityRow row = row(model, "shared", ChannelActivityRow.Role.TRAFFIC, 851_000_000L);
        row.addTag(ChannelTag.CURRENT_CONTROL);
        ChannelActivitySelectionController controller = new ChannelActivitySelectionController();

        controller.select(model, row);

        assertFalse(controller.getSelection().isSite());
        assertEquals(ChannelActivitySelectionScope.EXACT, controller.getSelection().scope());
    }

    @Test
    void switchingSystemsTablesClearsSelection()
    {
        ChannelActivityTableModel conventional = model("Conventional");
        ChannelActivityTableModel trunkedSite = model("Trunked Site");
        ChannelActivitySelectionController controller = new ChannelActivitySelectionController();
        controller.select(conventional,
            row(conventional, "conventional", ChannelActivityRow.Role.CONVENTIONAL, 151_000_000L));

        assertFalse(controller.clearIfSelectionIsOutside(conventional));
        assertNotNull(controller.getSelection());
        assertEquals(ChannelActivitySelectionScope.EXACT, controller.getSelection().scope());
        assertTrue(controller.clearIfSelectionIsOutside(trunkedSite));
        assertNull(controller.getSelection());
    }

    @Test
    void switchingBetweenTrunkedSiteTablesClearsSelection()
    {
        ChannelActivityTableModel firstSite = model("First Trunked Site");
        ChannelActivityTableModel secondSite = model("Second Trunked Site");
        ChannelActivitySelectionController controller = new ChannelActivitySelectionController();
        controller.select(firstSite,
            row(firstSite, "control", ChannelActivityRow.Role.CURRENT_CONTROL, 851_000_000L));

        assertTrue(controller.clearIfSelectionIsOutside(secondSite));
        assertNull(controller.getSelection());
    }

    private static ChannelActivityTableModel model()
    {
        return model("Test Site");
    }

    private static ChannelActivityTableModel model(String title)
    {
        Channel owner = new Channel(title);
        return new ChannelActivityTableModel(title, owner, true);
    }

    private static ChannelActivityRow row(ChannelActivityTableModel model, String key, ChannelActivityRow.Role role,
                                          long frequency)
    {
        return model.getOrCreate(key, model.getOwnerChannel(), role, frequency, null);
    }
}
