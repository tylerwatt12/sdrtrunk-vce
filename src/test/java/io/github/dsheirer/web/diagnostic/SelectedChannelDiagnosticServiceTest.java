/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */

package io.github.dsheirer.web.diagnostic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.application.service.LiveContext;
import io.github.dsheirer.application.service.LiveContextResolver;
import io.github.dsheirer.channel.metadata.activity.ChannelActivitySelectionDescriptor;
import io.github.dsheirer.channel.metadata.activity.ChannelActivitySelectionScope;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.ChannelProcessingManager;
import io.github.dsheirer.dsp.symbol.stream.SelectedChannelSymbolSource;
import io.github.dsheirer.dsp.symbol.stream.SymbolFrame;
import io.github.dsheirer.module.ProcessingChain;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.FeedbackDecoder;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.web.diagnostic.SelectedChannelDiagnosticService.OpenResult;
import io.github.dsheirer.web.diagnostic.SelectedChannelDiagnosticService.OpenStatus;
import io.github.dsheirer.web.diagnostic.SelectedChannelDiagnosticService.StateType;
import io.github.dsheirer.web.diagnostic.SelectedChannelDiagnosticService.View;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SelectedChannelDiagnosticServiceTest
{
    private static final String SELECTION_ID = "exact-selected-channel";

    @Test
    void busyOpenNeverAttachesASecondSourceAndOneSocketSwitchesInPlace() throws Exception
    {
        Fixture fixture = fixture();

        try(SelectedChannelDiagnosticService service = new SelectedChannelDiagnosticService(fixture.resolver(),
            new SelectedChannelDiagnosticService.Configuration(Duration.ofMillis(10), "diagnostic-test-refresh")))
        {
            OpenResult first = service.tryOpen(SELECTION_ID, View.SYMBOLS, 1);

            assertEquals(OpenStatus.OPEN, first.status());
            assertEquals(1, fixture.decoder().getSymbolObserverCount());
            assertEquals(StateType.LIVE, first.session().state().state());

            OpenResult rejected = service.tryOpen(SELECTION_ID, View.SYMBOLS, 2);

            assertEquals(OpenStatus.BUSY, rejected.status());
            assertNull(rejected.session());
            assertEquals(1, fixture.decoder().getSymbolObserverCount(),
                "a rejected opener must never touch the processing chain");

            broadcastBatch(fixture.decoder(), 0.5f);
            SymbolFrame frame = first.session().pollSymbols(Duration.ZERO);
            assertNotNull(frame);
            assertEquals(0, frame.getGeneration());

            first.session().update(View.SIGNAL, 3);
            assertEquals(0, fixture.decoder().getSymbolObserverCount());
            assertEquals(StateType.UNSUPPORTED, first.session().state().state());
            assertEquals(1, first.session().state().generation());

            first.session().update(View.SYMBOLS, 4);
            assertEquals(1, fixture.decoder().getSymbolObserverCount());
            assertEquals(StateType.LIVE, first.session().state().state());
            assertEquals(2, first.session().state().generation());

            first.session().close();
            assertEquals(0, fixture.decoder().getSymbolObserverCount());
            assertEquals(0, service.getActiveSessionCount());
        }
    }

    @Test
    void endingAnExactSelectionDetachesBeforePublishingEnded() throws Exception
    {
        Fixture fixture = fixture();

        try(SelectedChannelDiagnosticService service = new SelectedChannelDiagnosticService(fixture.resolver(),
            new SelectedChannelDiagnosticService.Configuration(Duration.ofMillis(5), "diagnostic-ended-test")))
        {
            SelectedChannelDiagnosticService.Session session =
                service.tryOpen(SELECTION_ID, View.SYMBOLS, 1).session();
            assertEquals(1, fixture.decoder().getSymbolObserverCount());
            fixture.resolver().setContext(null);

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);

            while(session.state().state() != StateType.ENDED && System.nanoTime() < deadline)
            {
                Thread.sleep(5);
            }

            assertEquals(StateType.ENDED, session.state().state());
            assertEquals(0, fixture.decoder().getSymbolObserverCount());
            assertEquals("Metro / Downtown", session.state().context().tableTitle(),
                "the final state retains the last useful channel label");
        }
    }

    @Test
    void invalidOrEndedSelectionDoesNotClaimTheService()
    {
        Fixture fixture = fixture();
        fixture.resolver().setContext(null);

        try(SelectedChannelDiagnosticService service = new SelectedChannelDiagnosticService(fixture.resolver()))
        {
            assertEquals(OpenStatus.ENDED, service.tryOpen(SELECTION_ID, View.SYMBOLS, 1).status());
            assertEquals(OpenStatus.INVALID, service.tryOpen("", View.SYMBOLS, 1).status());
            assertEquals(0, service.getActiveSessionCount());
            assertEquals(0, fixture.decoder().getSymbolObserverCount());
        }
    }

    private static Fixture fixture()
    {
        Channel channel = new Channel("Dispatch");
        ProcessingChain processingChain = new ProcessingChain(channel, new AliasModel());
        TestDecoder decoder = new TestDecoder();
        processingChain.addModule(decoder);
        ChannelActivitySelectionDescriptor selection = new ChannelActivitySelectionDescriptor(SELECTION_ID,
            "table-1", "row-1", "Metro / Downtown", "Dispatch",
            ChannelActivitySelectionScope.EXACT_FREQUENCY, null, channel.getChannelID(), 851_012_500L, 1, "DMR");
        LiveContext context = new LiveContext(selection, null, channel, processingChain, processingChain);
        MutableContextResolver resolver = new MutableContextResolver(context);
        assertSame(decoder, processingChain.getPrimaryDecoder());
        return new Fixture(decoder, resolver);
    }

    private static void broadcastBatch(TestDecoder decoder, float value)
    {
        for(int x = 0; x < SelectedChannelSymbolSource.BATCH_SIZE; x++)
        {
            decoder.broadcast(value);
        }
    }

    private record Fixture(TestDecoder decoder, MutableContextResolver resolver)
    {
    }

    private static final class TestDecoder extends FeedbackDecoder
    {
        @Override
        public String getProtocolDescription()
        {
            return "DMR test decoder";
        }

        @Override
        public DecoderType getDecoderType()
        {
            return DecoderType.DMR;
        }
    }

    private static final class MutableContextResolver extends LiveContextResolver
    {
        private final AtomicReference<LiveContext> mContext;

        private MutableContextResolver(LiveContext context)
        {
            super(new ChannelProcessingManager(null, null, null, null, new UserPreferences()));
            mContext = new AtomicReference<>(context);
        }

        private void setContext(LiveContext context)
        {
            mContext.set(context);
        }

        @Override
        public Optional<LiveContext> resolve(String selectionId)
        {
            LiveContext context = mContext.get();
            return context != null && context.selectionId().equals(selectionId) ? Optional.of(context) :
                Optional.empty();
        }
    }
}
