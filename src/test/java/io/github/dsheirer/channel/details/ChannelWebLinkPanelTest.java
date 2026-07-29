/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.channel.details;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.channel.metadata.activity.ChannelActivitySelectionScope;
import io.github.dsheirer.channel.metadata.activity.SelectedFrequencyContext;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.module.decode.dmr.DMRChannelMode;
import io.github.dsheirer.module.decode.dmr.DecodeConfigDMR;
import io.github.dsheirer.module.decode.nbfm.DecodeConfigNBFM;
import io.github.dsheirer.module.decode.nxdn.DecodeConfigNXDN;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import io.github.dsheirer.preference.PreferenceType;
import io.github.dsheirer.stats.StatsWebNavigationState;
import java.awt.Component;
import java.awt.Container;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JMenuItem;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class ChannelWebLinkPanelTest
{
    @Test
    void opensSiteAndSystemPagesWithStableOwnerGuidAndGatesActivityHistory() throws Exception
    {
        String ownerGuid = UUID.randomUUID().toString();
        String rowGuid = UUID.randomUUID().toString();
        Channel owner = channel("Boston", "Metro", "Site 12", ownerGuid);
        Channel row = channel("Traffic", "Other", "Traffic", rowGuid);
        AtomicReference<StatsWebNavigationState> state = new AtomicReference<>(
            new StatsWebNavigationState(true, 8090, true, false));
        List<URI> opened = new ArrayList<>();
        ChannelWebLinkPanel[] panelReference = new ChannelWebLinkPanel[1];

        SwingUtilities.invokeAndWait(() -> {
            ChannelWebLinkPanel panel = new ChannelWebLinkPanel(state::get, opened::add);
            panelReference[0] = panel;
            panel.receive(new SelectedFrequencyContext(853_987_500L, null, "P25", owner, row, null, null,
                ChannelActivitySelectionScope.SITE, false));
        });
        SwingUtilities.invokeAndWait(() -> {});

        ChannelWebLinkPanel panel = panelReference[0];

        try
        {
            JMenuItem talkgroups = item(panel, ChannelWebLinkPanel.Destination.TOP_TALKGROUPS);
            JMenuItem aliases = item(panel, ChannelWebLinkPanel.Destination.TALKER_ALIASES);
            JMenuItem activity = item(panel, ChannelWebLinkPanel.Destination.ACTIVITY_LOG);
            assertTrue(talkgroups.isEnabled());
            assertTrue(aliases.isEnabled());
            assertFalse(activity.isEnabled());
            assertFalse(contains(panel, JTextArea.class));
            assertFalse(contains(panel, JScrollPane.class));
            assertTrue(panel.isGroupVisible(ChannelWebLinkPanel.LinkScope.SITE));
            assertTrue(panel.isSystemGroupVisible());
            assertFalse(panel.isGroupVisible(ChannelWebLinkPanel.LinkScope.CONVENTIONAL));

            for(ChannelWebLinkPanel.Destination destination: List.of(
                ChannelWebLinkPanel.Destination.SITE_INFO, ChannelWebLinkPanel.Destination.TOP_TALKGROUPS,
                ChannelWebLinkPanel.Destination.CHANNELS, ChannelWebLinkPanel.Destination.QUALITY,
                ChannelWebLinkPanel.Destination.NEIGHBORS, ChannelWebLinkPanel.Destination.BAND_PLAN,
                ChannelWebLinkPanel.Destination.PATCHES, ChannelWebLinkPanel.Destination.ACTIVITY_LOG,
                ChannelWebLinkPanel.Destination.SYSTEM_OVERVIEW, ChannelWebLinkPanel.Destination.TALKER_ALIASES))
            {
                assertTrue(item(panel, destination).isVisible(), destination + " should preserve P25 visibility");
            }

            SwingUtilities.invokeAndWait(() -> {
                talkgroups.doClick();
                aliases.doClick();
            });

            assertEquals("http://127.0.0.1:8090/?view=site&guid=" + ownerGuid + "&tab=talkgroups",
                opened.get(0).toString());
            assertEquals("http://127.0.0.1:8090/?view=system&guid=" + ownerGuid + "&tab=talker-aliases",
                opened.get(1).toString());

            state.set(new StatsWebNavigationState(true, 8090, true, true));
            panel.preferenceUpdated(PreferenceType.APPLICATION);
            SwingUtilities.invokeAndWait(() -> {});
            assertTrue(activity.isEnabled());
            SwingUtilities.invokeAndWait(activity::doClick);
            assertEquals("http://127.0.0.1:8090/?view=site&guid=" + ownerGuid + "&tab=activity",
                opened.get(2).toString());
        }
        finally
        {
            SwingUtilities.invokeAndWait(panel::dispose);
        }
    }

    @Test
    void trunkedDmrOffersOnlyProtocolNeutralSitePages() throws Exception
    {
        String guid = UUID.randomUUID().toString();
        Channel dmr = new Channel("DMR Site");
        dmr.setSystem("County DMR");
        dmr.setSite("North");
        dmr.setRadresGuid(guid);
        DecodeConfigDMR configuration = new DecodeConfigDMR();
        configuration.setChannelMode(DMRChannelMode.TRUNKED);
        dmr.setDecodeConfiguration(configuration);
        AtomicReference<StatsWebNavigationState> state = new AtomicReference<>(
            new StatsWebNavigationState(true, 8090, true, true));
        List<URI> opened = new ArrayList<>();
        ChannelWebLinkPanel[] panelReference = new ChannelWebLinkPanel[1];

        SwingUtilities.invokeAndWait(() -> {
            ChannelWebLinkPanel panel = new ChannelWebLinkPanel(state::get, opened::add);
            panelReference[0] = panel;
            panel.receive(new SelectedFrequencyContext(451_012_500L, 1, "DMR", dmr, dmr, null, null,
                ChannelActivitySelectionScope.SITE, false));
        });
        SwingUtilities.invokeAndWait(() -> {});

        ChannelWebLinkPanel panel = panelReference[0];

        try
        {
            assertProtocolNeutralSiteDestinations(panel);
            assertFalse(panel.isSystemGroupVisible());

            List<ChannelWebLinkPanel.Destination> destinations = List.of(
                ChannelWebLinkPanel.Destination.SITE_INFO, ChannelWebLinkPanel.Destination.CHANNELS,
                ChannelWebLinkPanel.Destination.QUALITY, ChannelWebLinkPanel.Destination.NEIGHBORS);

            for(ChannelWebLinkPanel.Destination destination: destinations)
            {
                SwingUtilities.invokeAndWait(item(panel, destination)::doClick);
            }

            assertEquals(List.of(
                "http://127.0.0.1:8090/?view=site&guid=" + guid + "&tab=info",
                "http://127.0.0.1:8090/?view=site&guid=" + guid + "&tab=channels",
                "http://127.0.0.1:8090/?view=site&guid=" + guid + "&tab=quality",
                "http://127.0.0.1:8090/?view=site&guid=" + guid + "&tab=neighbors"),
                opened.stream().map(URI::toString).toList());

            SwingUtilities.invokeAndWait(item(panel, ChannelWebLinkPanel.Destination.PATCHES)::doClick);
            assertEquals(4, opened.size());

            SwingUtilities.invokeAndWait(() -> panel.receive(new SelectedFrequencyContext(451_025_000L, 2,
                "DMR", dmr, dmr, null, null, ChannelActivitySelectionScope.EXACT_FREQUENCY, false)));
            SwingUtilities.invokeAndWait(() -> {});
            assertProtocolNeutralSiteDestinations(panel);
        }
        finally
        {
            SwingUtilities.invokeAndWait(panel::dispose);
        }
    }

    @Test
    void trunkedSiteWithoutGuidExplainsWhyLinksAreUnavailable() throws Exception
    {
        Channel dmr = new Channel("DMR Site");
        DecodeConfigDMR configuration = new DecodeConfigDMR();
        configuration.setChannelMode(DMRChannelMode.TRUNKED);
        dmr.setDecodeConfiguration(configuration);
        AtomicReference<StatsWebNavigationState> state = new AtomicReference<>(
            new StatsWebNavigationState(true, 8090, true, true));
        ChannelWebLinkPanel[] panelReference = new ChannelWebLinkPanel[1];

        SwingUtilities.invokeAndWait(() -> {
            ChannelWebLinkPanel panel = new ChannelWebLinkPanel(state::get, uri -> {});
            panelReference[0] = panel;
            panel.receive(new SelectedFrequencyContext(451_012_500L, 1, "DMR", dmr, dmr, null, null,
                ChannelActivitySelectionScope.SITE, false));
        });
        SwingUtilities.invokeAndWait(() -> {});

        ChannelWebLinkPanel panel = panelReference[0];

        try
        {
            assertFalse(panel.isGroupVisible(ChannelWebLinkPanel.LinkScope.SITE));
            assertEquals("The selected channel does not have a site GUID. Site web pages are unavailable.",
                panel.getMessageText());
        }
        finally
        {
            SwingUtilities.invokeAndWait(panel::dispose);
        }
    }

    @Test
    void conventionalDmrIsNeverPromotedToSiteNavigation() throws Exception
    {
        Channel dmr = new Channel("DMR Conventional");
        dmr.setRadresGuid(UUID.randomUUID().toString());
        DecodeConfigDMR configuration = new DecodeConfigDMR();
        configuration.setChannelMode(DMRChannelMode.CONVENTIONAL);
        dmr.setDecodeConfiguration(configuration);
        AtomicReference<StatsWebNavigationState> state = new AtomicReference<>(
            new StatsWebNavigationState(true, 8090, true, true));
        List<URI> opened = new ArrayList<>();
        ChannelWebLinkPanel[] panelReference = new ChannelWebLinkPanel[1];

        SwingUtilities.invokeAndWait(() -> {
            ChannelWebLinkPanel panel = new ChannelWebLinkPanel(state::get, opened::add);
            panelReference[0] = panel;
            /*
             * Even a stale site-shaped selection must not override the channel's explicit conventional mode.
             */
            panel.receive(new SelectedFrequencyContext(154_452_500L, 1, "DMR", dmr, dmr, null, null,
                ChannelActivitySelectionScope.SITE, false));
        });
        SwingUtilities.invokeAndWait(() -> {});

        ChannelWebLinkPanel panel = panelReference[0];

        try
        {
            assertFalse(panel.isGroupVisible(ChannelWebLinkPanel.LinkScope.SITE));
            assertFalse(panel.isSystemGroupVisible());
            assertFalse(item(panel, ChannelWebLinkPanel.Destination.SITE_INFO).isVisible());
            assertFalse(item(panel, ChannelWebLinkPanel.Destination.NEIGHBORS).isEnabled());
            SwingUtilities.invokeAndWait(item(panel, ChannelWebLinkPanel.Destination.SITE_INFO)::doClick);
            assertTrue(opened.isEmpty());
        }
        finally
        {
            SwingUtilities.invokeAndWait(panel::dispose);
        }
    }

    @Test
    void nxdnRequiresSystemsTableEvidenceBeforeOfferingSitePages() throws Exception
    {
        String guid = UUID.randomUUID().toString();
        Channel nxdn = new Channel("NXDN Site");
        nxdn.setSystem("County NXDN");
        nxdn.setSite("West");
        nxdn.setRadresGuid(guid);
        nxdn.setDecodeConfiguration(new DecodeConfigNXDN());
        AtomicReference<StatsWebNavigationState> state = new AtomicReference<>(
            new StatsWebNavigationState(true, 8090, true, true));
        List<URI> opened = new ArrayList<>();
        ChannelWebLinkPanel[] panelReference = new ChannelWebLinkPanel[1];

        SwingUtilities.invokeAndWait(() -> {
            ChannelWebLinkPanel panel = new ChannelWebLinkPanel(state::get, opened::add);
            panelReference[0] = panel;
            panel.receive(new SelectedFrequencyContext(460_112_500L, null, "NXDN", null, nxdn, null, null,
                ChannelActivitySelectionScope.EXACT_FREQUENCY, false));
        });
        SwingUtilities.invokeAndWait(() -> {});

        ChannelWebLinkPanel panel = panelReference[0];

        try
        {
            assertFalse(panel.isGroupVisible(ChannelWebLinkPanel.LinkScope.SITE));
            assertFalse(item(panel, ChannelWebLinkPanel.Destination.SITE_INFO).isEnabled());

            SwingUtilities.invokeAndWait(() -> panel.receive(new SelectedFrequencyContext(460_112_500L, null,
                "NXDN", nxdn, nxdn, null, null, ChannelActivitySelectionScope.SITE, false)));
            SwingUtilities.invokeAndWait(() -> {});
            assertProtocolNeutralSiteDestinations(panel);

            /*
             * A non-null owner is supplied only by a Systems table. It remains present for traffic-row selections,
             * whose logical scope is exact frequency rather than the control site's persistent selection.
             */
            SwingUtilities.invokeAndWait(() -> panel.receive(new SelectedFrequencyContext(460_225_000L, null,
                "NXDN", nxdn, nxdn, null, null, ChannelActivitySelectionScope.EXACT_FREQUENCY, false)));
            SwingUtilities.invokeAndWait(() -> {});

            assertProtocolNeutralSiteDestinations(panel);
            assertFalse(panel.isSystemGroupVisible());
            SwingUtilities.invokeAndWait(item(panel, ChannelWebLinkPanel.Destination.CHANNELS)::doClick);
            assertEquals("http://127.0.0.1:8090/?view=site&guid=" + guid + "&tab=channels",
                opened.getFirst().toString());
        }
        finally
        {
            SwingUtilities.invokeAndWait(panel::dispose);
        }
    }

    @Test
    void conventionalSelectionOnlyOffersConventionalPages() throws Exception
    {
        String guid = UUID.randomUUID().toString();
        Channel conventional = new Channel("County Fire");
        conventional.setSystem("County");
        conventional.setSite("Conventional");
        conventional.setRadresGuid(guid);
        conventional.setDecodeConfiguration(new DecodeConfigNBFM());
        AtomicReference<StatsWebNavigationState> state = new AtomicReference<>(
            new StatsWebNavigationState(true, 8090, true, false));
        List<URI> opened = new ArrayList<>();
        ChannelWebLinkPanel[] panelReference = new ChannelWebLinkPanel[1];

        SwingUtilities.invokeAndWait(() -> {
            ChannelWebLinkPanel panel = new ChannelWebLinkPanel(state::get, opened::add);
            panelReference[0] = panel;
            panel.receive(new SelectedFrequencyContext(154_310_000L, null, "NBFM", conventional, conventional,
                null, null, ChannelActivitySelectionScope.EXACT_FREQUENCY, false));
        });
        SwingUtilities.invokeAndWait(() -> {});

        ChannelWebLinkPanel panel = panelReference[0];

        try
        {
            JMenuItem channelInfo = item(panel, ChannelWebLinkPanel.Destination.CONVENTIONAL_INFO);
            JMenuItem channelActivity = item(panel, ChannelWebLinkPanel.Destination.CONVENTIONAL_ACTIVITY);
            JMenuItem siteInfo = item(panel, ChannelWebLinkPanel.Destination.SITE_INFO);
            JMenuItem aliases = item(panel, ChannelWebLinkPanel.Destination.TALKER_ALIASES);
            assertTrue(channelInfo.isEnabled());
            assertFalse(channelActivity.isEnabled());
            assertFalse(siteInfo.isEnabled());
            assertFalse(aliases.isEnabled());
            assertTrue(panel.isGroupVisible(ChannelWebLinkPanel.LinkScope.CONVENTIONAL));
            assertFalse(panel.isGroupVisible(ChannelWebLinkPanel.LinkScope.SITE));

            SwingUtilities.invokeAndWait(channelInfo::doClick);
            assertEquals("http://127.0.0.1:8090/?view=conventional-detail&context=GUID:" + guid + "&tab=info",
                opened.getFirst().toString());

            state.set(new StatsWebNavigationState(true, 8090, true, true));
            panel.preferenceUpdated(PreferenceType.APPLICATION);
            SwingUtilities.invokeAndWait(() -> {});
            assertTrue(channelActivity.isEnabled());
            SwingUtilities.invokeAndWait(channelActivity::doClick);
            assertEquals("http://127.0.0.1:8090/?view=conventional-detail&context=GUID:" + guid +
                "&tab=activity", opened.get(1).toString());
        }
        finally
        {
            SwingUtilities.invokeAndWait(panel::dispose);
        }
    }

    @Test
    void disabledServerHidesContextCardsAndPreventsNavigation() throws Exception
    {
        AtomicReference<StatsWebNavigationState> state = new AtomicReference<>(
            new StatsWebNavigationState(false, 8123, true, true));
        List<URI> opened = new ArrayList<>();
        Channel owner = channel("Test", "System", "Site", UUID.randomUUID().toString());
        ChannelWebLinkPanel[] panelReference = new ChannelWebLinkPanel[1];

        SwingUtilities.invokeAndWait(() -> {
            ChannelWebLinkPanel panel = new ChannelWebLinkPanel(state::get, opened::add);
            panelReference[0] = panel;
            panel.receive(new SelectedFrequencyContext(851_000_000L, null, "P25", owner, owner, null, null,
                ChannelActivitySelectionScope.SITE, false));
        });
        SwingUtilities.invokeAndWait(() -> {});

        ChannelWebLinkPanel panel = panelReference[0];

        try
        {
            JMenuItem siteInfo = item(panel, ChannelWebLinkPanel.Destination.SITE_INFO);
            assertFalse(siteInfo.isEnabled());
            assertFalse(panel.isGroupVisible(ChannelWebLinkPanel.LinkScope.SITE));
            assertFalse(panel.isGroupVisible(ChannelWebLinkPanel.LinkScope.CONVENTIONAL));
            SwingUtilities.invokeAndWait(siteInfo::doClick);
            assertTrue(opened.isEmpty());
        }
        finally
        {
            SwingUtilities.invokeAndWait(panel::dispose);
        }
    }

    private static Channel channel(String name, String system, String site, String guid)
    {
        Channel channel = new Channel(name);
        channel.setSystem(system);
        channel.setSite(site);
        channel.setRadresGuid(guid);
        channel.setDecodeConfiguration(new DecodeConfigP25Phase1());
        return channel;
    }

    private static void assertProtocolNeutralSiteDestinations(ChannelWebLinkPanel panel)
    {
        assertTrue(panel.isGroupVisible(ChannelWebLinkPanel.LinkScope.SITE));

        for(ChannelWebLinkPanel.Destination destination: List.of(ChannelWebLinkPanel.Destination.SITE_INFO,
            ChannelWebLinkPanel.Destination.CHANNELS, ChannelWebLinkPanel.Destination.QUALITY,
            ChannelWebLinkPanel.Destination.NEIGHBORS))
        {
            assertTrue(item(panel, destination).isVisible(), destination + " should be visible");
            assertTrue(item(panel, destination).isEnabled(), destination + " should be enabled");
        }

        for(ChannelWebLinkPanel.Destination destination: List.of(ChannelWebLinkPanel.Destination.TOP_TALKGROUPS,
            ChannelWebLinkPanel.Destination.BAND_PLAN, ChannelWebLinkPanel.Destination.PATCHES,
            ChannelWebLinkPanel.Destination.ACTIVITY_LOG, ChannelWebLinkPanel.Destination.SYSTEM_OVERVIEW,
            ChannelWebLinkPanel.Destination.TALKER_ALIASES))
        {
            assertFalse(item(panel, destination).isVisible(), destination + " should be hidden");
            assertFalse(item(panel, destination).isEnabled(), destination + " should be disabled");
        }
    }

    private static JMenuItem item(ChannelWebLinkPanel panel, ChannelWebLinkPanel.Destination destination)
    {
        JMenuItem item = panel.getDestinationMenuItem(destination);

        if(item == null)
        {
            throw new AssertionError("Menu item not found: " + destination);
        }

        return item;
    }

    private static boolean contains(Container root, Class<? extends Component> type)
    {
        for(Component component: root.getComponents())
        {
            if(type.isInstance(component) || component instanceof Container container && contains(container, type))
            {
                return true;
            }
        }

        return false;
    }
}
