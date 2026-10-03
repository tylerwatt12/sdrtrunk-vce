package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class SpectrumPeakDetectorTest
{
    private static final long CENTER = 851_000_000;
    private static final long RATE = 2_000_000;

    @Test void impossibilityBoundIncludesUnseenTracksAndExactPersistenceThresholds()
    {
        for(int total = 0; total <= 30; total++)
        {
            for(int count = 0; count <= total; count++)
            {
                SpectrumPeakDetector detector = detector();
                for(int index = 0; index < total; index++)
                {
                    float[] bins = noise();
                    if(index < count) signal(bins, CENTER + 300_000, -35);
                    detector.receive(bins);
                }
                for(int future = 0; future <= 40; future++)
                {
                    boolean possible = count + future >= 6 && 4L * (count + future) >= 3L * (total + future);
                    assertEquals(total >= 6 && !possible, detector.cannotBecomePersistent(future),
                        "frames=" + total + " count=" + count + " future=" + future);
                }
                assertFalse(detector.cannotBecomePersistent(-1));
                assertFalse(detector.cannotBecomePersistent(Integer.MAX_VALUE));
            }
        }
    }

    @Test void steadyPeakSurvivesSmallDriftButIntermittentVoiceDoesNot()
    {
        SpectrumPeakDetector detector = detector();
        for(int frame = 0; frame < 12; frame++)
        {
            float[] bins = noise();
            signal(bins, 851_300_000 + frame % 2 * 1_000, -35);
            if(frame % 3 == 0) signal(bins, 850_500_000, -25);
            detector.receive(bins);
        }
        assertEquals(1, detector.peaks().size());
        var peak = detector.peaks().getFirst();
        assertTrue(Math.abs(peak.frequencyHz() - 851_300_000) <= 1_250);
        assertEquals(12, peak.observations());
        assertEquals(-35, peak.powerDbfs(), 0.1);
        assertEquals(65, peak.prominenceDb(), 0.1);
    }

    @Test void ignoresCenterArtifactsEdgesWideSignalsAndSingleBinSpurs()
    {
        SpectrumPeakDetector detector = detector();
        for(int frame = 0; frame < 10; frame++)
        {
            float[] bins = noise();
            signal(bins, CENTER, -10);
            signal(bins, CENTER + 899_000, -20);
            Arrays.fill(bins, 2200, 2500, -15);
            bins[3100] = -10;
            detector.receive(bins);
        }
        assertTrue(detector.peaks().isEmpty());
    }

    @Test void rejectsTooFewFramesAndQuietSpectrum()
    {
        SpectrumPeakDetector detector = detector();
        for(int frame = 0; frame < 5; frame++)
        {
            float[] bins = noise();
            signal(bins, CENTER + 300_000, -30);
            detector.receive(bins);
        }
        assertTrue(detector.peaks().isEmpty());
        SpectrumPeakDetector quiet = detector();
        for(int frame = 0; frame < 20; frame++) quiet.receive(noise());
        assertTrue(quiet.peaks().isEmpty());
    }

    @Test void fixedRecordingWindowRetainsCenteredCarrierButStillRejectsSpursAndEdges()
    {
        SpectrumPeakDetector detector = new SpectrumPeakDetector(CENTER, RATE, 1_800_000, 0, false);
        for(int frame = 0; frame < 10; frame++)
        {
            float[] bins = noise();
            signal(bins, CENTER, -35);
            signal(bins, CENTER + 899_000, -20);
            bins[3100] = -10;
            detector.receive(bins);
        }
        assertEquals(1, detector.peaks().size());
        assertTrue(Math.abs(detector.peaks().getFirst().frequencyHz() - CENTER) <= 1_250);
        assertEquals(10, detector.peaks().getFirst().observations());
    }

    @Test void readsExistingBoundedDiagnosticFramesAndRejectsOtherTuningWindows()
    {
        SpectrumPeakDetector detector = detector();
        float[] bins = noise();
        signal(bins, CENTER + 300_000, -35);
        for(int frame = 0; frame < 10; frame++)
        {
            detector.receive(DiagnosticStreamFrame.tunerFft(1, frame, frame, CENTER + RATE, RATE,
                bins.length, 8, bins));
            detector.receive(DiagnosticStreamFrame.tunerFft(1, frame, frame, CENTER, RATE,
                bins.length, 8, bins));
        }
        assertEquals(10, detector.frames());
        assertEquals(1, detector.peaks().size());
        assertEquals(-35, detector.peaks().getFirst().powerDbfs(), 1);
    }

    private static SpectrumPeakDetector detector() { return new SpectrumPeakDetector(CENTER, RATE, 1_800_000, 0); }
    private static float[] noise() { float[] bins = new float[4096]; Arrays.fill(bins, -100); return bins; }
    private static void signal(float[] bins, long frequency, float power)
    {
        double width = (double)RATE / bins.length;
        for(int bin = 0; bin < bins.length; bin++)
            if(Math.abs(CENTER - RATE / 2.0 + (bin + 0.5) * width - frequency) <= 5_000) bins[bin] = power;
    }
}
