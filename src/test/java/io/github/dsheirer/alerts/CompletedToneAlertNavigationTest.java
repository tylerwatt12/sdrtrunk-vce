/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.alerts;

import static org.junit.jupiter.api.Assertions.*;
import io.github.dsheirer.audio.call.*;
import io.github.dsheirer.audio.tone.CompletedToneSequence;
import io.github.dsheirer.configuration.ChannelConfigurationPolicy.ChannelKind;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CompletedToneAlertNavigationTest
{
    @TempDir Path directory;
    private static final String CHANNEL = "00000000-0000-4000-8000-000000000001";
    private static final String SYSTEM = "p25:12345:001";
    private static final long START = 1_800_000_000_000L;

    @Test void completedToneAlertCapturesGroupForTrunkedCallsAndChannelForConventionalDigitalCalls() throws Exception
    {
        for(var kind: ChannelKind.values())
        {
            Path database = directory.resolve(kind.name() + ".sqlite");
            SdrTrunkDatabaseStartup.createGlobalDatabase(database);
            var rule = new AlertRule("tone", "Dispatch tone", "fire", true, "tone", "", "", "", "", "",
                null, null, null, 0, "rule", false, "Dispatch tone", "Dispatch tones detected", "details", true);
            try(var service = new TopicAlertService(new TopicAlertStore(database), () -> START + 2000))
            {
                service.updateConfiguration(new TopicAlertConfiguration(1,
                    new TopicAlertConfiguration.PushSettings(true, "proxy", "", "VCE"),
                    List.of(AlertTopic.administratorAlerts(), new AlertTopic("fire", "Fire", "", true, true, false)), List.of(rule)), 1);
                var decoder = kind == ChannelKind.TRUNKED ? DecoderType.P25_PHASE1 : DecoderType.P25_CONVENTIONAL;
                var source = new CallLegSource(decoder, CHANNEL, "Dispatch", null, 0, null, null, kind,
                    kind == ChannelKind.TRUNKED, SYSTEM);
                var id = new AudioCallId(12, 1, 0);
                var snapshot = new AudioCallSnapshot(id, null, null,
                    new IdentifierCollection(List.of(APCO25Talkgroup.create(200))), Set.of(), START, START + 2000,
                    1, 0, START, START + 2000, false, true, CallEncryptionState.CLEAR, false, null,
                    VoiceCallQuality.EMPTY, CallLegId.from(id), source, null);
                var sequence = new CompletedToneSequence(snapshot, START, CompletedToneSequence.Source.DECODED_AUDIO,
                    CompletedToneSequence.Method.P25_PCM_AMBE_TONE_V2,
                    List.of(new CompletedToneSequence.Element(List.of(437500), null, 0L, 250, .99)));
                service.receiveCompletedToneSequence(sequence);
                service.drain();
                @SuppressWarnings("unchecked")
                var items = (List<Map<String,Object>>)service.history(null, 100, 0).get("items");
                assertEquals(1, items.size());
                var action = (AlertAction)items.getFirst().get("action");
                assertEquals(kind == ChannelKind.TRUNKED ? "talkgroup" : "channel", action.kind());
                if(kind == ChannelKind.TRUNKED)
                {
                    assertEquals(SYSTEM, action.entityRef().get("radio_system_key"));
                    assertEquals("v1-g-12345-001-200", action.entityRef().get("identity_key"));
                }
                else assertEquals(CHANNEL, action.entityRef().get("key"));
                assertEquals(action, new TopicAlertStore(database).historyItem((String)items.getFirst().get("id"))
                    .orElseThrow().get("action"), "The existing typed action persists without a URL or new destination shape");
            }
        }
    }
}
