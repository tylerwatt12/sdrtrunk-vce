/* Copyright (C) 2026. Licensed under GPL-3.0-or-later. */
package io.github.dsheirer.audio.enhance;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CancellationException;
import org.jtransforms.fft.FloatFFT_1D;

/** Fixed version-one architecture, using per-call FFT/CNN/overlap-add workspaces. */
final class WholeCallEnhancer
{
    private static final int CHANNELS = 48;
    private static final int[] DILATIONS = {1,2,4,8};
    private static final float SILENCE = 1e-5f;
    private static final float LOG_TWO = (float)Math.log(2);
    private static final float LOG_FOUR = (float)Math.log(4);

    /** Arrays belong exclusively to the validated loader and are never exposed or mutated. */
    static final class Model
    {
        private final float gain;
        private final Map<String, float[]> tensors;

        Model(float gain, Map<String, float[]> tensors)
        {
            this.gain = gain;
            this.tensors = Map.copyOf(tensors);
        }

        private float[] tensor(String name) { return tensors.get(name); }
    }

    private WholeCallEnhancer() {}

    static float[] enhance(float[] raw, Model model)
    {
        float[] calibrated = new float[raw.length];
        for(int i = 0; i < raw.length; i++) calibrated[i] = raw[i] * model.gain;
        float[] low = enhance8k(calibrated, model);
        float[] base = upsample(low, model.tensor("constants.upsample"));
        float[] result = highband(low, base, model);
        for(int i = 0; i < result.length; i++)
        {
            result[i] /= model.gain;
            if(!Float.isFinite(result[i])) throw new IllegalStateException("Model produced non-finite audio");
        }
        return result;
    }

    static float[] enhance8k(float[] input, Model model)
    {
        int length = input.length;
        float[] values = new float[length];
        float[] norm = new float[length];
        boolean[] active = new boolean[length];
        float[] window = model.tensor("constants.hann256");
        Network network = new Network(model, "low", 129);
        FloatFFT_1D fft = new FloatFFT_1D(256);
        float[] complex = new float[512];
        float[] features = new float[129];
        float[] logits = new float[129];
        int frames = frameCount(length, 128);
        for(int frame = 0; frame < frames; frame++)
        {
            checkInterrupted(frame);
            int start = frame * 128 - 128;
            float rms = fillAnalysis(input, start, window, complex);
            fft.realForwardFull(complex);
            magnitudes(complex, features);
            network.process(features, logits);
            boolean silent = rms <= SILENCE;
            for(int frequency = 0; frequency < 129; frequency++)
            {
                float logit = (float)Math.tanh(logits[frequency]);
                float gain = silent ? 1 : (float)Math.exp(logit * (logit >= 0 ? LOG_TWO : LOG_FOUR));
                complex[frequency * 2] *= gain;
                complex[frequency * 2 + 1] *= gain;
            }
            inverse(fft, complex, 256);
            overlapAdd(complex, start, window, values, norm);
            if(!silent)
            {
                for(int i = 0; i < 256; i++)
                {
                    int sample = start + i;
                    if(sample >= 0 && sample < length && window[i] != 0) active[sample] = true;
                }
            }
        }
        for(int i = 0; i < length; i++) values[i] = active[i] ? values[i] / Math.max(norm[i], 1e-8f) : input[i];
        return values;
    }

    static float[] upsample(float[] low, float[] coefficients)
    {
        int length = low.length * 2;
        float[] result = new float[length];
        int pad = coefficients.length / 2;
        // Zeros at odd indices are omitted, with the same centered zero padding as torch.conv1d.
        for(int sample = 0; sample < length; sample++)
        {
            checkInterrupted(sample);
            float sum = 0;
            int firstTap = (sample - pad) & 1;
            for(int tap = firstTap; tap < coefficients.length; tap += 2)
            {
                int index = sample + tap - pad;
                if(index >= 0 && index < length) sum += low[index / 2] * coefficients[tap];
            }
            result[sample] = sum;
        }
        return result;
    }

