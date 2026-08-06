/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */

package io.github.dsheirer.module.decode.p25.phase1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.dsp.symbol.Dibit;
import io.github.dsheirer.edac.bch.BCH_63_16_23_P25;
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.module.decode.p25.phase1.P25P1DemodulatorC4FM.Correction;
import io.github.dsheirer.module.decode.p25.phase1.message.P25P1Message;
import io.github.dsheirer.source.SourceEvent;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class P25P1NACGuardTest
{
    private static final int EXPECTED_NAC = 0x491;
    private static final int FOREIGN_NAC = 0x1C0;
    private static final long BCH_GENERATOR = 0xCD930BDD3B2BL;
    private static final long BCH_REMAINDER_MASK = (1L << 47) - 1;

    @Test
    void pinnedFramerAcceptsOnlyExpectedNACAcrossCompleteFrameBoundaries()
    {
        P25P1MessageFramer framer = new P25P1MessageFramer();
        List<IMessage> messages = new ArrayList<>();
        framer.setListener(messages::add);
        framer.setExpectedNAC(EXPECTED_NAC);
        framer.start();

        feedNID(framer, nid(EXPECTED_NAC, P25P1DataUnitID.LOGICAL_LINK_DATA_UNIT_1));
        feedUntilP25MessageCount(framer, messages, 1);
        assertEquals(EXPECTED_NAC, nac(p25Messages(messages).getFirst()));

        for(int frame = 0; frame < 20; frame++)
        {
            feedNID(framer, nid(FOREIGN_NAC, P25P1DataUnitID.LOGICAL_LINK_DATA_UNIT_2));
            feedDibits(framer, 900);
        }

        assertEquals(1, p25Messages(messages).size(), "foreign units must never train the pinned decoder");

        feedNID(framer, nid(EXPECTED_NAC, P25P1DataUnitID.LOGICAL_LINK_DATA_UNIT_2));
        feedUntilP25MessageCount(framer, messages, 2);
        assertEquals(EXPECTED_NAC, nac(p25Messages(messages).get(1)));
    }

    @Test
    void uncorrectableNIDCannotReusePreviouslyDetectedNAC()
    {
        P25P1MessageFramer framer = new P25P1MessageFramer();
        List<IMessage> messages = new ArrayList<>();
        framer.setListener(messages::add);
        framer.setExpectedNAC(EXPECTED_NAC);
        framer.start();

        feedNID(framer, nid(EXPECTED_NAC, P25P1DataUnitID.LOGICAL_LINK_DATA_UNIT_1));
        feedUntilP25MessageCount(framer, messages, 1);

        CorrectedBinaryMessage damaged = uncorrectableNID(EXPECTED_NAC,
            P25P1DataUnitID.LOGICAL_LINK_DATA_UNIT_2);
        assertEquals(EXPECTED_NAC, damaged.getInt(BCH_63_16_23_P25.NAC_FIELD));
        feedNID(framer, damaged);
        feedDibits(framer, 900);

        assertEquals(1, p25Messages(messages).size(),
            "failed NID must not enter placeholder recovery under the previous NAC");

        feedNID(framer, nid(EXPECTED_NAC, P25P1DataUnitID.LOGICAL_LINK_DATA_UNIT_2));
        feedUntilP25MessageCount(framer, messages, 2);
    }

    @Test
    void strictControlFramerCannotInferAuthorityAfterANIDFailure() throws Exception
    {
        P25P1MessageFramer framer = new P25P1MessageFramer();
        framer.setRequireValidNID(true);
        setField(framer, "mDetectedNAC", EXPECTED_NAC);

        assertFalse(framer.decodeNID(uncorrectableNID(EXPECTED_NAC,
            P25P1DataUnitID.TRUNKING_SIGNALING_BLOCK_1)));
        setField(framer, "mMessageAssemblyRequired", false);
        setField(framer, "mDibitCounter", 57);
        setField(framer, "mStatusSymbolDibitCounter", 0);
        framer.process(Dibit.D00_PLUS_1.getIdealPhase(), Dibit.D00_PLUS_1);

        assertFalse(framer.isAssembling(), "a stale NAC cannot start inferred control-message assembly");
        assertFalse(framer.decodeNID(nid(EXPECTED_NAC, 0x1)),
            "a protected but unrecognized DUID cannot be inferred into a control unit");
    }

    @Test
    void strictControlFramerNeverSubstitutesATrainedNACIntoFailedBCH() throws Exception
    {
        P25P1MessageFramer framer = new P25P1MessageFramer();
        framer.setRequireValidNID(true);
        train(field(framer, "mNACTracker", NACTracker.class), EXPECTED_NAC);
        CorrectedBinaryMessage damaged = nid(EXPECTED_NAC, P25P1DataUnitID.TRUNKING_SIGNALING_BLOCK_1);

        for(int bit = 0; bit < 12; bit++)
        {
            damaged.flip(bit);
        }

        CorrectedBinaryMessage unhinted = new CorrectedBinaryMessage(damaged);
        new BCH_63_16_23_P25().decode(unhinted);
        assertEquals(-1, unhinted.getCorrectedBitCount());

        CorrectedBinaryMessage legacyRecovery = new CorrectedBinaryMessage(damaged);
        new BCH_63_16_23_P25().decode(legacyRecovery, EXPECTED_NAC);
        assertTrue(legacyRecovery.getCorrectedBitCount() >= 0,
            "the permissive legacy path demonstrates the trained-NAC substitution risk");

        assertFalse(framer.decodeNID(new CorrectedBinaryMessage(damaged)));
    }

    @Test
    void strictC4FMTimingPathNeverSubstitutesATrainedNACIntoFailedBCH() throws Exception
    {
        P25P1DecoderC4FM parent = new P25P1DecoderC4FM(25_000);
        P25P1DemodulatorC4FM demodulator = new P25P1DemodulatorC4FM(new P25P1MessageFramer(), parent);
        demodulator.setRequireValidNID(true);
        train(field(demodulator, "mNACTracker", NACTracker.class), EXPECTED_NAC);
        CorrectedBinaryMessage damaged = nid(EXPECTED_NAC, P25P1DataUnitID.TRUNKING_SIGNALING_BLOCK_1);

        for(int bit = 0; bit < 12; bit++)
        {
            damaged.flip(bit);
        }

        Correction correction = correction();
        demodulator.decodeNID(damaged, correction);

        assertEquals(P25P1DataUnitID.PLACE_HOLDER, correction.getDataUnitID());

        Correction unknownDuid = correction();
        demodulator.decodeNID(nid(EXPECTED_NAC, 0x1), unknownDuid);
        assertEquals(P25P1DataUnitID.PLACE_HOLDER, unknownDuid.getDataUnitID());
    }

    @Test
    void ordinaryDecoderWithoutPreloadRetainsNACDiscovery()
    {
        P25P1MessageFramer framer = new P25P1MessageFramer();
        List<IMessage> messages = new ArrayList<>();
        framer.setListener(messages::add);
        framer.start();

        feedNID(framer, nid(FOREIGN_NAC, P25P1DataUnitID.LOGICAL_LINK_DATA_UNIT_1));
        feedUntilP25MessageCount(framer, messages, 1);

        assertEquals(FOREIGN_NAC, nac(p25Messages(messages).getFirst()));

        P25P1DecoderC4FM parent = new P25P1DecoderC4FM(25_000);
        P25P1DemodulatorC4FM demodulator = new P25P1DemodulatorC4FM(new P25P1MessageFramer(), parent);
        Correction correction = correction();
        demodulator.decodeNID(nid(FOREIGN_NAC, P25P1DataUnitID.LOGICAL_LINK_DATA_UNIT_1), correction);
        assertEquals(FOREIGN_NAC, correction.getNAC());
        assertEquals(P25P1DataUnitID.LOGICAL_LINK_DATA_UNIT_1, correction.getDataUnitID());
    }

    @Test
    void expectedNACReplacementClearsOldAuthority()
    {
        P25P1MessageFramer framer = new P25P1MessageFramer();
        framer.setExpectedNAC(EXPECTED_NAC);
        assertTrue(framer.decodeNID(nid(EXPECTED_NAC, P25P1DataUnitID.LOGICAL_LINK_DATA_UNIT_1)));

        framer.setExpectedNAC(FOREIGN_NAC);

        assertEquals(FOREIGN_NAC, framer.getExpectedNAC());
        assertFalse(framer.decodeNID(nid(EXPECTED_NAC, P25P1DataUnitID.LOGICAL_LINK_DATA_UNIT_1)));
        assertTrue(framer.decodeNID(nid(FOREIGN_NAC, P25P1DataUnitID.LOGICAL_LINK_DATA_UNIT_1)));
    }

    @Test
    void pinnedFramerRecoversOnlyNearLimitVoiceNIDWithAuthoritativeNAC() throws Exception
    {
        P25P1MessageFramer framer = new P25P1MessageFramer();
        framer.setExpectedNAC(EXPECTED_NAC);
        setField(framer, "mPreviousDataUnitID", P25P1DataUnitID.LOGICAL_LINK_DATA_UNIT_2);

        CorrectedBinaryMessage recoverableVoice =
            nid(FOREIGN_NAC, P25P1DataUnitID.LOGICAL_LINK_DATA_UNIT_1);

        for(int bit = 16; bit < 26; bit++)
        {
            recoverableVoice.flip(bit);
        }

        assertTrue(framer.decodeNID(recoverableVoice));
        assertEquals(EXPECTED_NAC, field(framer, "mDetectedNAC", Integer.class));

        CorrectedBinaryMessage lightlyDamagedVoice =
            nid(FOREIGN_NAC, P25P1DataUnitID.LOGICAL_LINK_DATA_UNIT_1);

        for(int bit = 16; bit < 23; bit++)
        {
            lightlyDamagedVoice.flip(bit);
        }

        assertFalse(framer.decodeNID(lightlyDamagedVoice));

        CorrectedBinaryMessage nearLimitControl =
            nid(FOREIGN_NAC, P25P1DataUnitID.TRUNKING_SIGNALING_BLOCK_1);

        for(int bit = 16; bit < 26; bit++)
        {
            nearLimitControl.flip(bit);
        }

        assertFalse(framer.decodeNID(nearLimitControl));
    }

    @Test
    void matchingNACWithUnknownDUIDMayStillInferOnlyTheUnitType() throws Exception
    {
        P25P1MessageFramer framer = new P25P1MessageFramer();
        framer.setExpectedNAC(EXPECTED_NAC);

        assertTrue(framer.decodeNID(nid(EXPECTED_NAC, 0x1)));
        framer.process(Dibit.D00_PLUS_1.getIdealPhase(), Dibit.D00_PLUS_1);

        assertTrue(framer.isAssembling(), "verified NAC may retain placeholder recovery for only the unknown DUID");
        P25P1MessageAssembler assembler = field(framer, "mMessageAssembler", P25P1MessageAssembler.class);
        assertEquals(EXPECTED_NAC, assembler.getNAC(), "placeholder recovery must retain the verified NAC");
    }

    @Test
    void pinnedNIDUsesFullBCHAndParityProtection()
    {
        CorrectedBinaryMessage parityOnly = nid(EXPECTED_NAC, P25P1DataUnitID.LOGICAL_LINK_DATA_UNIT_1);
        parityOnly.flip(63);
        new BCH_63_16_23_P25().decode(parityOnly);
        assertEquals(0, parityOnly.getCorrectedBitCount());
        assertEquals(1, P25P1NIDValidator.validateExpectedNAC(parityOnly, EXPECTED_NAC));

        CorrectedBinaryMessage tenPlusParity = nid(EXPECTED_NAC, P25P1DataUnitID.LOGICAL_LINK_DATA_UNIT_1);

        for(int bit = 16; bit < 26; bit++)
        {
            tenPlusParity.flip(bit);
        }

        tenPlusParity.flip(63);
        new BCH_63_16_23_P25().decode(tenPlusParity);
        assertEquals(10, tenPlusParity.getCorrectedBitCount());
        assertEquals(11, P25P1NIDValidator.validateExpectedNAC(tenPlusParity, EXPECTED_NAC));

        CorrectedBinaryMessage elevenPlusParity = nid(EXPECTED_NAC, P25P1DataUnitID.LOGICAL_LINK_DATA_UNIT_1);

        for(int bit = 16; bit < 27; bit++)
        {
            elevenPlusParity.flip(bit);
        }

        elevenPlusParity.flip(63);
        new BCH_63_16_23_P25().decode(elevenPlusParity);
        assertEquals(11, elevenPlusParity.getCorrectedBitCount());
        assertEquals(-1, P25P1NIDValidator.validateExpectedNAC(elevenPlusParity, EXPECTED_NAC));

        CorrectedBinaryMessage elevenOnly = nid(EXPECTED_NAC, P25P1DataUnitID.LOGICAL_LINK_DATA_UNIT_1);

        for(int bit = 16; bit < 27; bit++)
        {
            elevenOnly.flip(bit);
        }

        new BCH_63_16_23_P25().decode(elevenOnly);
        assertEquals(11, elevenOnly.getCorrectedBitCount());
        assertEquals(11, P25P1NIDValidator.validateExpectedNAC(elevenOnly, EXPECTED_NAC));
    }

    @Test
    void ordinaryNIDAlsoUsesFullParityProtection()
    {
        CorrectedBinaryMessage elevenPlusParity = nid(EXPECTED_NAC, P25P1DataUnitID.LOGICAL_LINK_DATA_UNIT_1);

        for(int bit = 16; bit < 27; bit++)
        {
            elevenPlusParity.flip(bit);
        }

        elevenPlusParity.flip(63);
        P25P1MessageFramer framer = new P25P1MessageFramer();
        assertFalse(framer.decodeNID(new CorrectedBinaryMessage(elevenPlusParity)));

        P25P1DecoderC4FM parent = new P25P1DecoderC4FM(25_000);
        P25P1DemodulatorC4FM demodulator = new P25P1DemodulatorC4FM(new P25P1MessageFramer(), parent);
        Correction correction = correction();
        demodulator.decodeNID(new CorrectedBinaryMessage(elevenPlusParity), correction);
        assertEquals(P25P1DataUnitID.PLACE_HOLDER, correction.getDataUnitID());
    }

    @Test
    void c4fmEarlyValidatorNeverLearnsForeignNAC()
    {
        P25P1DecoderC4FM parent = new P25P1DecoderC4FM(25_000);
        P25P1DemodulatorC4FM demodulator = new P25P1DemodulatorC4FM(new P25P1MessageFramer(), parent);
        demodulator.setExpectedNAC(EXPECTED_NAC);

        for(int attempt = 0; attempt < 100; attempt++)
        {
            Correction correction = correction();
            demodulator.decodeNID(nid(FOREIGN_NAC, P25P1DataUnitID.LOGICAL_LINK_DATA_UNIT_1), correction);
            assertEquals(P25P1DataUnitID.PLACE_HOLDER, correction.getDataUnitID());
        }

        Correction matching = correction();
        demodulator.decodeNID(nid(EXPECTED_NAC, P25P1DataUnitID.LOGICAL_LINK_DATA_UNIT_1), matching);
        assertEquals(P25P1DataUnitID.LOGICAL_LINK_DATA_UNIT_1, matching.getDataUnitID());
        assertEquals(EXPECTED_NAC, matching.getNAC());

        Correction damaged = correction();
        demodulator.decodeNID(uncorrectableNID(EXPECTED_NAC, P25P1DataUnitID.LOGICAL_LINK_DATA_UNIT_2), damaged);
        assertEquals(P25P1DataUnitID.PLACE_HOLDER, damaged.getDataUnitID());
    }

    @Test
    void pinnedGuardNeverSubstitutesExpectedNACIntoAnUncorrectableCodeword()
    {
        CorrectedBinaryMessage damaged = nid(EXPECTED_NAC, P25P1DataUnitID.LOGICAL_LINK_DATA_UNIT_1);

        for(int bit = 0; bit < 12; bit++)
        {
            damaged.flip(bit);
        }

        CorrectedBinaryMessage unhinted = new CorrectedBinaryMessage(damaged);
        new BCH_63_16_23_P25().decode(unhinted);
        assertEquals(-1, unhinted.getCorrectedBitCount());

        CorrectedBinaryMessage substituted = new CorrectedBinaryMessage(damaged);
        new BCH_63_16_23_P25().decode(substituted, EXPECTED_NAC);
        assertEquals(EXPECTED_NAC, substituted.getInt(BCH_63_16_23_P25.NAC_FIELD));
        assertTrue(substituted.getCorrectedBitCount() >= 0,
            "the legacy retry demonstrates why a pinned guard must not substitute authority bits");

        P25P1MessageFramer framer = new P25P1MessageFramer();
        framer.setExpectedNAC(EXPECTED_NAC);
        assertFalse(framer.decodeNID(new CorrectedBinaryMessage(damaged)));
    }

    @Test
    void nacZeroWorksAsAConcreteExpectedAndTrackedValue()
    {
        P25P1MessageFramer framer = new P25P1MessageFramer();
        framer.setExpectedNAC(0);
        assertEquals(0, framer.getExpectedNAC());
        assertTrue(framer.decodeNID(nid(0, P25P1DataUnitID.LOGICAL_LINK_DATA_UNIT_1)));

        P25P1DecoderC4FM parent = new P25P1DecoderC4FM(25_000);
        P25P1DemodulatorC4FM demodulator = new P25P1DemodulatorC4FM(new P25P1MessageFramer(), parent);
        demodulator.setExpectedNAC(0);
        Correction correction = correction();
        demodulator.decodeNID(nid(0, P25P1DataUnitID.LOGICAL_LINK_DATA_UNIT_1), correction);
        assertEquals(0, correction.getNAC());
        assertEquals(P25P1DataUnitID.LOGICAL_LINK_DATA_UNIT_1, correction.getDataUnitID());

        NACTracker tracker = new NACTracker();
        tracker.track(0);
        tracker.track(0);
        tracker.track(0);
        assertEquals(0, tracker.getTrackedNAC());

        CorrectedBinaryMessage damaged = nid(0, P25P1DataUnitID.LOGICAL_LINK_DATA_UNIT_1);

        for(int bit = 0; bit < 12; bit++)
        {
            damaged.flip(bit);
        }

        new BCH_63_16_23_P25().decode(damaged, tracker.getTrackedNAC());
        assertEquals(0, damaged.getInt(BCH_63_16_23_P25.NAC_FIELD));
        assertTrue(damaged.getCorrectedBitCount() >= 0);
    }

    @Test
    void decoderPreloadPinsBothModulationPaths() throws Exception
    {
        P25P1NACPreloadDataContent preload = new P25P1NACPreloadDataContent(EXPECTED_NAC);
        P25P1DecoderC4FM c4fm = new P25P1DecoderC4FM(25_000);
        c4fm.process(preload);

        P25P1MessageFramer c4fmFramer = field(c4fm, "mMessageFramer", P25P1MessageFramer.class);
        P25P1DemodulatorC4FM c4fmDemodulator = field(c4fm, "mSymbolProcessor", P25P1DemodulatorC4FM.class);
        assertEquals(EXPECTED_NAC, c4fmFramer.getExpectedNAC());
        assertEquals(EXPECTED_NAC, c4fmDemodulator.getExpectedNAC());

        P25P1DecoderLSM lsm = new P25P1DecoderLSM(25_000);
        lsm.process(preload);
        P25P1MessageFramer lsmFramer = field(lsm, "mMessageFramer", P25P1MessageFramer.class);
        assertEquals(EXPECTED_NAC, lsmFramer.getExpectedNAC());
    }

    @Test
    void sourceFrequencyChangeClearsDiscoveryButRetainsPinnedAuthority() throws Exception
    {
        P25P1DecoderC4FM c4fm = new P25P1DecoderC4FM(25_000);
        P25P1MessageFramer c4fmFramer = field(c4fm, "mMessageFramer", P25P1MessageFramer.class);
        P25P1DemodulatorC4FM c4fmDemodulator = field(c4fm, "mSymbolProcessor", P25P1DemodulatorC4FM.class);
        NACTracker framerTracker = field(c4fmFramer, "mNACTracker", NACTracker.class);
        NACTracker demodulatorTracker = field(c4fmDemodulator, "mNACTracker", NACTracker.class);
        train(framerTracker, EXPECTED_NAC);
        train(demodulatorTracker, EXPECTED_NAC);

        c4fm.getSourceEventListener().receive(SourceEvent.frequencyCorrectionChange(25));
        assertEquals(EXPECTED_NAC, framerTracker.getTrackedNAC());
        assertEquals(EXPECTED_NAC, demodulatorTracker.getTrackedNAC());

        c4fm.getSourceEventListener().receive(SourceEvent.frequencyChange(null, 852_000_000L));
        assertEquals(NACTracker.NO_TRACKED_NAC, framerTracker.getTrackedNAC());
        assertEquals(NACTracker.NO_TRACKED_NAC, demodulatorTracker.getTrackedNAC());

        P25P1NACPreloadDataContent preload = new P25P1NACPreloadDataContent(EXPECTED_NAC);
        c4fm.process(preload);
        c4fm.getSourceEventListener().receive(SourceEvent.frequencyChange(null, 853_000_000L));
        assertEquals(EXPECTED_NAC, c4fmFramer.getExpectedNAC());
        assertEquals(EXPECTED_NAC, c4fmDemodulator.getExpectedNAC());
        assertTrue(c4fmFramer.decodeNID(nid(EXPECTED_NAC, P25P1DataUnitID.LOGICAL_LINK_DATA_UNIT_1)));
        assertFalse(c4fmFramer.decodeNID(nid(FOREIGN_NAC, P25P1DataUnitID.LOGICAL_LINK_DATA_UNIT_1)));

        P25P1DecoderLSM lsm = new P25P1DecoderLSM(25_000);
        P25P1MessageFramer lsmFramer = field(lsm, "mMessageFramer", P25P1MessageFramer.class);
        NACTracker lsmTracker = field(lsmFramer, "mNACTracker", NACTracker.class);
        train(lsmTracker, EXPECTED_NAC);
        lsm.getSourceEventListener().receive(SourceEvent.frequencyChange(null, 852_000_000L));
        assertEquals(NACTracker.NO_TRACKED_NAC, lsmTracker.getTrackedNAC());
        lsm.process(preload);
        lsm.getSourceEventListener().receive(SourceEvent.frequencyChange(null, 853_000_000L));
        assertEquals(EXPECTED_NAC, lsmFramer.getExpectedNAC());
    }

    @Test
    void ordinaryNACZeroCanEnterPlaceholderRecovery() throws Exception
    {
        P25P1MessageFramer framer = new P25P1MessageFramer();
        setField(framer, "mDetectedNAC", 0);
        setField(framer, "mMessageAssemblyRequired", false);
        setField(framer, "mDibitCounter", 57);
        setField(framer, "mStatusSymbolDibitCounter", 0);

        framer.process(Dibit.D00_PLUS_1.getIdealPhase(), Dibit.D00_PLUS_1);

        P25P1MessageAssembler assembler = field(framer, "mMessageAssembler", P25P1MessageAssembler.class);
        assertEquals(0, assembler.getNAC());
        assertEquals(P25P1DataUnitID.PLACE_HOLDER, assembler.getDataUnitID());
    }

    @Test
    void wildcardReceiverSettingsCannotBecomeTrafficAuthority()
    {
        assertThrows(IllegalArgumentException.class, () -> new P25P1NACPreloadDataContent(0xF7E));
        assertThrows(IllegalArgumentException.class, () -> new P25P1NACPreloadDataContent(0xF7F));
    }

    private static Correction correction()
    {
        return new Correction(0, 0, 0, 0, 200, 200, 0);
    }

    private static void train(NACTracker tracker, int nac)
    {
        tracker.track(nac);
        tracker.track(nac);
        tracker.track(nac);
    }

    private static void feedNID(P25P1MessageFramer framer, CorrectedBinaryMessage nid)
    {
        framer.syncDetected();
        int bit = 0;

        for(int symbol = 0; symbol < 33; symbol++)
        {
            Dibit dibit;

            if(symbol == 11)
            {
                dibit = Dibit.D00_PLUS_1;
            }
            else
            {
                dibit = dibit(nid.get(bit), nid.get(bit + 1));
                bit += 2;
            }

            framer.process(dibit.getIdealPhase(), dibit);
        }
    }

    private static void feedDibits(P25P1MessageFramer framer, int count)
    {
        for(int x = 0; x < count; x++)
        {
            framer.process(Dibit.D00_PLUS_1.getIdealPhase(), Dibit.D00_PLUS_1);
        }
    }

    private static void feedUntilP25MessageCount(P25P1MessageFramer framer, List<IMessage> messages, int expectedCount)
    {
        for(int x = 0; x < 2_000 && p25Messages(messages).size() < expectedCount; x++)
        {
            framer.process(Dibit.D00_PLUS_1.getIdealPhase(), Dibit.D00_PLUS_1);
        }

        assertEquals(expectedCount, p25Messages(messages).size());
    }

    private static List<P25P1Message> p25Messages(List<IMessage> messages)
    {
        return messages.stream().filter(P25P1Message.class::isInstance).map(P25P1Message.class::cast).toList();
    }

    private static int nac(P25P1Message message)
    {
        assertInstanceOf(Number.class, message.getNAC().getValue());
        return ((Number)message.getNAC().getValue()).intValue();
    }

    private static CorrectedBinaryMessage uncorrectableNID(int nac, P25P1DataUnitID duid)
    {
        CorrectedBinaryMessage original = nid(nac, duid);

        for(int width = 12; width <= 24; width++)
        {
            for(int start = 16; start + width <= 63; start++)
            {
                CorrectedBinaryMessage damaged = new CorrectedBinaryMessage(original);

                for(int bit = start; bit < start + width; bit++)
                {
                    damaged.flip(bit);
                }

                CorrectedBinaryMessage probe = new CorrectedBinaryMessage(damaged);
                new BCH_63_16_23_P25().decode(probe);

                if(probe.getCorrectedBitCount() < 0)
                {
                    return damaged;
                }
            }
        }

        throw new AssertionError("Unable to construct an uncorrectable NID test vector");
    }

    /**
     * Encodes the systematic BCH(63,16,23) NID and appends the DUID S1 XOR S0 parity bit from TIA-102.BAAA-A 8.5.2.
     */
    private static CorrectedBinaryMessage nid(int nac, P25P1DataUnitID duid)
    {
        return nid(nac, duid.getValue());
    }

    private static CorrectedBinaryMessage nid(int nac, int duidValue)
    {
        long information = ((long)((nac << 4) | duidValue)) << 47;
        long remainder = information;

        for(int bit = 62; bit >= 47; bit--)
        {
            if((remainder & (1L << bit)) != 0)
            {
                remainder ^= BCH_GENERATOR << (bit - 47);
            }
        }

        long codeword63 = information | (remainder & BCH_REMAINDER_MASK);
        long parity = ((duidValue & 0x2) != 0) ^ ((duidValue & 0x1) != 0) ? 1 : 0;
        long codeword64 = (codeword63 << 1) | parity;
        CorrectedBinaryMessage message = new CorrectedBinaryMessage(64);

        for(int bit = 0; bit < 64; bit++)
        {
            if(((codeword64 >>> (63 - bit)) & 1) == 1)
            {
                message.set(bit);
            }
        }

        return message;
    }

    private static Dibit dibit(boolean bit1, boolean bit2)
    {
        if(bit1)
        {
            return bit2 ? Dibit.D11_MINUS_3 : Dibit.D10_MINUS_1;
        }

        return bit2 ? Dibit.D01_PLUS_3 : Dibit.D00_PLUS_1;
    }

    private static <T> T field(Object instance, String name, Class<T> type) throws Exception
    {
        Field field = instance.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(instance));
    }

    private static void setField(Object instance, String name, Object value) throws Exception
    {
        Field field = instance.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(instance, value);
    }
}
