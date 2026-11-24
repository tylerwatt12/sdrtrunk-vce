/* Copyright (C) 2026 Dennis Sheirer */
package io.github.dsheirer.audio.broadcast.broadcastify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.dsheirer.audio.broadcast.BroadcastConfiguration;
import io.github.dsheirer.audio.broadcast.StreamingConfigurationCodec;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BroadcastifyHealthConfigurationTest
{
    private final ObjectMapper mMapper = new ObjectMapper();

    @Test
    void savedDestinationsWithoutTheSettingUseTheDefaultAndKeepItOnRoundTrip() throws Exception
    {
        for(BroadcastifyCallConfiguration original: configurations())
        {
            ObjectNode legacy = (ObjectNode)mMapper.readTree(persist(original));
            assertTrue(legacy.has("healthReports"));
            legacy.remove("healthReports");

            BroadcastifyCallConfiguration restored = restore(legacy.toString());
            assertTrue(restored.isHealthReports());
            assertTrue(restore(persist(restored)).isHealthReports());
            assertEquals(true, StreamingConfigurationCodec.view(restored).settings().get("health_reports"));
        }
    }

    @Test
    void explicitChoicesSurvivePersistenceCopiesAndOtherSettingsEdits() throws Exception
    {
        for(BroadcastifyCallConfiguration original: configurations())
        {
            for(boolean enabled: new boolean[]{false, true})
            {
                original.setHealthReports(enabled);
                assertEquals(enabled, restore(persist(original)).isHealthReports());
                assertEquals(enabled,
                    ((BroadcastifyCallConfiguration)StreamingConfigurationCodec.copy(original)).isHealthReports());
                BroadcastifyCallConfiguration clone =
                    assertInstanceOf(BroadcastifyCallConfiguration.class, original.copyOf());
                assertEquals(original.getClass(), clone.getClass());
                assertEquals(enabled, clone.isHealthReports());

                if(original instanceof BroadcastifyCallSiteConfiguration site)
                {
                    BroadcastifyCallSiteConfiguration clonedSite =
                        assertInstanceOf(BroadcastifyCallSiteConfiguration.class, clone);
                    assertEquals(site.getAliasListId(), clonedSite.getAliasListId());
                    assertEquals(site.getChannelConfigurationId(), clonedSite.getChannelConfigurationId());
                }

                BroadcastifyCallConfiguration edited = assertInstanceOf(BroadcastifyCallConfiguration.class,
                    StreamingConfigurationCodec.apply(original, Map.of("name", "Renamed destination")));
                assertEquals(enabled, edited.isHealthReports());
            }
        }
    }

    @Test
    void theNormalEditorOffersTheEnabledDefaultAndSavesAnExplicitOptOut()
    {
        for(String provider: List.of("BROADCASTIFY_CALL", "BROADCASTIFY_CALL_SITE"))
        {
            BroadcastConfiguration template = StreamingConfigurationCodec.template(provider);
            assertEquals(true, StreamingConfigurationCodec.view(template).settings().get("health_reports"));
            var field = StreamingConfigurationCodec.providers().stream().filter(item -> item.id().equals(provider))
                .findFirst().orElseThrow().fields().stream().filter(item -> item.key().equals("health_reports"))
                .findFirst().orElseThrow();
            assertEquals("boolean", field.type());
            assertEquals("Send node health reports", field.label());
            assertFalse(field.advanced());
            BroadcastifyCallConfiguration edited = assertInstanceOf(BroadcastifyCallConfiguration.class,
                StreamingConfigurationCodec.apply(template, Map.of("name", "Fixture", "health_reports", false)));
            assertFalse(edited.isHealthReports());
        }
    }

    private List<BroadcastifyCallConfiguration> configurations()
    {
        BroadcastifyCallSiteConfiguration site = new BroadcastifyCallSiteConfiguration();
        site.setAliasListId(41);
        site.setChannelConfigurationId("00000000-0000-0000-0000-000000000041");
        return List.of(new BroadcastifyCallConfiguration(), site);
    }

    private String persist(BroadcastConfiguration configuration) throws Exception
    {
        return mMapper.writerFor(BroadcastConfiguration.class).writeValueAsString(configuration);
    }

    private BroadcastifyCallConfiguration restore(String json) throws Exception
    {
        return assertInstanceOf(BroadcastifyCallConfiguration.class,
            mMapper.readValue(json, BroadcastConfiguration.class));
    }
}
