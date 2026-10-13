/*
 * Copyright (C) 2026
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.dsheirer.audio.codec.mbe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import jmbe.iface.IAudioCodec;
import jmbe.iface.IAudioWithMetadata;
import org.junit.jupiter.api.Test;

public class JmbeVoiceFrameFingerprintTest
{
    @Test
    void olderCodecKeepsAudioWithoutOptionalEvidence()
    {
        LegacyCodec codec = new LegacyCodec();
        assertNull(JmbeVoiceFrameFingerprint.create(codec));
        assertEquals(1.0f, codec.getAudio(new byte[0])[0]);
    }

    @Test
    void bindsAndEnablesEachIndependentCodecOnce()
    {
        CorrectedCodec first = new CorrectedCodec();
        CorrectedCodec replacement = new CorrectedCodec();
        JmbeVoiceFrameFingerprint firstEvidence = JmbeVoiceFrameFingerprint.create(first);
        JmbeVoiceFrameFingerprint replacementEvidence = JmbeVoiceFrameFingerprint.create(replacement);
        assertNotNull(firstEvidence);
        assertNotNull(replacementEvidence);
        assertEquals(1, first.enableCalls);
        assertEquals(1, replacement.enableCalls);
        first.fingerprint = 123L;
        replacement.fingerprint = 456L;
        assertEquals(123L, firstEvidence.get());
        assertEquals(456L, replacementEvidence.get());
        first.reset();
        assertEquals(0L, firstEvidence.get());
        assertEquals(456L, replacementEvidence.get());
    }

    @Test
    void incompatibleReturnTypeDoesNotEnableOptionalWork()
    {
        MalformedCodec codec = new MalformedCodec();
        assertNull(JmbeVoiceFrameFingerprint.create(codec));
        assertFalse(codec.enabled);
        assertEquals(1.0f, codec.getAudio(new byte[0])[0]);
    }

    @Test
    void brokenOptionalGetterIsDisabledWithoutAffectingAudio()
    {
        BrokenCodec codec = new BrokenCodec();
        JmbeVoiceFrameFingerprint evidence = JmbeVoiceFrameFingerprint.create(codec);
        assertNotNull(evidence);
        assertEquals(0L, evidence.get());
        assertEquals(0L, evidence.get());
        assertEquals(1, codec.reads);
        assertEquals(1.0f, codec.getAudio(new byte[0])[0]);
    }

    public static class LegacyCodec implements IAudioCodec
    {
        public String getCodecName() { return "TEST"; }
        public float[] getAudio(byte[] frame) { return new float[]{1.0f}; }
        public IAudioWithMetadata getAudioWithMetadata(byte[] frame) { return null; }
        public void reset() {}
    }

    public static class CorrectedCodec extends LegacyCodec
    {
        int enableCalls;
        long fingerprint;
        public void setCorrectedVoiceFrameFingerprintEnabled(boolean enabled) { if(enabled) enableCalls++; }
        public long getCorrectedVoiceFrameFingerprintV1() { return fingerprint; }
        public void reset() { fingerprint = 0L; }
    }

    public static class MalformedCodec extends LegacyCodec
    {
        boolean enabled;
        public void setCorrectedVoiceFrameFingerprintEnabled(boolean enabled) { this.enabled = enabled; }
        public String getCorrectedVoiceFrameFingerprintV1() { return "invalid"; }
    }

    public static class BrokenCodec extends CorrectedCodec
    {
        int reads;
        public long getCorrectedVoiceFrameFingerprintV1() { reads++; throw new IllegalStateException("broken"); }
    }
}