    private static float[] highband(float[] low, float[] base, Model model)
    {
        float[] values = new float[base.length];
        float[] norm = new float[base.length];
        float[] windowLow = model.tensor("constants.hann256");
        float[] window = model.tensor("constants.hann512");
        float[] mask = model.tensor("high.mask");
        Network network = new Network(model, "high", 514);
        FloatFFT_1D lowFFT = new FloatFFT_1D(256);
        FloatFFT_1D fft = new FloatFFT_1D(512);
        float[] lowComplex = new float[512];
        float[] harmonic = new float[1024];
        float[] noise = new float[1024];
        float[] features = new float[129];
        float[] gains = new float[514];
        int frames = frameCount(low.length, 128);
        float scaleConstant = (float)Math.sqrt(0.375 / 2);
        for(int frame = 0; frame < frames; frame++)
        {
            checkInterrupted(frame);
            int startLow = frame * 128 - 128;
            int start = frame * 256 - 256;
            float rms = fillAnalysis(low, startLow, windowLow, lowComplex);
            lowFFT.realForwardFull(lowComplex);
            magnitudes(lowComplex, features);
            network.process(features, gains);
            Arrays.fill(harmonic, 0);
            Arrays.fill(noise, 0);
            for(int i = 0; i < 512; i++)
            {
                int index = start + i;
                float value = index >= 0 && index < base.length ? base[index] : 0;
                harmonic[i] = (float)Math.tanh(6 * value / Math.max(rms, SILENCE)) * window[i];
                noise[i] = index >= 0 && index < base.length ? deterministicNoise(index) * window[i] : 0;
            }
            fft.realForwardFull(harmonic);
            fft.realForwardFull(noise);
            float harmonicNorm = maskedNorm(harmonic, mask);
            float noiseNorm = maskedNorm(noise, mask);
            float sum = 0;
            for(int frequency = 0; frequency < 257; frequency++)
            {
                float noiseGain = sigmoid(gains[frequency]);
                float harmonicGain = sigmoid(gains[frequency + 257]);
                for(int component = 0; component < 2; component++)
                {
                    int i = frequency * 2 + component;
                    harmonic[i] = (noise[i] / noiseNorm * noiseGain + harmonic[i] / harmonicNorm * harmonicGain) * mask[frequency];
                    sum += harmonic[i] * harmonic[i];
                }
            }
            float candidateNorm = Math.max((float)Math.sqrt(sum), 1);
            float scale = rms > SILENCE ? rms * 512 * scaleConstant * 0.35f : 0;
            for(int i = 0; i < 514; i++) harmonic[i] = harmonic[i] / candidateNorm * scale;
            inverse(fft, harmonic, 512);
            overlapAdd(harmonic, start, window, values, norm);
        }
        for(int i = 0; i < values.length; i++) values[i] /= Math.max(norm[i], 1e-8f);
        // Reuse the norm workspace for the centered FIR output, then add the frozen base once.
        filter(values, model.tensor("high.bandpass"), norm);
        for(int i = 0; i < norm.length; i++) norm[i] += base[i];
        return norm;
    }

    private static int frameCount(int length, int hop) { return (length + hop - 1) / hop + 2; }

    private static float fillAnalysis(float[] audio, int start, float[] window, float[] complex)
    {
        Arrays.fill(complex, 0);
        float squareSum = 0;
        for(int i = 0; i < window.length; i++)
        {
            int index = start + i;
            float sample = index >= 0 && index < audio.length ? audio[index] : 0;
            squareSum += sample * sample;
            complex[i] = sample * window[i];
        }
        return (float)Math.sqrt(squareSum / window.length);
    }

    private static void magnitudes(float[] complex, float[] features)
    {
        for(int i = 0; i < features.length; i++)
        {
            float real = complex[2 * i];
            float imaginary = complex[2 * i + 1];
            features[i] = (float)Math.log1p((float)Math.sqrt(real * real + imaginary * imaginary) * 2);
        }
    }

    private static float maskedNorm(float[] complex, float[] mask)
    {
        float sum = 0;
        for(int frequency = 0; frequency < mask.length; frequency++)
        {
            complex[frequency * 2] *= mask[frequency];
            complex[frequency * 2 + 1] *= mask[frequency];
            sum += complex[frequency * 2] * complex[frequency * 2] + complex[frequency * 2 + 1] * complex[frequency * 2 + 1];
        }
        return Math.max((float)Math.sqrt(sum), SILENCE);
    }

    private static void inverse(FloatFFT_1D fft, float[] complex, int size)
    {
        // rFFT endpoints are real; rebuild the Hermitian half for a normalized inverse.
        complex[1] = 0;
        complex[size + 1] = 0;
        for(int frequency = 1; frequency < size / 2; frequency++)
        {
            complex[(size - frequency) * 2] = complex[frequency * 2];
            complex[(size - frequency) * 2 + 1] = -complex[frequency * 2 + 1];
        }
        fft.complexInverse(complex, true);
    }

