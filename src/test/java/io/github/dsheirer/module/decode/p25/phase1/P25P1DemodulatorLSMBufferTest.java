package io.github.dsheirer.module.decode.p25.phase1;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.FeedbackDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class P25P1DemodulatorLSMBufferTest
{
    @Test
    void shrinkingAndGrowingBuffersPreserveTheSameSymbols()
    {
        float[] i = new float[4096];
        float[] q = new float[i.length];

        for(int x = 0; x < i.length; x++)
        {
            double phase = 0.09 * x + 0.8 * Math.sin(0.07 * x);
            i[x] = (float)Math.cos(phase);
            q[x] = (float)Math.sin(phase);
        }

        List<Float> expected = new ArrayList<>();
        demodulator(expected).process(i, q);
        List<Float> actual = new ArrayList<>();
        P25P1DemodulatorLSM demodulator = demodulator(actual);
        int offset = 0;

        for(int length: new int[]{1024, 512, 0, 1, 17, 1024, 1518})
        {
            demodulator.process(Arrays.copyOfRange(i, offset, offset + length),
                Arrays.copyOfRange(q, offset, offset + length));
            offset += length;
        }

        assertEquals(i.length, offset);
        assertEquals(expected, actual);
    }

    private static P25P1DemodulatorLSM demodulator(List<Float> symbols)
    {
        FeedbackDecoder feedback = new FeedbackDecoder()
        {
            @Override
            public String getProtocolDescription()
            {
                return "P25 LSM test";
            }

            @Override
            public DecoderType getDecoderType()
            {
                return DecoderType.P25_PHASE1;
            }
        };
        feedback.addSymbolObserver(symbols::add);
        P25P1DemodulatorLSM demodulator = new P25P1DemodulatorLSM(new P25P1MessageFramer(), feedback);
        demodulator.setSamplesPerSymbol(25000.0f / 4800);
        return demodulator;
    }
}
