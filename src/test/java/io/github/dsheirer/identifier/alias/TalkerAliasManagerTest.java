/*
 * *****************************************************************************
 * Copyright (C) 2026
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */
package io.github.dsheirer.identifier.alias;

import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.identifier.IdentifierClass;
import io.github.dsheirer.identifier.MutableIdentifierCollection;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.identifier.radio.RadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25FullyQualifiedRadioIdentifier;
import io.github.dsheirer.module.decode.dmr.identifier.DMRRadio;
import io.github.dsheirer.module.decode.nxdn.identifier.NXDNRadioIdentifier;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TalkerAliasManagerTest
{
    @Test
    void forgetMatchesTheStoredFormOfPaddedTruncatedAndSupplementaryNames()
    {
        String supplementary = "\uD83D\uDE80";
        String[][] names = {
            {" \u2003Padded alias\u2003 ", "Padded alias"},
            {"X".repeat(160) + "trailing text", "X".repeat(160)},
            {supplementary.repeat(160) + "trailing text", supplementary.repeat(160)},
            {"X".repeat(159) + supplementary + "trailing text", "X".repeat(159) + supplementary}
        };
        var radio = APCO25RadioIdentifier.createFrom(501);
        var identity = io.github.dsheirer.module.decode.traffic.RadioSystemIdentityKey.parse("v1-r-bee00-123-501");
        for(String[] name: names)
        {
            var cache = new TalkerAliasManager();
            cache.update(radio, P25TalkerAliasIdentifier.create(name[0]), "p25:bee00:123");
            cache.forget("p25:bee00:123", identity, name[1]);
            assertTrue(!cache.hasAlias(radio, "p25:bee00:123"));
            cache.update(radio, P25TalkerAliasIdentifier.create("Replacement"), "p25:bee00:123");
            cache.forget("p25:bee00:123", identity, name[1]);
            assertTrue(cache.hasAlias(radio, "p25:bee00:123"));
        }
    }

    @Test
    void dmrChannelFallbackForgetPreservesOtherProtocolsAndNativeEntries()
    {
        var cache = new TalkerAliasManager();
        var selected = DMRRadio.createFrom(501);
        var otherRadio = DMRRadio.createFrom(502);
        var otherProtocol = NXDNRadioIdentifier.createFrom(501);
        String fallback = "dmr:channel:00000000-0000-0000-0000-000000000001";
        String nativeKey = "dmr:tier3:small:17";
        cache.update(selected, DmrTalkerAliasIdentifier.create("OLD"), fallback);
        cache.update(selected, DmrTalkerAliasIdentifier.create("NATIVE"), nativeKey);
        cache.update(otherRadio, DmrTalkerAliasIdentifier.create("OTHER"), fallback);
        cache.update(otherProtocol, new io.github.dsheirer.module.decode.nxdn.identifier.NXDNTalkerAliasIdentifier("NXDN"));

        assertFallbackForgetAndFreshLearning(cache, selected, fallback, DmrTalkerAliasIdentifier.create("FRESH"));
        assertTrue(cache.hasAlias(selected, nativeKey));
        assertTrue(cache.hasAlias(otherRadio, fallback));
        assertTrue(cache.hasAlias(otherProtocol));
    }

    @Test
    void nxdnChannelFallbackForgetKeepsTypeCAndTypeDAddressDomainsSeparate()
    {
        var cache = new TalkerAliasManager();
        var typeC = NXDNRadioIdentifier.createFrom(501);
        var typeD = NXDNRadioIdentifier.createFrom(501);
        typeD.setTypeD(true);
        String suffix = ":channel:00000000-0000-0000-0000-000000000001";
        String nativeKey = "nxdn-c:local:17";
        cache.update(typeC, new io.github.dsheirer.module.decode.nxdn.identifier.NXDNTalkerAliasIdentifier("OLD"), "nxdn-c" + suffix);
        cache.update(typeD, new io.github.dsheirer.module.decode.nxdn.identifier.NXDNTalkerAliasIdentifier("OLD"), "nxdn-d" + suffix);
        cache.update(typeC, new io.github.dsheirer.module.decode.nxdn.identifier.NXDNTalkerAliasIdentifier("NATIVE"), nativeKey);

        assertFallbackForgetAndFreshLearning(cache, typeC, "nxdn-c" + suffix,
            new io.github.dsheirer.module.decode.nxdn.identifier.NXDNTalkerAliasIdentifier("FRESH"));
        assertTrue(cache.hasAlias(typeD, "nxdn-d" + suffix));
        assertTrue(cache.hasAlias(typeC, nativeKey));
        assertFallbackForgetAndFreshLearning(cache, typeD, "nxdn-d" + suffix,
            new io.github.dsheirer.module.decode.nxdn.identifier.NXDNTalkerAliasIdentifier("FRESH"));
        assertTrue(cache.hasAlias(typeC, "nxdn-c" + suffix));
        assertTrue(cache.hasAlias(typeC, nativeKey));
    }

    private static void assertFallbackForgetAndFreshLearning(TalkerAliasManager cache, RadioIdentifier radio,
                                                            String fallback, TalkerAliasIdentifier fresh)
    {
        var identity = io.github.dsheirer.module.decode.traffic.RadioSystemIdentityKey.parse("v1-r-x-x-501");
        cache.forget(fallback, identity, "STALE");
        assertTrue(cache.hasAlias(radio, fallback));
        cache.forget(fallback, identity, "OLD");
        assertTrue(!cache.hasAlias(radio, fallback));
        cache.update(radio, fresh, fallback);
        cache.forget(fallback, identity, "OLD");
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
        identifiers.update(radio);
        cache.enrichMutable(identifiers, fallback);
        assertAlias(fresh, talkerAlias(identifiers));
    }

    @Test
    void forgetsOnlyTheSelectedObservedNameAndAllowsFreshLearning()
    {
        TalkerAliasManager manager = new TalkerAliasManager();
        var radio = APCO25RadioIdentifier.createFrom(750003);
        var foreign = APCO25FullyQualifiedRadioIdentifier.createFrom(750003, 0xABCDE, 0x321, 750003);
        var identity = io.github.dsheirer.module.decode.traffic.RadioSystemIdentityKey.parse("v1-r-bee00-123-750003");
        var alias = P25TalkerAliasIdentifier.create("Observed name");
        manager.update(radio, alias, "p25:bee00:123");
        manager.update(radio, alias, "p25:bee00:124");
        manager.update(foreign, alias, "p25:bee00:123");
        manager.forget("p25:bee00:123", identity, "Different name");
        assertTrue(manager.hasAlias(radio, "p25:bee00:123"));
        manager.forget("p25:bee00:123", identity, "Observed name");
        assertTrue(!manager.hasAlias(radio, "p25:bee00:123"));
        assertTrue(manager.hasAlias(radio, "p25:bee00:124"));
        assertTrue(manager.hasAlias(foreign, "p25:bee00:123"));
        manager.update(radio, P25TalkerAliasIdentifier.create("Fresh name"), "p25:bee00:123");
        manager.forget("p25:bee00:123", identity, "Observed name");
        assertTrue(manager.hasAlias(radio, "p25:bee00:123"));
    }

    @Test
    void forgetDiscardsAnOldNameAlreadyInFlightAndKeepsFreshLearning() throws Exception
    {
        assertDelayedUpdateAcrossForget(true, "OLD", true, "FRESH");
    }

    @Test
    void forgetPreservesAnUnrelatedUpdateAlreadyInFlight() throws Exception
    {
        assertDelayedUpdateAcrossForget(false, "UNRELATED", false, null);
    }

    @Test
    void forgetPreservesADifferentNameAlreadyInFlight() throws Exception
    {
        assertDelayedUpdateAcrossForget(true, "REPLACEMENT", false, "REPLACEMENT");
    }

    private static void assertDelayedUpdateAcrossForget(boolean selectedRadio, String delayedName,
                                                        boolean learnAfterForget, String expectedSelected)
        throws Exception
    {
        TalkerAliasManager manager = new TalkerAliasManager();
        var selected = APCO25RadioIdentifier.createFrom(501);
        var unrelated = APCO25RadioIdentifier.createFrom(502);
        var retained = APCO25RadioIdentifier.createFrom(503);
        String system = "p25:bee00:123";
        manager.update(selected, P25TalkerAliasIdentifier.create("OLD"), system);
        manager.update(retained, P25TalkerAliasIdentifier.create("KEPT"), system);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        TalkerAliasIdentifier delayed = new TalkerAliasIdentifier(delayedName)
        {
            @Override
            public io.github.dsheirer.protocol.Protocol getProtocol()
            {
                return io.github.dsheirer.protocol.Protocol.APCO25;
            }

            @Override
            public boolean isValid()
            {
                entered.countDown();
                try
                {
                    return resume.await(5, TimeUnit.SECONDS) && super.isValid();
                }
                catch(InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        };
        try(var executor = Executors.newSingleThreadExecutor())
        {
            var updating = executor.submit(() -> manager.update(selectedRadio ? selected : unrelated, delayed, system));
            try
            {
                assertTrue(entered.await(1, TimeUnit.SECONDS));
                manager.forget(system,
                    io.github.dsheirer.module.decode.traffic.RadioSystemIdentityKey.parse("v1-r-bee00-123-501"), "OLD");
                if(learnAfterForget) manager.update(selected, P25TalkerAliasIdentifier.create("FRESH"), system);
            }
            finally
            {
                resume.countDown();
            }
            updating.get(1, TimeUnit.SECONDS);
        }
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
        identifiers.update(selected);
        manager.enrichMutable(identifiers, system);
        assertEquals(expectedSelected, TalkerAliasIdentifier.valueFor(identifiers));
        identifiers.update(retained);
        manager.enrichMutable(identifiers, system);
        assertEquals("KEPT", TalkerAliasIdentifier.valueFor(identifiers));
        if(!selectedRadio)
        {
            identifiers.update(unrelated);
            manager.enrichMutable(identifiers, system);
            assertEquals(delayedName, TalkerAliasIdentifier.valueFor(identifiers));
        }
    }

    @Test
    void removesPreviousAliasWhenFromRadioChanges()
    {
        TalkerAliasManager manager = new TalkerAliasManager();
        RadioIdentifier firstRadio = APCO25RadioIdentifier.createFrom(1_880_231);
        RadioIdentifier consoleRadio = APCO25RadioIdentifier.createFrom(1_102);
        TalkerAliasIdentifier firstAlias = P25TalkerAliasIdentifier.create("CDP #0231");
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
        manager.update(firstRadio, firstAlias);
        identifiers.update(firstRadio);

        manager.enrichMutable(identifiers);
        assertAlias(firstAlias, talkerAlias(identifiers));

        identifiers.update(consoleRadio);
        manager.enrichMutable(identifiers);

        assertEquals(consoleRadio, identifiers.getFromIdentifier());
        assertNull(talkerAlias(identifiers));
    }

    @Test
    void replacesPreviousAliasWithAliasForCurrentRadio()
    {
        TalkerAliasManager manager = new TalkerAliasManager();
        RadioIdentifier firstRadio = APCO25RadioIdentifier.createFrom(1_880_231);
        RadioIdentifier secondRadio = APCO25RadioIdentifier.createFrom(1_880_292);
        TalkerAliasIdentifier firstAlias = P25TalkerAliasIdentifier.create("CDP #0231");
        TalkerAliasIdentifier secondAlias = P25TalkerAliasIdentifier.create("CDP #0292");
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
        manager.update(firstRadio, firstAlias);
        manager.update(secondRadio, secondAlias);
        identifiers.update(firstRadio);
        manager.enrichMutable(identifiers);
        identifiers.update(secondRadio);

        manager.enrichMutable(identifiers);

        assertAlias(secondAlias, talkerAlias(identifiers));
    }

    @Test
    void canonicalHomeAndOrdinaryNativeAliasShareOneCacheEntry()
    {
        TalkerAliasManager manager = new TalkerAliasManager();
        var canonical = APCO25FullyQualifiedRadioIdentifier.createFrom(10_900_077,
            0xBEE00, 0x123, 10_900_077);
        var nativeRadio = APCO25RadioIdentifier.createFrom(10_900_077);
        var first = P25TalkerAliasIdentifier.create("UNIT 77");
        var revised = P25TalkerAliasIdentifier.create("UNIT 77 NEW");
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
        manager.update(canonical, first, "p25:bee00:123");
        identifiers.update(nativeRadio);
        manager.enrichMutable(identifiers, 0xBEE00, 0x123);
        assertAlias(first, talkerAlias(identifiers));

        manager.update(nativeRadio, revised, "p25:bee00:123");
        identifiers.update(canonical);
        manager.enrichMutable(identifiers, "p25:bee00:123");
        assertAlias(revised, talkerAlias(identifiers));

        identifiers.update(nativeRadio);
        manager.enrichMutable(identifiers);
        assertNull(talkerAlias(identifiers), "Unknown serving scope must not borrow a canonical name");
        manager.enrichMutable(identifiers, "p25:bee00:124");
        assertNull(talkerAlias(identifiers));
    }

    @Test
    void foreignSameNumberAndReusedWorkingAddressesDoNotContaminateNativeAliases()
    {
        TalkerAliasManager manager = new TalkerAliasManager();
        var home = APCO25FullyQualifiedRadioIdentifier.createFrom(501, 0xBEE00, 0x123, 501);
        var visiting = APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(501,
            0xABCDE, 0x321, 501);
        var reassigned = APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(501,
            0xABCDE, 0x321, 502);
        var homeAlias = P25TalkerAliasIdentifier.create("HOME");
        var visitingAlias = P25TalkerAliasIdentifier.create("VISITOR");
        var reassignedAlias = P25TalkerAliasIdentifier.create("NEXT VISITOR");
        manager.update(home, homeAlias, "p25:bee00:123");
        manager.update(visiting, visitingAlias, "p25:bee00:123");
        manager.update(reassigned, reassignedAlias, "p25:bee00:123");
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
        identifiers.update(APCO25RadioIdentifier.createFrom(501));
        manager.enrichMutable(identifiers, "p25:bee00:123");
        assertAlias(homeAlias, talkerAlias(identifiers));
        identifiers.update(visiting);
        manager.enrichMutable(identifiers, "p25:bee00:123");
        assertAlias(visitingAlias, talkerAlias(identifiers));
        identifiers.update(reassigned);
        manager.enrichMutable(identifiers, "p25:bee00:123");
        assertAlias(reassignedAlias, talkerAlias(identifiers));
        identifiers.update(APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(777,
            0xABCDE, 0x321, 501));
        manager.enrichMutable(identifiers, "p25:bee00:123");
        assertAlias(visitingAlias, talkerAlias(identifiers));
        identifiers.update(APCO25RadioIdentifier.createFrom(777));
        manager.enrichMutable(identifiers, "p25:bee00:123");
        assertNull(talkerAlias(identifiers));
    }

    @Test
    void dmrAndNxdnKeepTheirNumericAliasBehavior()
    {
        TalkerAliasManager manager = new TalkerAliasManager();
        var dmr = DMRRadio.createFrom(501);
        var nxdn = NXDNRadioIdentifier.createFrom(501);
        var dmrAlias = DmrTalkerAliasIdentifier.create("DMR");
        var nxdnAlias = new io.github.dsheirer.module.decode.nxdn.identifier.NXDNTalkerAliasIdentifier("NXDN");
        manager.update(dmr, dmrAlias);
        manager.update(nxdn, nxdnAlias);
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
        identifiers.update(dmr);
        manager.enrichMutable(identifiers);
        assertAlias(dmrAlias, talkerAlias(identifiers));
        identifiers.remove(IdentifierClass.USER, Form.RADIO, Role.FROM);
        identifiers.update(nxdn);
        manager.enrichMutable(identifiers);
        assertAlias(nxdnAlias, talkerAlias(identifiers));
    }

    @Test
    void aliasProjectionDoesNotWaitForAContendedManagerMonitor() throws Exception
    {
        TalkerAliasManager manager = new TalkerAliasManager();
        var radio = APCO25RadioIdentifier.createFrom(501);
        var alias = P25TalkerAliasIdentifier.create("HOME");
        manager.update(radio, alias, "p25:bee00:123");
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
        identifiers.update(radio);
        try(var executor = Executors.newSingleThreadExecutor())
        {
            synchronized(manager)
            {
                executor.submit(() -> manager.update(radio, alias, "p25:bee00:123"))
                    .get(1, TimeUnit.SECONDS);
                executor.submit(() -> manager.enrichMutable(identifiers, 0xBEE00, 0x123))
                    .get(1, TimeUnit.SECONDS);
            }
        }
        assertAlias(alias, talkerAlias(identifiers));
    }

    @Test
    void lateCanonicalCompletionCanBeEnrichedUsingTheConfirmedHomeServingScope()
    {
        TalkerAliasManager manager = new TalkerAliasManager();
        var home = APCO25FullyQualifiedRadioIdentifier.createFrom(501, 0xBEE00, 0x123, 501);
        var nativeRadio = APCO25RadioIdentifier.createFrom(501);
        manager.update(home, P25TalkerAliasIdentifier.create("HOME"));
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
        identifiers.update(nativeRadio);
        manager.enrichMutable(identifiers, 0xBEE00, 0x123);
        assertNotNull(TalkerAliasIdentifier.find(identifiers));
        assertEquals("HOME", TalkerAliasIdentifier.valueFor(identifiers));
        manager.enrichMutable(identifiers, 0xBEE00, 0x124);
        assertNull(talkerAlias(identifiers));
    }

    @Test
    void anAlreadyOwnedNameCannotPoisonAnotherRadiosCacheEntry()
    {
        TalkerAliasManager manager = new TalkerAliasManager();
        var first = APCO25RadioIdentifier.createFrom(101);
        var second = APCO25RadioIdentifier.createFrom(102);
        var alias = P25TalkerAliasIdentifier.create("FIRST").bindTo(first);
        manager.update(second, alias);
        assertEquals(false, manager.hasAlias(second));
        manager.update(first, alias);
        assertTrue(manager.hasAlias(first));
    }

    @Test
    void nxdnTypeCAndTypeDHaveSeparateCacheEntriesEvenWithEqualNumbers()
    {
        TalkerAliasManager manager = new TalkerAliasManager();
        var typeC = NXDNRadioIdentifier.createFrom(101);
        var typeD = NXDNRadioIdentifier.createTypeDFrom(101);
        manager.update(typeC, new io.github.dsheirer.module.decode.nxdn.identifier.NXDNTalkerAliasIdentifier("TYPE C"));
        manager.update(typeD, new io.github.dsheirer.module.decode.nxdn.identifier.NXDNTalkerAliasIdentifier("TYPE D"));
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
        identifiers.update(typeC);
        manager.enrichMutable(identifiers);
        assertEquals("TYPE C", TalkerAliasIdentifier.valueFor(identifiers));
        identifiers.update(typeD);
        manager.enrichMutable(identifiers);
        assertEquals("TYPE D", TalkerAliasIdentifier.valueFor(identifiers));
    }

    @Test
    void exactCanonicalCacheOwnerCanFollowTheSameSubscriberBetweenServingSystems()
    {
        TalkerAliasManager manager = new TalkerAliasManager();
        var visitor = APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(101, 0xABCDE, 0x321, 501);
        manager.update(visitor, P25TalkerAliasIdentifier.create("VISITOR"), "p25:bee00:123");
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
        identifiers.update(visitor);
        manager.enrichMutable(identifiers, "p25:bee00:124");
        assertEquals("VISITOR", TalkerAliasIdentifier.valueFor(identifiers));
        identifiers.update(APCO25RadioIdentifier.createFrom(101));
        manager.enrichMutable(identifiers, "p25:bee00:124");
        assertNull(talkerAlias(identifiers));
    }

    @Test
    void completedNamesCannotLeakBetweenNativeDmrOrNxdnSystemsOrIntoUnknownScope()
    {
        for(var radio: java.util.List.of(DMRRadio.createFrom(101), NXDNRadioIdentifier.createFrom(101)))
        {
            TalkerAliasManager manager = new TalkerAliasManager();
            TalkerAliasIdentifier raw = radio instanceof DMRRadio ? DmrTalkerAliasIdentifier.create("SYSTEM A") :
                new io.github.dsheirer.module.decode.nxdn.identifier.NXDNTalkerAliasIdentifier("SYSTEM A");
            var owned = raw.bindTo(radio, "native-system-a");
            manager.update(radio, owned); //Omitted caller scope must preserve the observation's native owner.
            MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
            identifiers.update(radio);
            manager.enrichMutable(identifiers, "native-system-a");
            assertEquals("SYSTEM A", TalkerAliasIdentifier.valueFor(identifiers));
            manager.enrichMutable(identifiers, "native-system-b");
            assertNull(talkerAlias(identifiers));
            manager.enrichMutable(identifiers);
            assertNull(talkerAlias(identifiers));
        }
    }

    @Test
    void aChannelFallbackDoesNotPretendToBeALearnedNativeSystem()
    {
        TalkerAliasManager manager = new TalkerAliasManager();
        var radio = DMRRadio.createFrom(101);
        String configurationId = "d34c73ce-4f85-491f-a0cf-86525c82352b";
        String fallback = "dmr:channel:" + configurationId;
        var alias = DmrTalkerAliasIdentifier.create("UNIT").bindTo(radio, fallback, configurationId);
        manager.update(radio, alias, fallback);
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
        identifiers.update(radio);
        manager.enrichMutable(identifiers);
        assertEquals("UNIT", TalkerAliasIdentifier.valueFor(identifiers));
        var learned = alias.bindTo(radio, "dmr:tier3:small:1", configurationId);
        assertNotNull(learned, "New system evidence refines an unknown owner during the same call");
        assertTrue(learned.matchesSource(radio, fallback, configurationId));
        assertNull(learned.bindTo(radio, "dmr:tier3:small:2", configurationId));
    }

    @Test
    void retiringAReceptionGenerationLeavesHistoricalSnapshotsIntact() throws Exception
    {
        TalkerAliasManager manager = new TalkerAliasManager();
        var radio = NXDNRadioIdentifier.createFrom(101);
        manager.update(radio, new io.github.dsheirer.module.decode.nxdn.identifier.NXDNTalkerAliasIdentifier("OLD"));
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
        identifiers.update(radio);
        manager.enrichMutable(identifiers);
        var historical = identifiers.copyOf();
        try(var executor = Executors.newSingleThreadExecutor())
        {
            synchronized(manager)
            {
                executor.submit(manager::clear).get(1, TimeUnit.SECONDS);
            }
        }
        manager.enrichMutable(identifiers);
        assertNull(talkerAlias(identifiers));
        assertEquals("OLD", TalkerAliasIdentifier.valueFor(historical));
    }

    @Test
    void anUpdateAlreadyInFlightCannotRepopulateARetiredReceptionGeneration() throws Exception
    {
        TalkerAliasManager manager = new TalkerAliasManager();
        var radio = DMRRadio.createFrom(101);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        var old = new TalkerAliasIdentifier("OLD")
        {
            @Override
            public io.github.dsheirer.protocol.Protocol getProtocol()
            {
                return io.github.dsheirer.protocol.Protocol.DMR;
            }

            @Override
            public boolean isValid()
            {
                entered.countDown();
                try
                {
                    return resume.await(1, TimeUnit.SECONDS) && super.isValid();
                }
                catch(InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        };
        try(var executor = Executors.newSingleThreadExecutor())
        {
            var updating = executor.submit(() -> manager.update(radio, old));
            try
            {
                assertTrue(entered.await(1, TimeUnit.SECONDS));
                manager.clear();
                manager.update(radio, DmrTalkerAliasIdentifier.create("NEW"));
            }
            finally
            {
                resume.countDown();
            }
            updating.get(1, TimeUnit.SECONDS);
        }
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
        identifiers.update(radio);
        manager.enrichMutable(identifiers);
        assertEquals("NEW", TalkerAliasIdentifier.valueFor(identifiers));
    }

    private static void assertAlias(TalkerAliasIdentifier expected, TalkerAliasIdentifier actual)
    {
        assertNotNull(actual);
        assertTrue(actual.isBound());
        assertEquals(expected.getValue(), actual.getValue());
    }

    private static TalkerAliasIdentifier talkerAlias(MutableIdentifierCollection identifiers)
    {
        return (TalkerAliasIdentifier)identifiers.getIdentifier(IdentifierClass.USER, Form.TALKER_ALIAS, Role.FROM);
    }
}
