package io.github.dsheirer.source.tuner.manager;

import io.github.dsheirer.source.SourceException;
import io.github.dsheirer.source.tuner.Tuner;
import io.github.dsheirer.source.tuner.ITunerErrorListener;
import io.github.dsheirer.source.tuner.TunerClass;
import io.github.dsheirer.source.tuner.airspy.AirspyTunerConfiguration;
import io.github.dsheirer.source.tuner.configuration.TunerConfiguration;
import io.github.dsheirer.source.tuner.test.TestTunerController;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UsbTunerReconciliationTest
{
    @Test
    void incompleteInventoryNeverRemovesOrRestartsKnownRadios()
    {
        try(Fixture manager = new Fixture())
        {
            Radio radio = new Radio(1);
            manager.reconcileUsbTuners(List.of(radio));
            assertEquals(TunerManager.USB_RESCAN_FAILED, manager.reconcileUsbTuners(List.of(), false));
            assertSame(radio, manager.getDiscoveredTunerRegistry().find(radio.getId()));
            assertEquals(1, radio.created);
            assertEquals(0, radio.closed.get());
        }
    }

    @Test
    void retiredRuntimeNotificationsCannotStopOrRemoveRecoveredRadio()
    {
        try(Fixture manager = new Fixture())
        {
            Radio radio = new Radio(1);
            manager.reconcileUsbTuners(List.of(radio));
            ITunerErrorListener old = radio.runtimeListener;
            old.setErrorMessage("disconnect");
            manager.reconcileUsbTuners(List.of(new Radio(1)));
            Tuner current = radio.getTuner();
            old.setErrorMessage("late failure");
            old.tunerRemoved();
            assertSame(current, radio.getTuner());
            assertTrue(radio.isAvailable());
            radio.runtimeListener.setErrorMessage("current failure");
            assertEquals(TunerStatus.ERROR, radio.getTunerStatus());
        }
    }

    @Test
    void reconnectTwoOfThreeRecoversErrorsAndRetainsMissingWithoutTouchingHealthyRadio()
    {
        try(Fixture manager = new Fixture())
        {
            Radio first = new Radio(1), second = new Radio(2), missing = new Radio(3), healthy = new Radio(4);
            assertEquals(4, manager.reconcileUsbTuners(List.of(first, second, missing, healthy)));
            first.setErrorMessage("USB disconnected");
            second.setErrorMessage("USB disconnected");
            assertEquals(3, manager.reconcileUsbTuners(List.of(new Radio(1), new Radio(2), new Radio(4))));
            assertSame(first, manager.getDiscoveredTunerRegistry().find(first.getId()));
            assertEquals(2, first.created);
            assertEquals(2, second.created);
            assertEquals(1, healthy.created);
            assertEquals(0, healthy.closed.get());
            assertTrue(manager.getDiscoveredTunerRegistry().contains(missing));
            assertEquals(TunerStatus.REMOVED, missing.getTunerStatus());
            assertFalse(missing.isAvailable());
            assertFalse(missing.hasTuner());
            assertEquals(1, missing.closed.get());
            assertEquals(3, manager.getAvailableTuners().size());
            assertEquals(855_000_000, first.getTuner().getTunerController().getFrequency());
        }
    }

    @Test
    void hundredsOfScansAndRecoveryCyclesLeaveExactlyOneRuntimeAndNoDuplicateEntries()
    {
        try(Fixture manager = new Fixture())
        {
            Radio radio = new Radio(1);
            manager.reconcileUsbTuners(List.of(radio));
            for(int cycle = 0; cycle < 200; cycle++)
            {
                assertEquals(0, manager.reconcileUsbTuners(List.of(new Radio(1))));
                radio.setErrorMessage("test unplug");
                assertEquals(1, manager.reconcileUsbTuners(List.of(new Radio(1))));
                assertEquals(1, radio.created - radio.closed.get());
                assertEquals(1, manager.getDiscoveredTunerRegistry().snapshot().size());
            }
            assertEquals(201, radio.created);
            assertEquals(1, manager.reconcileUsbTuners(List.of()));
            assertEquals(radio.created, radio.closed.get());
            assertSame(radio, manager.getDiscoveredTunerRegistry().find(radio.getId()));
            assertEquals(TunerStatus.REMOVED, radio.getTunerStatus());
            assertEquals(0, manager.reconcileUsbTuners(List.of()));
            assertTrue(manager.getAvailableTuners().isEmpty());
        }
    }

    @Test
    void intentionalDisableAndSetupModeSurviveRescan()
    {
        try(Fixture manager = new Fixture())
        {
            Radio disabled = new Radio(1), setup = new Radio(2);
            disabled.setEnabled(false);
            manager.reconcileUsbTuners(List.of(disabled, setup));
            assertTrue(setup.enterSetup());
            setup.setErrorMessage("unplug");
            Radio reattached = new Radio(2);
            reattached.setDeviceAddress(42);
            assertEquals(1, manager.reconcileUsbTuners(List.of(new Radio(1), reattached)));
            assertEquals(0, disabled.created);
            assertFalse(disabled.isEnabled());
            assertEquals(DiscoveredTuner.OperatorState.SETUP, setup.getOperatorState());
            assertFalse(setup.isAvailableForAllocation());
        }
    }

    @Test
    void failedStartupIsDisposedAndCanRecoverOnNextExplicitScan()
    {
        try(Fixture manager = new Fixture())
        {
            Radio radio = new Radio(1);
            radio.failStart = true;
            assertEquals(TunerManager.USB_RESCAN_FAILED, manager.reconcileUsbTuners(List.of(radio)));
            assertEquals(1, radio.closed.get());
            assertEquals(TunerStatus.ERROR, radio.getTunerStatus());
            radio.failStart = false;
            assertEquals(1, manager.reconcileUsbTuners(List.of(new Radio(1))));
            assertTrue(radio.isAvailable());
        }
    }

    @Test
    void incompleteShutdownNeverAllocatesReplacementOrForgetsOldOwner()
    {
        try(Fixture manager = new Fixture())
        {
            Radio radio = new Radio(1);
            manager.reconcileUsbTuners(List.of(radio));
            radio.stopComplete = false;
            radio.setErrorMessage("unplug");
            for(int retry = 0; retry < 100; retry++)
            {
                assertEquals(TunerManager.USB_RESCAN_FAILED, manager.reconcileUsbTuners(List.of(new Radio(1))));
                assertEquals(TunerManager.USB_RESCAN_FAILED, manager.reconcileUsbTuners(List.of()));
                assertSame(radio, manager.getDiscoveredTunerRegistry().find(radio.getId()));
                assertEquals(1, radio.created);
            }
        }
    }

    @Test
    void newAttachmentAtSamePortStopsOldRuntimeBeforeOpeningReplacement()
    {
        try(Fixture manager = new Fixture())
        {
            Radio old = new Radio(1), replacement = new Radio(1);
            old.setDeviceAddress(10);
            replacement.setDeviceAddress(11);
            manager.reconcileUsbTuners(List.of(old));
            old.beforeStart = () -> assertEquals(old.created, old.closed.get());
            assertEquals(1, manager.reconcileUsbTuners(List.of(replacement)));
            assertEquals(2, old.created);
            assertSame(old, manager.getDiscoveredTunerRegistry().find(replacement.getId()));
        }
    }

    @Test
    void contendedRetirementDoesNotWaitOrRemoveTheCurrentOwner() throws Exception
    {
        try(Fixture manager = new Fixture(); var worker = Executors.newSingleThreadExecutor())
        {
            Radio radio = new Radio(1);
            manager.reconcileUsbTuners(List.of(radio));
            CountDownLatch held = new CountDownLatch(1), release = new CountDownLatch(1);
            var reservation = worker.submit(() -> {
                assertTrue(radio.tryAcquireForAllocation());
                held.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch(InterruptedException e) { throw new AssertionError(e); }
                finally { radio.releaseAfterAllocation(); }
            });
            try
            {
                assertTrue(held.await(1, TimeUnit.SECONDS));
                assertEquals(TunerManager.USB_RESCAN_FAILED, manager.reconcileUsbTuners(List.of()));
                assertEquals(0, radio.closed.get());
            }
            finally { release.countDown(); }
            reservation.get(1, TimeUnit.SECONDS);
            assertEquals(1, manager.reconcileUsbTuners(List.of()));
        }
    }

    @Test
    void managedFailureCallbackDoesNotWaitForLifecycleLockAndWorkerOwnsCleanup() throws Exception
    {
        try(Fixture manager = new Fixture(); var holder = Executors.newSingleThreadExecutor();
            var callbacks = Executors.newSingleThreadExecutor())
        {
            Radio radio = new Radio(1);
            radio.enableManagedRecovery();
            manager.reconcileUsbTuners(List.of(radio));
            ITunerErrorListener retired = radio.runtimeListener;
            Tuner runtime = radio.getTuner();
            CountDownLatch held = new CountDownLatch(1), release = new CountDownLatch(1);
            var reservation = holder.submit(() -> {
                assertTrue(radio.tryAcquireForAllocation());
                held.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch(InterruptedException e) { throw new AssertionError(e); }
                finally { radio.releaseAfterAllocation(); }
            });
            try
            {
                assertTrue(held.await(1, TimeUnit.SECONDS));
                callbacks.submit(() -> retired.setErrorMessage("USB failure")).get(1, TimeUnit.SECONDS);
                assertFalse(radio.isAvailable(), "Pending failure immediately closes allocation admission");
                assertSame(runtime, radio.getTuner());
                assertEquals(0, radio.closed.get(), "The reporting callback must never stop hardware");
                assertEquals(TunerStatus.ENABLED, radio.getTunerStatus());
            }
            finally { release.countDown(); }
            reservation.get(1, TimeUnit.SECONDS);

            manager.processUsbRuntimeFailures();
            assertEquals(TunerStatus.ERROR, radio.getTunerStatus());
            assertFalse(radio.hasTuner());
            assertEquals(1, radio.closed.get());
            assertEquals(1, manager.reconcileUsbTuners(List.of(new Radio(1))));
            Tuner replacement = radio.getTuner();
            assertTrue(radio.isAvailable());

            callbacks.submit(() -> {
                retired.setErrorMessage("late failure");
                retired.tunerRemoved();
            }).get(1, TimeUnit.SECONDS);
            manager.processUsbRuntimeFailures();
            assertSame(replacement, radio.getTuner());
            assertTrue(radio.isAvailable());
            assertEquals(1, radio.closed.get());
        }
    }

    private static final class Fixture extends TunerManager implements AutoCloseable
    {
        Fixture() { super(null); }
        @Override boolean startAndConfigureTuner(DiscoveredTuner radio)
        {
            radio.configureOnStart(tuner -> new AirspyTunerConfiguration(radio.getId()));
            radio.startConfigured();
            getDiscoveredTunerRegistry().add(radio);
            return true;
        }
        @Override public void close() { stop(); }
    }

    private static final class Radio extends DiscoveredUSBTuner
    {
        int created;
        ITunerErrorListener runtimeListener;
        final AtomicInteger closed = new AtomicInteger();
        boolean failStart, stopComplete = true;
        Runnable beforeStart = () -> {};
        Radio(int port) { super(TunerClass.AIRSPY, 1, Integer.toString(port)); }
        @Override boolean isUsbStopComplete() { return stopComplete; }
        @Override protected Tuner createTuner()
        {
            beforeStart.run();
            created++;
            TestTunerController controller = new TestTunerController()
            {
                @Override public void start() throws SourceException
                {
                    if(failStart) throw new SourceException("test startup failure");
                }
                @Override public void stop() { closed.incrementAndGet(); }
                @Override public void apply(TunerConfiguration config) throws SourceException
                { setFrequency(855_000_000); }
            };
            runtimeListener = createRuntimeListener();
            return new Tuner(controller, runtimeListener)
            {
                @Override public int getMaximumUSBBitsPerSecond() { return 0; }
                @Override public String getUniqueID() { return "test"; }
                @Override public TunerClass getTunerClass() { return TunerClass.AIRSPY; }
                @Override public String getPreferredName() { return "Test radio"; }
                @Override public double getSampleSize() { return 16; }
            };
        }
    }
}
