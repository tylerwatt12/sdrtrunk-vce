/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.web.settings;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WebWorkspacePreferencesCodecTest
{
    private static final ObjectMapper JSON = new ObjectMapper();
    private static ObjectNode defaults() throws Exception
    {
        return (ObjectNode)JSON.readTree(WebUserPreferencesCodec.encode(WebUserPreferences.defaults()));
    }
    private static void rejected(Consumer<ObjectNode> edit) throws Exception
    {
        ObjectNode document = defaults(); edit.accept(document);
        String json = document.toString();
        assertThrows(IOException.class, () -> WebUserPreferencesCodec.decode(json), json);
        assertThrows(IOException.class, () -> WebUserPreferencesCodec.decode(json.getBytes(StandardCharsets.UTF_8)));
    }

    @Test void retainsResponsiveVisibilityExplicitNullSelectionFiltersAndIndependentEventChoices() throws Exception
    {
        for(String visibility: List.of("automatic", "collapsed", "expanded"))
        {
            ObjectNode document = defaults(), live = (ObjectNode)document.get("live_workspace");
            live.put("details_panel_percent", visibility.equals("automatic") ? 15 : 65);
            live.put("details_visibility", visibility); live.put("details_active_tab", "messages");
            live.put("picker_collapsed", true);
            if(!visibility.equals("automatic")) live.put("active_channel_table_id", "named-channel-table");
            ObjectNode filters = (ObjectNode)live.get("event_filters");
            filters.putArray("excluded_leaf_keys").add("CALL:grant").add("COMMAND:check");
            filters.putArray("excluded_timeslots").add("1").add("2");
            filters.putArray("excluded_validity").add("invalid"); filters.put("search", "Dispatch");
            ((ObjectNode)document.at("/p25_visualizer/events/emergency")).put("highlight", false).put("auto_zoom", false);
            ((ObjectNode)document.at("/p25_visualizer/events/call")).put("auto_zoom", true);
            ((ObjectNode)document.get("tuner")).put("fft_auto_range_on_retune", false);
            WebUserPreferences decoded = WebUserPreferencesCodec.decode(document.toString());
            assertEquals(document, JSON.readTree(WebUserPreferencesCodec.encode(decoded)));
            assertEquals("Dispatch", decoded.liveWorkspace().eventFilters().search());
            assertTrue(decoded.p25Visualizer().events().get("call").autoZoom());
            assertTrue(decoded.tuner().waterfallAutoRangeOnRetune());
        }
    }

    @Test void refusesMissingNullUnknownCoercedOrOutOfRangeWorkspaceFields() throws Exception
    {
        for(String key: List.of("live_workspace", "p25_visualizer"))
        {
            rejected(document -> document.remove(key)); rejected(document -> document.putNull(key));
        }
        for(String key: List.of("details_panel_percent", "details_visibility", "details_active_tab", "picker_collapsed",
            "active_channel_table_id", "event_filters", "message_filters"))
            rejected(document -> ((ObjectNode)document.get("live_workspace")).remove(key));
        rejected(document -> ((ObjectNode)document.get("live_workspace")).put("extra", true));
        for(int percent: new int[]{14, 66}) rejected(document -> ((ObjectNode)document.get("live_workspace")).put("details_panel_percent", percent));
        rejected(document -> ((ObjectNode)document.get("live_workspace")).put("details_panel_percent", 25.5));
        rejected(document -> ((ObjectNode)document.get("live_workspace")).put("picker_collapsed", "false"));
        rejected(document -> ((ObjectNode)document.get("live_workspace")).put("details_visibility", "auto"));
        rejected(document -> ((ObjectNode)document.get("live_workspace")).put("details_active_tab", "unknown"));
        rejected(document -> ((ObjectNode)document.get("live_workspace")).put("active_channel_table_id", "x".repeat(257)));
        rejected(document -> ((ObjectNode)document.get("live_workspace")).putNull("event_filters"));
    }

    @Test void boundsFiltersWithoutScalarCoercionOrDuplicateValues() throws Exception
    {
        ObjectNode accepted = defaults(), filters = (ObjectNode)accepted.at("/live_workspace/event_filters");
        for(int i = 0; i < 256; i++) filters.withArray("excluded_leaf_keys").add("key-" + i);
        filters.put("search", "x".repeat(256));
        assertEquals(256, WebUserPreferencesCodec.decode(accepted.toString()).liveWorkspace().eventFilters().excludedLeafKeys().size());
        for(String key: List.of("excluded_leaf_keys", "excluded_timeslots", "excluded_validity"))
        {
            rejected(document -> ((ObjectNode)document.at("/live_workspace/event_filters")).putArray(key).add("valid").add("valid"));
            rejected(document -> ((ObjectNode)document.at("/live_workspace/event_filters")).putArray(key).add(1));
        }
        rejected(document -> ((ObjectNode)document.at("/live_workspace/event_filters")).putArray("excluded_timeslots").add("x".repeat(65)));
        rejected(document -> ((ObjectNode)document.at("/live_workspace/event_filters")).putArray("excluded_validity").add("unknown"));
        rejected(document -> ((ObjectNode)document.at("/live_workspace/event_filters")).put("search", "x".repeat(257)));
        rejected(document -> { for(int i = 0; i < 257; i++) ((ObjectNode)document.at("/live_workspace/event_filters")).withArray("excluded_leaf_keys").add("key-" + i); });
    }

    @Test void enforcesExactP25CategoriesBooleanChoicesAndTunerFlags() throws Exception
    {
        rejected(document -> ((ObjectNode)document.at("/p25_visualizer/events")).remove("call"));
        rejected(document -> ((ObjectNode)document.at("/p25_visualizer/events")).putObject("unknown"));
        rejected(document -> ((ObjectNode)document.at("/p25_visualizer/events/call")).put("highlight", "true"));
        rejected(document -> ((ObjectNode)document.at("/p25_visualizer/events/call")).put("auto_zoom", 1));
        rejected(document -> ((ObjectNode)document.at("/p25_visualizer/events/call")).remove("highlight"));
        for(String key: List.of("fft_auto_range_on_retune", "waterfall_auto_range_on_retune"))
        {
            rejected(document -> ((ObjectNode)document.get("tuner")).remove(key));
            rejected(document -> ((ObjectNode)document.get("tuner")).put(key, "true"));
        }
        String json = defaults().toString().replace("\"details_visibility\":\"automatic\"",
            "\"details_visibility\":\"automatic\",\"details_visibility\":\"expanded\"");
        assertThrows(IOException.class, () -> WebUserPreferencesCodec.decode(json));
    }
}
