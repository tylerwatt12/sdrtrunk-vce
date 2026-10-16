package io.github.dsheirer.audio.tone;

import static org.junit.jupiter.api.Assertions.*;
import io.github.dsheirer.identifier.tone.AmbeTone;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class AnalogDtmfDetectorTest
{
    @Test void recognizesEveryStandardDigitAndFrequencyPairWithUnevenBuffers()
    {
        for(AmbeTone tone: AmbeTone.values())
        {
            if(!tone.name().startsWith("DTMF_")) continue;
            var pair = EncodedToneMetadata.frequencies(tone);
            var result = new AnalogDtmfDetector().detect(blocks(signal(pair.getFirst() / 1000., pair.getLast() / 1000., .25, .25, 2400), 113));
            assertEquals(1, result.size(), tone.name());
            assertEquals(tone.name(), result.getFirst().codecSymbol());
            assertEquals(pair, result.getFirst().frequenciesMilliHertz());
            assertTrue(result.getFirst().durationMilliseconds() >= 50);
        }
    }

    @Test void keepsRepeatedDigitsSeparateAcrossSilenceAndReportsSampleOffsets()
    {
        float[] digit = signal(697,1209,.25,.25,1600);
        float[] samples = new float[4000];
        System.arraycopy(digit,0,samples,0,digit.length);
        System.arraycopy(digit,0,samples,2400,digit.length);
        var result = new AnalogDtmfDetector().detect(blocks(samples,237));
        assertEquals(2,result.size());
        assertEquals("DTMF_1",result.getFirst().codecSymbol());
        assertEquals("DTMF_1",result.getLast().codecSymbol());
        assertEquals(300L,result.getLast().offsetMilliseconds() - result.getFirst().offsetMilliseconds());
    }

    @Test void toleratesModestDriftButRejectsOffFrequencyPairsNoiseTwistAndShortBursts()
    {
        var detector = new AnalogDtmfDetector();
        assertEquals(1,detector.detect(List.of(signal(770 *1.014,1336 * .986,.25,.25,2400))).size());
        assertTrue(detector.detect(List.of(signal(770 *1.05,1336 *1.05,.25,.25,2400))).isEmpty());
        assertTrue(detector.detect(List.of(signal(770,1336,.4,.025,2400))).isEmpty());
        assertTrue(detector.detect(List.of(signal(770,1336,.25,.25,320))).isEmpty());
        assertTrue(detector.detect(List.of(signal(770,0,.25,0,2400))).isEmpty());
        assertTrue(detector.detect(List.of(signal(254.1,0,.25,0,2400))).isEmpty());
        float[] noise = new float[2400];
        Random random = new Random(2026);
        for(int sample=0;sample<noise.length;sample++) noise[sample] = (float)(random.nextGaussian() * .2);
        assertTrue(detector.detect(List.of(noise)).isEmpty());
        noise[0]=Float.NaN;
        assertTrue(detector.detect(List.of(noise)).isEmpty());
    }

    static float[] signal(double first,double second,double firstGain,double secondGain,int length)
    {
        float[] samples=new float[length];
        for(int sample=0;sample<length;sample++) samples[sample]=(float)(firstGain*Math.sin(2*Math.PI*first*sample/8000)+
            secondGain*Math.sin(2*Math.PI*second*sample/8000));
        return samples;
    }
    static List<float[]> blocks(float[] samples,int size)
    {
        List<float[]> blocks=new ArrayList<>();
        for(int start=0;start<samples.length;start+=size) blocks.add(Arrays.copyOfRange(samples,start,Math.min(samples.length,start+size)));
        return blocks;
    }
}
