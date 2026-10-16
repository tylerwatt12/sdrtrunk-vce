/* Copyright (C) 2026 Dennis Sheirer */
package io.github.dsheirer.dsp.squelch;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CtcssSquelchTest
{
    @Test
    void requiresBothCarrierAndCtcssAndFlushesBeforeCallEnd()
    {
        Scenario scenario = new Scenario();
        scenario.feed(800, true, 103.5);
        assertTrue(scenario.squelch.isSquelched());
        assertTrue(scenario.events.isEmpty(), "Wrong CTCSS must produce neither audio nor a call");
        scenario.feed(800, true, 100.0);
        assertFalse(scenario.squelch.isSquelched());
        assertEquals("UNSQUELCH", scenario.events.getFirst());
        scenario.feed(600, true, 0.0);
        assertTrue(scenario.squelch.isSquelched());
        assertEquals("SQUELCH", scenario.events.getLast());
        assertEquals("flush", scenario.events.get(scenario.events.size() - 2));
        long starts = scenario.events.stream().filter("UNSQUELCH"::equals).count();
        scenario.feed(800, false, 100.0);
        assertTrue(scenario.squelch.isSquelched(), "CTCSS alone must not bypass carrier squelch");
        assertEquals(starts, scenario.events.stream().filter("UNSQUELCH"::equals).count());
    }

    @Test
    void monitorBypassesBothGatesAndReleaseReturnsToSelectedTone()
    {
        Scenario scenario = new Scenario();
        scenario.squelch.setSquelchOverride(true);
        scenario.feed(100, false, 0.0);
        assertFalse(scenario.squelch.isSquelched());
        assertTrue(scenario.events.contains("audio"));
        scenario.squelch.setSquelchOverride(false);
        assertTrue(scenario.squelch.isSquelched());
        assertEquals("SQUELCH", scenario.events.getLast());
        scenario.feed(800, true, 100.0);
        assertFalse(scenario.squelch.isSquelched());
        scenario.squelch.reset();
        assertTrue(scenario.squelch.isSquelched());
        scenario.feed(100, true, 100.0);
        assertTrue(scenario.squelch.isSquelched(), "A restart must reacquire receive-tone evidence");
        scenario.feed(500, true, 100.0);
        assertFalse(scenario.squelch.isSquelched());
        scenario.squelch.setSampleRate(25_000.0);
        assertTrue(scenario.squelch.isSquelched(), "A retune/sample-rate change cannot inherit a tone");
    }

    @Test
    void nextCarrierCannotReusePreviousReceiveTone()
    {
        Scenario scenario = new Scenario();
        scenario.feed(800, true, 100.0);
        assertFalse(scenario.squelch.isSquelched());
        scenario.feed(80, false, 100.0);
        assertTrue(scenario.squelch.isSquelched());
        long starts = scenario.events.stream().filter("UNSQUELCH"::equals).count();
        scenario.feed(800, true, 103.5);
        assertTrue(scenario.squelch.isSquelched());
        assertEquals(starts, scenario.events.stream().filter("UNSQUELCH"::equals).count());
    }

    @Test
    void noiseSquelchClosesAndRequiresFreshCtcssAtNextCall()
    {
        CtcssSquelch squelch = new CtcssSquelch(new NoiseSquelch(0.1f, 0.19f, 2, 3), 100.0);
        squelch.setSampleRate(25_000.0);
        List<String> events = new ArrayList<>();
        squelch.setSquelchStateListener(state -> events.add(state.name()));
        squelch.setAudioListener(audio -> {
            if(events.isEmpty() || events.getLast().equals("SQUELCH"))
                fail("Noise-squelch audio must be inside the call lifecycle");
            events.add(audio.length == 0 ? "flush" : "audio");
        });
        java.util.Random random = new java.util.Random(7L);
        for(int buffer = 0; buffer < 40; buffer++)
        {
            float[] audio = new float[500];
            for(int x = 0; x < audio.length; x++)
            {
                double time = (buffer * 500 + x) / 25_000.0;
                audio[x] = (float)(0.1 * Math.sin(2 * Math.PI * 100 * time) +
                    Math.sin(2 * Math.PI * 5000 * time));
            }
            squelch.process(audio, audio, audio);
        }
        assertTrue(squelch.isSquelched(), "A correct receive tone cannot bypass failed noise squelch");
        assertTrue(events.isEmpty(), "Failed primary squelch must produce no call or audio");
        for(int buffer = 0; buffer < 80; buffer++)
        {
            float[] audio = new float[500];
            for(int x = 0; x < audio.length; x++)
                audio[x] = (float)(0.1 * Math.sin(2 * Math.PI * 100 * (buffer * 500 + x) / 25_000.0));
            squelch.process(audio, audio, audio);
        }
        assertFalse(squelch.isSquelched());
        for(int buffer = 0; buffer < 8; buffer++)
        {
            float[] noise = new float[500];
            for(int x = 0; x < noise.length; x++) noise[x] = (float)(random.nextGaussian() * 2.0);
            squelch.process(noise, noise, noise);
        }
        assertTrue(squelch.isSquelched());
        assertEquals("SQUELCH", events.getLast());
        long starts = events.stream().filter("UNSQUELCH"::equals).count();
        for(int buffer = 0; buffer < 40; buffer++)
        {
            float[] audio = new float[500];
            for(int x = 0; x < audio.length; x++)
                audio[x] = (float)(0.1 * Math.sin(2 * Math.PI * 103.5 * (buffer * 500 + x) / 25_000.0));
            squelch.process(audio, audio, audio);
        }
        assertTrue(squelch.isSquelched());
        assertEquals(starts, events.stream().filter("UNSQUELCH"::equals).count());
    }

    private static final class Scenario
    {
        private final CtcssSquelch squelch = new CtcssSquelch(new CarrierSquelch(0.1f, 0.19f, 2, 3), 100.0);
        private final List<String> events = new ArrayList<>();
        private long sample;

        private Scenario()
        {
            squelch.setSampleRate(25_000.0);
            squelch.setSquelchStateListener(state -> events.add(state.name()));
            squelch.setAudioListener(audio -> events.add(audio.length == 0 ? "flush" : "audio"));
        }

        private void feed(int milliseconds, boolean carrier, double frequency)
        {
            for(int buffer = 0; buffer < milliseconds / 20; buffer++)
            {
                float[] audio = new float[500];
                float[] i = new float[500];
                float[] q = new float[500];
                for(int x = 0; x < audio.length; x++)
                {
                    audio[x] = (float)(0.2 + (frequency == 0.0 ? 0.0 :
                        0.1 * Math.sin(2.0 * Math.PI * frequency * sample / 25_000.0)));
                    i[x] = carrier ? 0.2f : 0.0f;
                    sample++;
                }
                squelch.process(audio, i, q);
            }
        }
    }
}
