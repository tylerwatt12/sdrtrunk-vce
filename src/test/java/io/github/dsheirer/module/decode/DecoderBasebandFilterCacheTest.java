/*
 * Copyright (C) 2026 Dennis Sheirer
 * Licensed under GPL-3.0-or-later.
 */
package io.github.dsheirer.module.decode;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.dsp.filter.FilterFactory;
import io.github.dsheirer.dsp.filter.fir.FIRFilterSpecification;
import io.github.dsheirer.module.decode.nxdn.DecodeConfigNXDN;
import io.github.dsheirer.module.decode.nxdn.NXDNDecoder;
import io.github.dsheirer.module.decode.nxdn.layer3.type.TransmissionMode;
import io.github.dsheirer.module.decode.p25.phase1.P25P1DecoderC4FM;
import io.github.dsheirer.source.tuner.channel.ChannelSpecification;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class DecoderBasebandFilterCacheTest
{
    @Test
    void concurrentP25DecodersSharePublishedTapsAcrossSampleRates() throws Exception
    {
        List<FilterRequest> requests = new ArrayList<>();

        for(int repeat = 0; repeat < 4; repeat++)
        {
            for(double sampleRate : new double[]{27_017, 31_013})
            {
                requests.add(p25Request(sampleRate));
            }
        }

        assertConcurrentCache(requests);
    }

    @Test
    void concurrentNXDNDecodersKeepModesAndSampleRatesIndependent() throws Exception
    {
        List<FilterRequest> requests = new ArrayList<>();

        for(int repeat = 0; repeat < 2; repeat++)
        {
            for(TransmissionMode mode : TransmissionMode.values())
            {
                //The shared rate requires different M4800/M9600 taps, exposing a cache key that loses the mode.
                double[] sampleRates = mode == TransmissionMode.M9600 ?
                    new double[]{20_011, 30_029} : new double[]{20_011, 15_013};

                for(double sampleRate : sampleRates)
                {
                    requests.add(nxdnRequest(sampleRate, mode));
                }
            }
        }

        assertConcurrentCache(requests);
    }

    @Test
    void rejectedSampleRatesLeaveUsableCachedFiltersUnchanged() throws Exception
    {
        FilterRequest p25 = p25Request(25_000);
        float[] p25Taps = p25.coefficients();
        assertThrows(IllegalArgumentException.class, () ->
            ((P25P1DecoderC4FM)p25.decoder()).setSampleRate(9_600));
        assertSame(p25Taps, p25.coefficients());

        for(TransmissionMode mode : TransmissionMode.values())
        {
            double sampleRate = mode == TransmissionMode.M9600 ? 25_000 : 12_500;
            FilterRequest nxdn = nxdnRequest(sampleRate, mode);
            NXDNDecoder decoder = (NXDNDecoder)nxdn.decoder();
            decoder.setSampleRate(sampleRate);
            float[] nxdnTaps = nxdn.coefficients();
            assertThrows(IllegalArgumentException.class, () -> decoder.setSampleRate(mode.getSymbolRate() * 4.0));
            assertSame(nxdnTaps, nxdn.coefficients());
        }
    }

    private void assertConcurrentCache(List<FilterRequest> requests) throws Exception
    {
        CountDownLatch ready = new CountDownLatch(requests.size());
        CountDownLatch start = new CountDownLatch(1);

        try(var executor = Executors.newFixedThreadPool(requests.size()))
        {
            List<Future<float[]>> results = new ArrayList<>();

            for(FilterRequest request : requests)
            {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    return request.coefficients();
                }));
            }

            try
            {
                assertTrue(ready.await(10, TimeUnit.SECONDS));
            }
            finally
            {
                start.countDown();
            }

            Map<String,float[]> published = new HashMap<>();

            for(int index = 0; index < requests.size(); index++)
            {
                FilterRequest request = requests.get(index);
                float[] actual = results.get(index).get(20, TimeUnit.SECONDS);
                assertNotNull(actual);
                assertTrue(actual.length > 0);

                for(float coefficient : actual)
                {
                    assertTrue(Float.isFinite(coefficient), request.key());
                }

                float[] first = published.putIfAbsent(request.key(), actual);

                if(first != null)
                {
                    assertSame(first, actual, "Concurrent starts must use the same published coefficient array");
                }
                else
                {
                    //Independently design through the public API to verify caching preserves the existing filter.
                    assertArrayEquals(FilterFactory.getTaps(request.specification()), actual, 0.0f, request.key());
                }

                assertSame(actual, request.coefficients(), "Later starts must reuse the published coefficients");
            }
        }
    }

    private FilterRequest p25Request(double sampleRate) throws Exception
    {
        Method getter = P25P1DecoderC4FM.class.getDeclaredMethod("getBasebandFilter", double.class);
        getter.setAccessible(true);
        FIRFilterSpecification specification = FIRFilterSpecification.lowPassBuilder()
            .sampleRate(sampleRate).passBandCutoff(5200).passBandAmplitude(1.0).passBandRipple(0.01)
            .stopBandAmplitude(0.0).stopBandStart(6500).stopBandRipple(0.01).build();
        return new FilterRequest("P25:" + sampleRate, new P25P1DecoderC4FM(25_000), getter,
            new Object[]{sampleRate}, specification);
    }

    private FilterRequest nxdnRequest(double sampleRate, TransmissionMode mode) throws Exception
    {
        DecodeConfigNXDN config = new DecodeConfigNXDN(mode);
        Method getter = NXDNDecoder.class.getDeclaredMethod("getBasebandFilter", double.class, DecodeConfigNXDN.class);
        getter.setAccessible(true);
        ChannelSpecification channel = config.getChannelSpecification();
        FIRFilterSpecification specification = FIRFilterSpecification.lowPassBuilder()
            .sampleRate(sampleRate).passBandCutoff(channel.getPassFrequency())
            .passBandAmplitude(1.0).passBandRipple(0.01).stopBandAmplitude(0.0)
            .stopBandStart(channel.getStopFrequency()).stopBandRipple(0.01).build();
        return new FilterRequest(mode.name() + ":" + sampleRate, new NXDNDecoder(config), getter,
            new Object[]{sampleRate, config}, specification);
    }

    private record FilterRequest(String key, Object decoder, Method getter, Object[] arguments,
                                 FIRFilterSpecification specification)
    {
        private float[] coefficients() throws Exception
        {
            return (float[])getter.invoke(decoder, arguments);
        }
    }
}
