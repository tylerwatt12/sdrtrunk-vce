/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.alerts;

import static org.junit.jupiter.api.Assertions.*;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.stats.WebEntityReferences;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AlertActionStorageTest
{
    @TempDir Path directory;
    private static final long NOW = 1_800_000_000_000L;

    @Test void storedActionSurvivesRuleEditsDeletionAndRestartWithoutChangingTapAction() throws Exception
    {
        Path path = directory.resolve("snapshot.sqlite"); SdrTrunkDatabaseStartup.createGlobalDatabase(path);
        var store = new TopicAlertStore(path);
        String id;
        try(var service = new TopicAlertService(store, () -> NOW))
        {
            configure(service, rule(true));
            var context = new AlertAction.Context(WebEntityReferences.channel("00000000-0000-4000-8000-000000000001"), null, null, null,
                WebEntityReferences.identity("talkgroup", "p25:12345:001", "v1-g-12345-001-200"));
            service.observe(new AlertEvent("first", NOW, "call", Map.of(), context)); service.drain();
            var first = items(service).getFirst(); id = (String)first.get("id");
            assertEquals("listen", first.get("click_action"));
            assertEquals("talkgroup", ((AlertAction)first.get("action")).kind());
            service.saveRule(rule(false), service.configuration().revision());
            service.observe(new AlertEvent("second", NOW, "call", Map.of(), context)); service.drain();
            assertTrue(items(service).stream().anyMatch(item -> ((AlertAction)item.get("action")).equals(AlertAction.NONE)));
            service.deleteRule("r", service.configuration().revision());
            assertEquals("talkgroup", ((AlertAction)service.historyItem(id).orElseThrow().get("action")).kind());
        }
        assertEquals("v1-g-12345-001-200", ((AlertAction)new TopicAlertStore(path).historyItem(id).orElseThrow().get("action")).entityRef().get("identity_key"));
    }

    @Test void builtinHealthToggleIsExplicitAndPreservedByUnrelatedConfigurationWrites() throws Exception
    {
        Path path = directory.resolve("builtin.sqlite"); SdrTrunkDatabaseStartup.createGlobalDatabase(path);
        try(var service = new TopicAlertService(new TopicAlertStore(path), () -> NOW))
        {
            configure(service, rule(false));
            var builtin = new AlertTopic(AlertTopic.ADMINISTRATOR_TOPIC_ID, "Administrator alerts", "", true, true, true);
            service.saveTopic(builtin, service.configuration().revision(), true);
            service.saveRule(rule(true), service.configuration().revision());
            service.saveTopic(new AlertTopic("fire", "Renamed", "", true, true, false), service.configuration().revision());
            assertTrue(service.configuration().builtinActionButtonEnabled());
            assertTrue(new TopicAlertStore(path).configuration().builtinActionButtonEnabled());
            assertThrows(IllegalArgumentException.class, () -> service.saveTopic(new AlertTopic("fire", "Fire", "", true, true, false), service.configuration().revision(), true));
            service.observe(new AlertEvent("health-on", NOW, "health", Map.of("state", List.of("opened"), "issue_name", List.of("Tuner lost")))); service.drain();
            assertEquals(AlertAction.LIVE, items(service).stream().filter(item -> item.get("rule_id").equals("builtin-health")).map(item -> (AlertAction)item.get("action")).findFirst().orElseThrow());
            service.saveTopic(builtin, service.configuration().revision(), false);
            service.observe(new AlertEvent("health-off", NOW, "health", Map.of("state", List.of("opened"), "issue_name", List.of("Tuner recovered")))); service.drain();
            assertTrue(items(service).stream().filter(item -> item.get("rule_id").equals("builtin-health")).anyMatch(item -> item.get("action").equals(AlertAction.NONE)));
        }
    }

    private static AlertRule rule(boolean enabled)
    {
        return new AlertRule("r", "Rule", "fire", true, "call", "", "", "", "", "", null, null, null,
            0, "rule", false, "Title", "Body", "listen", enabled);
    }
    private static void configure(TopicAlertService service, AlertRule rule) throws Exception
    {
        service.updateConfiguration(new TopicAlertConfiguration(1, new TopicAlertConfiguration.PushSettings(true, "proxy", "", "VCE"),
            List.of(AlertTopic.administratorAlerts(), new AlertTopic("fire", "Fire", "", true, true, false)), List.of(rule)), 1);
    }
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> items(TopicAlertService service) throws Exception
    {
        return (List<Map<String,Object>>)service.history(null, 100, 0).get("items");
    }
}
