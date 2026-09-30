package io.github.dsheirer.controller.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.dsheirer.controller.channel.Channel.ChannelType;
import io.github.dsheirer.source.config.SourceConfigTuner;
import io.github.dsheirer.source.config.SourceConfigTunerMultipleFrequency;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChannelProcessingManagerTunerAssignmentTest
{
    @Test
    void trafficAssignmentsKeepDistinctIdsAndTheirSharedSavedParent()
    {
        Channel standard = new Channel("Control", ChannelType.STANDARD);
        standard.setSystem("Regional system");
        standard.setSite("North site");
        String parentId = standard.getConfigurationId();

        Channel firstTraffic = trafficChannel(standard);
        Channel secondTraffic = trafficChannel(standard);
        ChannelProcessingManager.TunerChannelAssignment control =
            ChannelProcessingManager.tunerChannelAssignment(standard, 851_012_500L);
        ChannelProcessingManager.TunerChannelAssignment first =
            ChannelProcessingManager.tunerChannelAssignment(firstTraffic, 852_012_500L);
        ChannelProcessingManager.TunerChannelAssignment second =
            ChannelProcessingManager.tunerChannelAssignment(secondTraffic, 853_012_500L);

        assertEquals(parentId, control.id());
        assertEquals(parentId, control.parentId());
        assertEquals("standard", control.kind());
        assertEquals(851_012_500L, control.frequencyHz());
        assertEquals("Regional system", control.system());
        assertEquals("North site", control.site());
        assertEquals(parentId, first.parentId());
        assertEquals(parentId, second.parentId());
        assertEquals("traffic", first.kind());
        assertEquals("traffic", second.kind());
        assertNotEquals(first.id(), second.id());
        assertNotEquals(parentId, first.id());
        assertEquals(852_012_500L, first.frequencyHz());
        assertEquals(853_012_500L, second.frequencyHz());
        assertEquals("Regional system", first.system());
        assertEquals("North site", first.site());
    }

    @Test
    void actualFrequencyWinsAndRotatingCandidatesNeverImplyAnActiveFrequency()
    {
        Channel single = new Channel("Single");
        SourceConfigTuner fixed = new SourceConfigTuner();
        fixed.setFrequency(155_085_000L);
        single.setSourceConfiguration(fixed);
        assertEquals(155_085_000L,
            ChannelProcessingManager.tunerChannelAssignment(single, 0).frequencyHz());

        Channel rotating = new Channel("Rotating");
        SourceConfigTunerMultipleFrequency multiple = new SourceConfigTunerMultipleFrequency();
        multiple.setFrequencies(List.of(851_012_500L, 852_012_500L));
        rotating.setSourceConfiguration(multiple);
        assertEquals(852_012_500L,
            ChannelProcessingManager.tunerChannelAssignment(rotating, 852_012_500L).frequencyHz());
        assertNull(ChannelProcessingManager.tunerChannelAssignment(rotating, 0).frequencyHz(),
            "a configured candidate cannot stand in for an unknown active rotating frequency");

        multiple.setFrequencies(List.of(853_012_500L));
        assertNull(ChannelProcessingManager.tunerChannelAssignment(rotating, 0).frequencyHz(),
            "even one rotating candidate is not an allocated source during a retune gap");
    }

    private static Channel trafficChannel(Channel parent)
    {
        Channel traffic = new Channel("T-" + parent.getName(), ChannelType.TRAFFIC);
        traffic.setConfigurationId(parent.getConfigurationId());
        traffic.setSystem(parent.getSystem());
        traffic.setSite(parent.getSite());
        return traffic;
    }
}
