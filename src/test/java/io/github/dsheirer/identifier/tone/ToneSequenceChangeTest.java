/* Copyright (C) 2026 Dennis Sheirer */
package io.github.dsheirer.identifier.tone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dsheirer.alias.id.tone.TonesID;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ToneSequenceChangeTest
{
    @Test
    void nestedToneChangesReachAliasAndRemovedSequencesDetach()
    {
        Tone first = new Tone(AmbeTone.DTMF_1, 4);
        ToneSequence original = new ToneSequence(List.of(first));
        TonesID alias = new TonesID(original);
        AtomicInteger changes = new AtomicInteger();
        alias.addChangeListener(changes::incrementAndGet);
        first.incrementDuration();
        assertEquals(1, changes.get());
        assertEquals(5, first.getDuration());
        ToneSequence replacement = new ToneSequence();
        alias.setToneSequence(replacement);
        assertEquals(2, changes.get());
        first.incrementDuration();
        assertEquals(2, changes.get());
        Tone second = new Tone(AmbeTone.DTMF_2, 6);
        replacement.addTone(second);
        second.setAmbeTone(AmbeTone.DTMF_3);
        assertEquals(4, changes.get());
        assertTrue(alias.isValid());
        replacement.clear();
        assertEquals(5, changes.get());
        assertFalse(alias.isValid());
        second.incrementDuration();
        assertEquals(5, changes.get());
    }

    @Test
    void sharedToneStaysObservedUntilItsLastOccurrenceIsRemoved()
    {
        Tone tone = new Tone(AmbeTone.DTMF_1, 4);
        ToneSequence sequence = new ToneSequence(List.of(tone, tone));
        AtomicInteger changes = new AtomicInteger();
        sequence.addChangeListener(changes::incrementAndGet);
        sequence.removeTone(tone);
        tone.incrementDuration();
        assertEquals(2, changes.get());
        sequence.removeTone(tone);
        tone.incrementDuration();
        assertEquals(3, changes.get());
    }

    @Test
    void serializationAndDurationMatchingRemainUnchanged() throws Exception
    {
        ObjectMapper mapper = new ObjectMapper();
        ToneSequence sequence = mapper.readValue("{\"tones\":[{\"ambeTone\":\"DTMF_1\",\"duration\":4}]}", ToneSequence.class);
        ToneSequence longer = new ToneSequence(List.of(new Tone(AmbeTone.DTMF_1, 5)));
        assertTrue(sequence.isContainedIn(longer));
        assertFalse(longer.isContainedIn(sequence));
        assertEquals(mapper.readTree("{\"tones\":[{\"ambeTone\":\"DTMF_1\",\"duration\":4}]}"),
            mapper.readTree(mapper.writeValueAsString(sequence)));
        ToneSequence copy = sequence.copyOf();
        copy.getTones().getFirst().incrementDuration();
        assertEquals(4, sequence.getTones().getFirst().getDuration());
    }
}