    private static void overlapAdd(float[] complex, int start, float[] window, float[] values, float[] norm)
    {
        for(int i = 0; i < window.length; i++)
        {
            int sample = start + i;
            if(sample >= 0 && sample < values.length)
            {
                values[sample] += complex[i * 2] * window[i];
                norm[sample] += window[i] * window[i];
            }
        }
    }

    private static void filter(float[] input, float[] coefficients, float[] output)
    {
        int pad = coefficients.length / 2;
        for(int sample = 0; sample < input.length; sample++)
        {
            checkInterrupted(sample);
            float sum = 0;
            for(int tap = 0; tap < coefficients.length; tap++)
            {
                int index = sample + tap - pad;
                if(index >= 0 && index < input.length) sum += input[index] * coefficients[tap];
            }
            output[sample] = sum;
        }
    }

    static float deterministicNoise(int sample)
    {
        long value = ((sample + 1729L) * 1664525 + 1013904223) & 0xffffffffL;
        value ^= value >>> 13;
        value = (value * 1274126177) & 0xffffffffL;
        value ^= value >>> 16;
        return (float)((value / 4294967295.0) * 2 - 1) * (float)Math.sqrt(3);
    }

    private static float sigmoid(float value) { return 1.0f / (1.0f + (float)Math.exp(-value)); }
    private static float silu(float value) { return value * sigmoid(value); }

    private static void checkInterrupted(int step)
    {
        if((step & 127) == 0 && Thread.currentThread().isInterrupted())
            throw new CancellationException("Whole-call enhancement was cancelled");
    }

    /** Per-call 30-frame histories preserve the causal CNN across the entire call. */
    private static final class Network
    {
        private final Model mModel;
        private final String mPrefix;
        private final float[][][] mHistory = new float[4][][];
        private final float[] mFeatures = new float[CHANNELS];
        private final float[] mUpdate = new float[CHANNELS];
        private final float[] mMix = new float[CHANNELS];
        private final float[] mActivated = new float[CHANNELS];
        private final int mOutputs;
        private int mFrame;

        Network(Model model, String prefix, int outputs)
        {
            mModel = model;
            mPrefix = prefix;
            mOutputs = outputs;
            for(int block = 0; block < 4; block++) mHistory[block] = new float[2 * DILATIONS[block] + 1][CHANNELS];
        }

        void process(float[] input, float[] output)
        {
            linear(input, mFeatures, mModel.tensor(mPrefix + ".input.weight"), mModel.tensor(mPrefix + ".input.bias"));
            for(int i = 0; i < CHANNELS; i++) mFeatures[i] = silu(mFeatures[i]);
            for(int block = 0; block < 4; block++)
            {
                int dilation = DILATIONS[block];
                float[][] history = mHistory[block];
                System.arraycopy(mFeatures, 0, history[mFrame % history.length], 0, CHANNELS);
                String prefix = mPrefix + ".blocks." + block;
                float[] weight = mModel.tensor(prefix + ".conv.weight");
                float[] bias = mModel.tensor(prefix + ".conv.bias");
                for(int out = 0; out < CHANNELS; out++)
                {
                    float value = bias[out];
                    for(int in = 0; in < CHANNELS; in++)
                    {
                        int offset = (out * CHANNELS + in) * 3;
                        for(int tap = 0; tap < 3; tap++)
                        {
                            int frame = mFrame - (2 - tap) * dilation;
                            if(frame >= 0) value += weight[offset + tap] * history[frame % history.length][in];
                        }
                    }
                    mUpdate[out] = silu(value);
                }
                linear(mUpdate, mMix, mModel.tensor(prefix + ".mix.weight"), mModel.tensor(prefix + ".mix.bias"));
                for(int i = 0; i < CHANNELS; i++) mFeatures[i] += mMix[i] * 0.2f;
            }
            for(int i = 0; i < CHANNELS; i++) mActivated[i] = silu(mFeatures[i]);
            linear(mActivated, output, mModel.tensor(mPrefix + ".output.weight"), mModel.tensor(mPrefix + ".output.bias"));
            if(output.length != mOutputs) throw new IllegalStateException("Invalid output workspace");
            mFrame++;
        }

        private static void linear(float[] input, float[] output, float[] weights, float[] bias)
        {
            for(int out = 0; out < output.length; out++)
            {
                float value = bias[out];
                int offset = out * input.length;
                for(int in = 0; in < input.length; in++) value += input[in] * weights[offset + in];
                output[out] = value;
            }
        }
    }
}
