/*
 * Copyright (C) 2026 Dennis Sheirer
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.dsheirer.identifier.alias;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierClass;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.MutableIdentifierCollection;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.identifier.radio.RadioIdentifier;
import io.github.dsheirer.identifier.configuration.ChannelConfigurationIdentifier;
import io.github.dsheirer.module.decode.dmr.identifier.DMRRadio;
import io.github.dsheirer.module.decode.nxdn.identifier.NXDNRadioIdentifier;
import io.github.dsheirer.module.decode.nxdn.identifier.NXDNTalkerAliasIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.APCO25System;
import io.github.dsheirer.module.decode.p25.identifier.APCO25Wacn;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25FullyQualifiedRadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class TalkerAliasOwnershipTest
{
    @Test
    void everySupportedAliasProtocolRequiresAnExplicitMatchingSource()
    {
        for(Sample sample: samples())
        {
            assertNull(TalkerAliasIdentifier.valueFor(collection(sample.first, sample.raw)));
            TalkerAliasIdentifier owned = sample.raw.bindTo(sample.first);
            assertNotNull(owned);
            assertEquals("UNIT", TalkerAliasIdentifier.valueFor(collection(sample.first, owned)));
            assertNull(TalkerAliasIdentifier.find(collection(sample.second, owned)));
            assertNull(TalkerAliasIdentifier.find(collection(owned)));
            assertNull(owned.bindTo(sample.second), "An existing owner can never be reassigned");
            assertNull(sample.raw.bindTo((RadioIdentifier)null));
        }
    }

    @Test
    void sharedCollectionsEnforceOwnerOnNormalAndSilentUpdates()
    {
        for(Sample sample: samples())
        {
            for(boolean silent: List.of(false, true))
            {
                MutableIdentifierCollection state = new MutableIdentifierCollection();
                update(state, sample.first, silent);
                update(state, sample.raw, silent);
                assertNull(aliasSlot(state), "Consumers cannot bind loose text to a convenient current source");
                TalkerAliasIdentifier owned = sample.raw.bindTo(sample.first);
                update(state, owned, silent);
                assertSame(owned, TalkerAliasIdentifier.find(state));
                update(state, sample.second, silent);
                assertNull(aliasSlot(state));
                update(state, owned, silent);
                assertNull(aliasSlot(state), "A late old-owner update cannot restore the stale name");
                TalkerAliasIdentifier next = sample.raw.bindTo(sample.second);
                update(state, next, silent);
                assertSame(next, TalkerAliasIdentifier.find(state));
            }
        }
    }

    @Test
    void boundSnapshotOrderCannotChangeAttribution()
    {
        for(Sample sample: samples())
        {
            TalkerAliasIdentifier alias = sample.raw.bindTo(sample.first);
            assertSame(alias, TalkerAliasIdentifier.find(new MutableIdentifierCollection(List.of(alias, sample.first))));
            assertNull(aliasSlot(new MutableIdentifierCollection(List.of(alias, sample.second))));
            assertNull(aliasSlot(new MutableIdentifierCollection(List.of(sample.second, alias))));
        }
    }

    @Test
    void nativeP25NumberCannotMoveBetweenServingSystems()
    {
        var radio = APCO25RadioIdentifier.createFrom(101);
        var alias = P25TalkerAliasIdentifier.create("HOME").bindTo(radio, "p25:bee00:123");
        assertTrue(alias.matchesSource(radio, "p25:bee00:123"));
        assertFalse(alias.matchesSource(radio, "p25:bee00:124"));
        assertNull(alias.bindTo(radio, "p25:bee00:124"));
        assertEquals("HOME", TalkerAliasIdentifier.valueFor(collection(radio, alias,
            APCO25Wacn.create(0xBEE00), APCO25System.create(0x123))));
        assertNull(TalkerAliasIdentifier.valueFor(collection(radio, alias,
            APCO25Wacn.create(0xBEE00), APCO25System.create(0x124))));

        MutableIdentifierCollection state = new MutableIdentifierCollection(List.of(radio, alias,
            APCO25Wacn.create(0xBEE00), APCO25System.create(0x123)));
        state.update(APCO25System.create(0x124));
        assertNull(aliasSlot(state));
        assertNull(TalkerAliasIdentifier.find(collection(radio, alias, APCO25Wacn.create(0xABCDE))));
        assertNull(TalkerAliasIdentifier.find(collection(radio, alias, APCO25System.create(0x124))));
    }

    @Test
    void canonicalP25IdentityAndWorkingAddressRemainDistinctFacts()
    {
        var first = APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(101, 0xBEE00, 0x123, 501);
        var revised = APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(102, 0xBEE00, 0x123, 501);
        var other = APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(101, 0xBEE00, 0x123, 502);
        var otherHome = APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(101, 0xBEE00, 0x124, 501);
        var alias = P25TalkerAliasIdentifier.create("VISITOR").bindTo(first, "p25:bee00:123");
        assertTrue(alias.matchesSource(revised));
        assertFalse(alias.matchesSource(other));
        assertFalse(alias.matchesSource(otherHome));
        assertTrue(alias.matchesSource(APCO25RadioIdentifier.createFrom(101)));
        assertFalse(alias.matchesSource(APCO25RadioIdentifier.createFrom(501)));
        assertNull(alias.bindTo(other));
    }

    @Test
    void aPlainOwnerMustAcquireCanonicalEvidenceBeforeAWorkingAddressCanBeReused()
    {
        var local = APCO25RadioIdentifier.createFrom(101);
        var first = APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(101, 0xBEE00, 0x123, 501);
        var second = APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(101, 0xBEE00, 0x123, 502);
        var alias = P25TalkerAliasIdentifier.create("FIRST").bindTo(local);
        MutableIdentifierCollection state = new MutableIdentifierCollection(List.of(local, alias));
        state.update(first);
        assertNotNull(TalkerAliasIdentifier.find(state));
        assertTrue(TalkerAliasIdentifier.find(state).matchesSource(first));
        state.update(second);
        assertNull(aliasSlot(state), "The old numeric owner must not follow a reassigned working address");
    }

    @Test
    void revisedWorkingAddressDoesNotLeaveThePreviousAddressAttachedToTheName()
    {
        var first = APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(101, 0xBEE00, 0x123, 501);
        var revised = APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(102, 0xBEE00, 0x123, 501);
        var alias = P25TalkerAliasIdentifier.create("FIRST").bindTo(first);
        MutableIdentifierCollection state = new MutableIdentifierCollection(List.of(first, alias));
        state.update(revised);
        assertEquals("FIRST", TalkerAliasIdentifier.valueFor(state));
        assertTrue(TalkerAliasIdentifier.find(state).matchesSource(APCO25RadioIdentifier.createFrom(102)));
        assertFalse(TalkerAliasIdentifier.find(state).matchesSource(APCO25RadioIdentifier.createFrom(101)));
        state.update(APCO25RadioIdentifier.createFrom(101));
        assertNull(aliasSlot(state));
    }

    @Test
    void sameLocalNumberInAnotherSavedChannelCannotAcquireTheName()
    {
        String first = "00000000-0000-0000-0000-000000000001";
        String second = "00000000-0000-0000-0000-000000000002";
        for(Sample sample: samples())
        {
            var alias = sample.raw.bindTo(collection(sample.first, new ChannelConfigurationIdentifier(first)));
            assertNotNull(alias);
            assertTrue(alias.matchesSource(sample.first, null, first));
            assertFalse(alias.matchesSource(sample.first, null, second));
            assertNull(alias.bindTo(sample.first, null, second));
            assertNull(TalkerAliasIdentifier.find(collection(sample.first, alias,
                new ChannelConfigurationIdentifier(second))));
        }
    }

    @Test
    void dmrAndNxdnNamesRetainTheirKnownNativeSystemOwner()
    {
        for(Sample sample: samples().subList(1, 3))
        {
            var alias = sample.raw.bindTo(sample.first, "native-system-a");
            assertTrue(alias.matchesSource(sample.first, "native-system-a"));
            assertFalse(alias.matchesSource(sample.first, "native-system-b"));
            assertNull(alias.bindTo(sample.first, "native-system-b"));
            assertNotNull(alias.bindTo(sample.first), "An omitted context cannot rewrite the known owner");
        }
    }

    @Test
    void canonicalHomePromotionNeedsTheActualServingSystem()
    {
        var local = APCO25RadioIdentifier.createFrom(501);
        var home = APCO25FullyQualifiedRadioIdentifier.createFrom(501, 0xBEE00, 0x123, 501);
        var alias = P25TalkerAliasIdentifier.create("HOME").bindTo(home, "p25:bee00:123");
        assertTrue(alias.matchesSource(local));
        assertNull(TalkerAliasIdentifier.find(collection(local, alias,
            APCO25Wacn.create(0xBEE00), APCO25System.create(0x124))));
        assertEquals("HOME", TalkerAliasIdentifier.valueFor(collection(local, alias,
            APCO25Wacn.create(0xBEE00), APCO25System.create(0x123))));
        assertNull(P25TalkerAliasIdentifier.create("UNKNOWN").bindTo(home).bindTo(local));
    }

    @Test
    void exactCanonicalOwnerCanRoamWhileLocalAndPartialScopesRemainStrict()
    {
        var visitor = APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(101, 0xABCDE, 0x321, 501);
        var alias = P25TalkerAliasIdentifier.create("VISITOR").bindTo(visitor, "p25:bee00:123");
        var nextSite = collection(visitor, alias, APCO25Wacn.create(0xBEE00), APCO25System.create(0x124));
        assertEquals("VISITOR", TalkerAliasIdentifier.valueFor(nextSite));
        var rebound = alias.bindTo(visitor, "p25:bee00:124");
        assertNotNull(rebound);
        assertFalse(rebound.matchesSource(APCO25RadioIdentifier.createFrom(101), "p25:bee00:123"));
        assertNull(TalkerAliasIdentifier.find(collection(visitor, alias, APCO25System.create(0x124))));
        assertNull(TalkerAliasIdentifier.find(collection(APCO25RadioIdentifier.createFrom(101), alias,
            APCO25Wacn.create(0xBEE00), APCO25System.create(0x124))));
    }

    @Test
    void nxdnAddressDomainIsPartOfTheOwnerAndCapturedImmutably()
    {
        var typeC = NXDNRadioIdentifier.createFrom(101);
        var typeD = NXDNRadioIdentifier.createTypeDFrom(101);
        var alias = new NXDNTalkerAliasIdentifier("TYPE C").bindTo(typeC);
        assertFalse(alias.matchesSource(typeD));
        assertNull(alias.bindTo(typeD));
        typeC.setTypeD(true);
        assertFalse(alias.matchesSource(typeC));
        assertTrue(alias.matchesSource(NXDNRadioIdentifier.createFrom(101)));
    }

    @Test
    void radioOwnerCannotCrossProtocolsOrUseReservedP25Addresses()
    {
        var alias = P25TalkerAliasIdentifier.create("UNIT");
        assertNull(alias.bindTo(DMRRadio.createFrom(101)));
        assertNull(alias.bindTo(APCO25RadioIdentifier.createFrom(0)));
        assertNull(alias.bindTo(APCO25RadioIdentifier.createFrom(0xFFFFFF)));
        assertNull(P25TalkerAliasIdentifier.create("  ").bindTo(APCO25RadioIdentifier.createFrom(101)));
    }

    @Test
    void ownerChecksDoNotWaitForAnObserverMonitor() throws Exception
    {
        var source = DMRRadio.createFrom(101);
        var alias = DmrTalkerAliasIdentifier.create("UNIT").bindTo(source);
        IdentifierCollection context = collection(source, alias);
        try(var executor = Executors.newSingleThreadExecutor())
        {
            synchronized(alias)
            {
                assertEquals("UNIT", executor.submit(() -> TalkerAliasIdentifier.valueFor(context))
                    .get(1, TimeUnit.SECONDS));
            }
        }
    }

    private static void update(MutableIdentifierCollection state, Identifier identifier, boolean silent)
    {
        if(silent) state.silentUpdate(identifier);
        else state.update(identifier);
    }

    private static Identifier aliasSlot(IdentifierCollection state)
    {
        return state.getIdentifier(IdentifierClass.USER, Form.TALKER_ALIAS, Role.FROM);
    }

    private static IdentifierCollection collection(Identifier... values)
    {
        return new IdentifierCollection(List.of(values));
    }

    private record Sample(RadioIdentifier first, RadioIdentifier second, TalkerAliasIdentifier raw) {}

    private static List<Sample> samples()
    {
        return List.of(new Sample(APCO25RadioIdentifier.createFrom(101), APCO25RadioIdentifier.createFrom(102),
                P25TalkerAliasIdentifier.create("UNIT")),
            new Sample(DMRRadio.createFrom(101), DMRRadio.createFrom(102), DmrTalkerAliasIdentifier.create("UNIT")),
            new Sample(NXDNRadioIdentifier.createFrom(101), NXDNRadioIdentifier.createFrom(102),
                new NXDNTalkerAliasIdentifier("UNIT")),
            new Sample(NXDNRadioIdentifier.createTypeDFrom(101), NXDNRadioIdentifier.createTypeDFrom(102),
                new NXDNTalkerAliasIdentifier("UNIT")));
    }
}
