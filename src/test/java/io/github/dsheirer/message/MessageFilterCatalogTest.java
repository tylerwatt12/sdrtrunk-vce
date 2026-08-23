/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.bits.BinaryMessage;
import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.filter.AllPassFilter;
import io.github.dsheirer.filter.Filter;
import io.github.dsheirer.filter.FilterCatalog;
import io.github.dsheirer.filter.FilterElement;
import io.github.dsheirer.filter.FilterSet;
import io.github.dsheirer.filter.IFilter;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.module.decode.DecoderFactory;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.dmr.message.data.packet.DMRPacketMessage;
import io.github.dsheirer.module.decode.dmr.message.data.packet.UDTShortMessageService;
import io.github.dsheirer.module.decode.dmr.message.data.SlotType;
import io.github.dsheirer.module.decode.dmr.message.data.header.UDTHeader;
import io.github.dsheirer.module.decode.dmr.sync.DMRSyncPattern;
import io.github.dsheirer.module.decode.p25.phase1.message.lc.LinkControlOpcode;
import io.github.dsheirer.module.decode.p25.phase1.message.pdu.PDUMessage;
import io.github.dsheirer.module.decode.tait.Tait1200ANIMessage;
import io.github.dsheirer.module.decode.tait.Tait1200GPSMessage;
import io.github.dsheirer.protocol.Protocol;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class MessageFilterCatalogTest
{
    @Test
    void everyActiveDecoderTreeProducesAStableCatalogWithGloballyUniqueKeys()
    {
        LinkedHashSet<DecoderType> decoderTypes = new LinkedHashSet<>(DecoderType.PRIMARY_DECODERS);
        decoderTypes.addAll(DecoderType.AUX_DECODERS);

        for(DecoderType decoderType: decoderTypes)
        {
            FilterSet<IMessage> firstTree = filters(decoderType);
            assertComplete(firstTree);
            MessageFilterCatalog.Classifier first = MessageFilterCatalog.fromFilterSet(firstTree,
                new int[]{2, 1, 2, 0});
            MessageFilterCatalog.Classifier second = MessageFilterCatalog.fromFilterSet(filters(decoderType),
                new int[]{2, 1, 2, 0});

            assertEquals(first.catalog(), second.catalog(), decoderType + " catalog must be deterministic");
            assertEquals(List.of(1, 2), first.catalog().timeslots());
            assertFalse(first.catalog().groups().isEmpty());
            assertTrue(first.catalog().groups().stream().allMatch(group -> !group.children().isEmpty()),
                decoderType + " exposed an empty group as a selectable leaf");
            assertEquals(firstTree.getElementCount(), first.catalog().groups().stream()
                .mapToInt(MessageFilterCatalogTest::leafCount).sum(),
                decoderType + " must catalog every predefined choice before any message is observed");

            List<String> keys = new ArrayList<>();
            first.catalog().groups().forEach(group -> collectKeys(group, keys));
            assertEquals(keys.size(), new HashSet<>(keys).size(), decoderType + " catalog keys must be unique");
        }
    }

    @Test
    void combinedPrimaryAndAuxiliaryTreePreservesFactoryOrderAndDeterminism()
    {
        FilterSet<IMessage> firstTree = filters(DecoderType.P25_PHASE1, DecoderType.DCS,
            DecoderType.FLEETSYNC2, DecoderType.LJ_1200, DecoderType.MDC1200, DecoderType.TAIT_1200);
        FilterSet<IMessage> secondTree = filters(DecoderType.P25_PHASE1, DecoderType.DCS,
            DecoderType.FLEETSYNC2, DecoderType.LJ_1200, DecoderType.MDC1200, DecoderType.TAIT_1200);
        MessageFilterCatalog.Classifier first = MessageFilterCatalog.fromFilterSet(firstTree, new int[0]);
        MessageFilterCatalog.Classifier second = MessageFilterCatalog.fromFilterSet(secondTree, new int[0]);

        assertEquals(first.catalog(), second.catalog());
        assertEquals(firstTree.getFilters().stream().map(IFilter::getName).toList(),
            first.catalog().groups().stream().map(FilterCatalog.Node::label).toList());
    }

    @Test
    void repairedLegacyBranchesAreCatalogedAndClassified()
    {
        MessageFilterCatalog.Match pdu = classifier(DecoderType.P25_PHASE1).classify(
            new PDUMessage(new CorrectedBinaryMessage(200), 0, 1_000L));
        assertNotNull(pdu);
        assertEquals("Packet Data Unit (PDU)", pdu.filterLabel());

        MessageFilterCatalog.Match dmrPacket = classifier(DecoderType.DMR).classify(
            new DMRPacketMessage(null, null, new CorrectedBinaryMessage(0), 1, 1_000L));
        assertNotNull(dmrPacket);
        assertEquals("Other/Unknown", dmrPacket.filterLabel());

        UDTHeader udtHeader = new UDTHeader(DMRSyncPattern.BASE_STATION_DATA,
            new CorrectedBinaryMessage(96), null, new SlotType(new CorrectedBinaryMessage(24)), 1_000L, 1);
        MessageFilterCatalog.Match udt = classifier(DecoderType.DMR).classify(
            new UDTShortMessageService(udtHeader, new CorrectedBinaryMessage(64)));
        assertNotNull(udt);
        assertEquals("Unified Data Transport - Short Message Service", udt.filterLabel());

        MessageFilterCatalog.Classifier tait = classifier(DecoderType.TAIT_1200);
        assertEquals("Tait-1200", tait.classify(new Tait1200GPSMessage(new BinaryMessage(500))).filterLabel());
        assertEquals("Tait-1200",
            tait.classify(new Tait1200ANIMessage(new CorrectedBinaryMessage(300))).filterLabel());

        MessageFilterCatalog.Match phase2Sync = classifier(DecoderType.P25_PHASE2).classify(
            new SyncLossMessage(1_000L, 40, Protocol.APCO25_PHASE2, 1));
        assertNotNull(phase2Sync);
        assertEquals("Sync-Loss", phase2Sync.filterLabel());

        assertTrue(LinkControlOpcode.L3HARRIS_RETURN_TO_CONTROL_CHANNEL.isGrouped());
        assertTrue(LinkControlOpcode.L3HARRIS_TALKER_ALIAS_BLOCK_1.isGrouped());
    }

    @Test
    void classificationUsesTheFirstJavaFilterThatClaimsTheMessage()
    {
        TrackingFilter first = new TrackingFilter("First", "first");
        TrackingFilter second = new TrackingFilter("Second", "second");
        FilterSet<IMessage> filters = new FilterSet<>("Messages");
        filters.addFilter(first);
        filters.addFilter(second);
        filters.addFilter(new AllPassFilter<>("All Other Messages"));
        MessageFilterCatalog.Match match = MessageFilterCatalog.fromFilterSet(filters, new int[0])
            .classify(new BareMessage());

        assertNotNull(match);
        assertEquals("first", match.filterLabel());
        assertTrue(match.filterKey().startsWith("message/0/"));
        assertTrue(first.calls() > 0);
        assertEquals(0, second.calls());
    }

    @Test
    void duplicateFriendlyLabelsAreDisambiguatedWithoutChangingOpaqueKeys()
    {
        FilterSet<IMessage> filters = new FilterSet<>("Messages");
        filters.addFilter(new DirectionFilter());
        MessageFilterCatalog.Classifier classifier = MessageFilterCatalog.fromFilterSet(filters, new int[0]);
        List<FilterCatalog.Node> leaves = classifier.catalog().groups().getFirst().children();

        assertEquals(List.of("SNDCP Request (Inbound)", "SNDCP Request (Outbound)"),
            leaves.stream().map(FilterCatalog.Node::label).toList());
        assertEquals(2, leaves.stream().map(FilterCatalog.Node::key).distinct().count());
        assertEquals("SNDCP Request (Inbound)", classifier.classify(new BareMessage()).filterLabel());
    }

    private static MessageFilterCatalog.Classifier classifier(DecoderType decoderType)
    {
        return MessageFilterCatalog.fromFilterSet(filters(decoderType), new int[0]);
    }

    private static FilterSet<IMessage> filters(DecoderType... decoderTypes)
    {
        FilterSet<IMessage> filters = new FilterSet<>("Message Filters");

        for(DecoderType decoderType: decoderTypes)
        {
            filters.addFilters(DecoderFactory.getMessageFilter(decoderType));
        }

        filters.addFilter(new AllPassFilter<>("All Other Messages Filter"));
        return filters;
    }

    private static void assertComplete(IFilter<IMessage> filter)
    {
        if(filter instanceof FilterSet<?> filterSet)
        {
            @SuppressWarnings("unchecked")
            List<IFilter<IMessage>> children = ((FilterSet<IMessage>)filterSet).getFilters();
            assertFalse(children.isEmpty(), filter.getName() + " has no child filters");
            children.forEach(MessageFilterCatalogTest::assertComplete);
        }
        else if(filter instanceof Filter<?,?> leaf)
        {
            assertFalse(leaf.getFilterElements().isEmpty(), filter.getName() + " has no filter choices");
        }
    }

    private static void collectKeys(FilterCatalog.Node node, List<String> keys)
    {
        keys.add(node.key());
        node.children().forEach(child -> collectKeys(child, keys));
    }

    private static int leafCount(FilterCatalog.Node node)
    {
        return node.children().isEmpty() ? 1 : node.children().stream()
            .mapToInt(MessageFilterCatalogTest::leafCount).sum();
    }

    private static class TrackingFilter extends Filter<IMessage,String>
    {
        private final String mKey;
        private final AtomicInteger mCalls = new AtomicInteger();

        private TrackingFilter(String name, String key)
        {
            super(name);
            mKey = key;
            add(new FilterElement<>(key));
        }

        @Override
        public Function<IMessage,String> getKeyExtractor()
        {
            return message -> {
                mCalls.incrementAndGet();
                return mKey;
            };
        }

        private int calls()
        {
            return mCalls.get();
        }
    }

    private static class DirectionFilter extends Filter<IMessage,Direction>
    {
        private DirectionFilter()
        {
            super("SNDCP Messages");
            add(new FilterElement<>(Direction.INBOUND));
            add(new FilterElement<>(Direction.OUTBOUND));
        }

        @Override
        public Function<IMessage,Direction> getKeyExtractor()
        {
            return message -> Direction.INBOUND;
        }
    }

    private enum Direction
    {
        INBOUND,
        OUTBOUND;

        @Override
        public String toString()
        {
            return "SNDCP Request";
        }
    }

    private static class BareMessage implements IMessage
    {
        @Override
        public long getTimestamp()
        {
            return 0;
        }

        @Override
        public boolean isValid()
        {
            return true;
        }

        @Override
        public Protocol getProtocol()
        {
            return Protocol.UNKNOWN;
        }

        @Override
        public int getTimeslot()
        {
            return 0;
        }

        @Override
        public List<Identifier> getIdentifiers()
        {
            return List.of();
        }
    }
}
