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
package io.github.dsheirer.alias;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.alias.id.AliasID;
import io.github.dsheirer.alias.id.radio.Radio;
import io.github.dsheirer.alias.id.radio.RadioRange;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.alias.id.talkgroup.TalkgroupRange;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.module.decode.dmr.identifier.DMRRadio;
import io.github.dsheirer.module.decode.dmr.identifier.DMRTalkgroup;
import io.github.dsheirer.module.decode.nxdn.identifier.NXDNRadioIdentifier;
import io.github.dsheirer.module.decode.nxdn.identifier.NXDNTalkgroupIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.protocol.Protocol;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class NumericAliasIndexTest
{
    private static final List<MatcherKind> KINDS = List.of(
        new MatcherKind(Protocol.APCO25, false), new MatcherKind(Protocol.APCO25, true),
        new MatcherKind(Protocol.DMR, false), new MatcherKind(Protocol.DMR, true),
        new MatcherKind(Protocol.NXDN, false), new MatcherKind(Protocol.NXDN, true));

    @Test
    void exactMatchesAndRangeTiesKeepTheirWinnerAfterRemovalAndReordering()
    {
        for(MatcherKind kind: KINDS)
        {
            AliasList list = kind.aliasList();
            Alias broad = kind.range("Broad", 1, 1_000);
            Alias narrow = kind.range("Narrow", 100, 200);
            Alias first = kind.range("First tied range", 100, 300);
            Alias second = kind.range("Second tied range", 100, 300);
            Alias exactFirst = kind.exact("First exact", 150);
            Alias exactSecond = kind.exact("Second exact", 150);
            list.addAliases(List.of(broad, first, second, narrow, exactFirst, exactSecond));

            assertSame(exactSecond, match(list, kind, 150), kind.toString());
            assertTrue(exactFirst.getMatchIdentifier().overlapProperty().get());
            assertTrue(exactSecond.getMatchIdentifier().overlapProperty().get());
            list.removeAlias(exactSecond);
            assertSame(exactFirst, match(list, kind, 150));
            assertFalse(exactFirst.getMatchIdentifier().overlapProperty().get());
            list.removeAlias(exactFirst);
            assertSame(second, match(list, kind, 150), "Equal bounds use the last configured alias");
            list.removeAlias(second);
            assertSame(first, match(list, kind, 150), "Same minimum prefers the larger maximum");
            list.removeAlias(first);
            assertSame(narrow, match(list, kind, 150), "The latest starting matching range wins");
            assertSame(broad, match(list, kind, 201));
            assertTrue(list.getAliases(kind.identifier(1_001)).isEmpty());

            list.addAliases(List.of(second, first));
            assertSame(first, match(list, kind, 150), "Re-adding ties honors their current order");
            first.setMatchIdentifier(kind.range("Moved", 2_000, 2_100).getMatchIdentifier());
            assertSame(second, match(list, kind, 150));
            assertSame(first, match(list, kind, 2_050));
        }
    }

    @Test
    void sparseAndOverlappingRangesMatchAnExhaustiveOracleForEveryProtocolAndAddressKind()
    {
        for(MatcherKind kind: KINDS)
        {
            AliasList list = kind.aliasList();
            List<ExpectedRange> ranges = new ArrayList<>();
            Random random = new Random(0xA11A5L);
            for(int index = 0; index < 96; index++)
            {
                int minimum = 1 + random.nextInt(4_000);
                int maximum = minimum + 1 + random.nextInt(index % 3 == 0 ? 1_000 : 5);
                ranges.add(new ExpectedRange(minimum, maximum, index,
                    kind.range("Range " + index, minimum, maximum)));
            }
            list.addAliases(ranges.stream().map(ExpectedRange::alias).toList());
            Alias exact = kind.exact("Exact within ranges", ranges.getFirst().minimum());
            list.addAlias(exact);

            for(ExpectedRange range: ranges)
            {
                boolean overlaps = ranges.stream().anyMatch(other -> range != other &&
                    range.minimum() <= other.maximum() && other.minimum() <= range.maximum());
                assertEquals(overlaps, range.alias().getMatchIdentifier().overlapProperty().get(),
                    kind + " " + range);
            }

            Comparator<ExpectedRange> precedence = Comparator.comparingInt(ExpectedRange::minimum)
                .thenComparingInt(ExpectedRange::maximum).thenComparingInt(ExpectedRange::order);
            for(int value = 1; value <= 5_000; value++)
            {
                int address = value;
                Alias expected = address == ranges.getFirst().minimum() ? exact : ranges.stream()
                    .filter(range -> range.minimum() <= address && address <= range.maximum())
                    .max(precedence).map(ExpectedRange::alias).orElse(null);
                List<Alias> found = list.getAliases(kind.identifier(value));
                assertEquals(expected == null ? List.of() : List.of(expected), found, kind + " " + value);
            }
        }
    }

    @Test
    void readersKeepThePreparedSnapshotWhileAnOverlappingEditIsStillBuilding() throws Exception
    {
        for(MatcherKind kind: KINDS)
        {
            AliasList list = kind.aliasList();
            Alias original = kind.range("Original", 100, 300);
            Alias replacement = kind.range("Replacement", 200, 400);
            list.addAlias(original);
            CountDownLatch building = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            replacement.getMatchIdentifier().overlapProperty().addListener((_, _, overlaps) ->
            {
                if(overlaps)
                {
                    building.countDown();
                    try
                    {
                        assertTrue(release.await(5, TimeUnit.SECONDS), "Test must release the index builder");
                    }
                    catch(InterruptedException e)
                    {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(e);
                    }
                }
            });

            try(var executor = Executors.newSingleThreadExecutor())
            {
                var edit = executor.submit(() -> list.addAlias(replacement));
                try
                {
                    assertTrue(building.await(5, TimeUnit.SECONDS));
                    assertTimeoutPreemptively(Duration.ofSeconds(1), () ->
                    {
                        assertSame(original, match(list, kind, 250));
                        assertTrue(list.getAliases(kind.identifier(350)).isEmpty());
                    }, "Alias readers must not wait for edits or observe an unprepared range index");
                }
                finally
                {
                    release.countDown();
                }
                edit.get(5, TimeUnit.SECONDS);
            }
            assertSame(replacement, match(list, kind, 250));
            assertSame(replacement, match(list, kind, 350));
        }
    }

    private static Alias match(AliasList list, MatcherKind kind, int value)
    {
        List<Alias> aliases = list.getAliases(kind.identifier(value));
        assertEquals(1, aliases.size(), kind + " " + value);
        return aliases.getFirst();
    }

    private record ExpectedRange(int minimum, int maximum, int order, Alias alias)
    {
    }

    private record MatcherKind(Protocol protocol, boolean radio)
    {
        private AliasList aliasList()
        {
            AliasListFamily family = switch(protocol)
            {
                case APCO25 -> AliasListFamily.P25;
                case DMR -> AliasListFamily.DMR;
                case NXDN -> AliasListFamily.NXDN;
                default -> throw new IllegalArgumentException();
            };
            return new AliasList(new AliasListDefinition(toString(), family));
        }

        private Alias exact(String name, int value)
        {
            return alias(name, radio ? new Radio(protocol, value) : new Talkgroup(protocol, value));
        }

        private Alias range(String name, int minimum, int maximum)
        {
            return alias(name, radio ? new RadioRange(protocol, minimum, maximum) :
                new TalkgroupRange(protocol, minimum, maximum));
        }

        private Alias alias(String name, AliasID matcher)
        {
            Alias alias = new Alias(name);
            alias.setMatchIdentifier(matcher);
            return alias;
        }

        private Identifier<?> identifier(int value)
        {
            return switch(protocol)
            {
                case APCO25 -> radio ? APCO25RadioIdentifier.createFrom(value) : APCO25Talkgroup.create(value);
                case DMR -> radio ? DMRRadio.createFrom(value) : DMRTalkgroup.create(value);
                case NXDN -> radio ? NXDNRadioIdentifier.createFrom(value) : NXDNTalkgroupIdentifier.createTo(value);
                default -> throw new IllegalArgumentException();
            };
        }
    }
}
