package io.github.dsheirer.source.tuner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import io.github.dsheirer.source.tuner.sdrplay.api.SDRPlayLibraryHelper.LoadState;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TunerHardwareDiscoveryTest
{
    @Test void cancelledInventoryNeverLoadsNativeLibrariesOrInventsImportedDevices()
    {
        var inventory=new TunerHardwareDiscovery().scan(()->true);
        assertTrue(inventory.devices().isEmpty()); assertTrue(inventory.errors().isEmpty());
        assertTrue(inventory.notices().isEmpty());
        assertThrows(UnsupportedOperationException.class,()->inventory.devices().add(new TunerHardwareDiscovery.Detected("test","test")));
    }
    @Test void discoveryBoundaryCannotActivateOrConfigureTuners() throws Exception
    {
        String source=Files.readString(Path.of("src/main/java/io/github/dsheirer/source/tuner/TunerHardwareDiscovery.java"));
        for(String forbidden:new String[]{"LibUsb.open", "TunerFactory", "startAndConfigureTuner", "selectDevice(", "TunerManager", "ChannelProcessingManager", "setFrequency("})
            assertFalse(source.contains(forbidden),forbidden);
        assertTrue(source.contains("LibUsb.freeDeviceList(list, true)"));
        assertTrue(source.contains("LibUsb.exit(context)"));
        assertTrue(source.contains("api.close()"));
    }

    @Test void absentOptionalSdrplaySupportIsANoticeAndDoesNotOpenTheApi()
    {
        FakeSdrplay backend = new FakeSdrplay(LoadState.NOT_INSTALLED);
        var result = discovery(backend).scan(() -> false);
        assertFalse(backend.opened);
        assertTrue(result.errors().isEmpty());
        assertEquals(1, result.notices().size());
        assertTrue(result.notices().getFirst().contains("you don't need this software"));
        assertThrows(UnsupportedOperationException.class, () -> result.notices().clear());
    }

    @Test void installedButBrokenSupportIsActionableAndNotTreatedAsMissing()
    {
        FakeSdrplay backend = new FakeSdrplay(LoadState.LOAD_FAILED);
        var result = discovery(backend).scan(() -> false);
        assertFalse(backend.opened);
        assertTrue(result.notices().isEmpty());
        assertEquals(1, result.errors().size());
        assertTrue(result.errors().getFirst().contains("Repair or reinstall"));
    }

    @Test void successfulEnumerationPreservesIdentitiesAndClosesSession()
    {
        FakeSdrplay backend = new FakeSdrplay(LoadState.AVAILABLE);
        backend.found.add(new TunerHardwareDiscovery.Detected("RSP1B", "example-serial"));
        var result = discovery(backend).scan(() -> false);
        assertTrue(backend.opened);
        assertTrue(backend.closed);
        assertEquals(backend.found, result.devices());
        assertTrue(result.errors().isEmpty());
        backend.found.clear();
        assertEquals(1, result.devices().size());
    }

    @Test void enumerationFailureClosesSessionAndReportsUsefulRecovery()
    {
        FakeSdrplay backend = new FakeSdrplay(LoadState.AVAILABLE);
        backend.enumerationFailure = true;
        var result = discovery(backend).scan(() -> false);
        assertTrue(backend.closed);
        assertTrue(result.devices().isEmpty());
        assertEquals(1, result.errors().size());
        assertTrue(result.errors().getFirst().contains("service is running"));
        assertFalse(result.errors().getFirst().contains("test error"));
    }

    @Test void unsupportedInstalledApiIsClosedAndDistinguishedFromMissingSoftware()
    {
        FakeSdrplay backend = new FakeSdrplay(LoadState.AVAILABLE);
        backend.supported = false;
        var result = discovery(backend).scan(() -> false);
        assertTrue(backend.closed);
        assertFalse(backend.enumerated);
        assertTrue(result.notices().isEmpty());
        assertTrue(result.errors().getFirst().contains("isn't compatible"));
    }

    @Test void cancellationDuringEnumerationDiscardsResultsAndClosesSession()
    {
        AtomicBoolean cancelled = new AtomicBoolean();
        FakeSdrplay backend = new FakeSdrplay(LoadState.AVAILABLE);
        backend.found.add(new TunerHardwareDiscovery.Detected("RSP1B", "example-serial"));
        backend.onEnumerate = () -> cancelled.set(true);
        var result = discovery(backend).scan(cancelled::get);
        assertTrue(backend.closed);
        assertTrue(result.devices().isEmpty());
        assertTrue(result.errors().isEmpty());
    }

    @Test void cancellationAfterUsbDoesNotLoadOrOpenSdrplay()
    {
        AtomicBoolean cancelled = new AtomicBoolean();
        FakeSdrplay backend = new FakeSdrplay(LoadState.AVAILABLE);
        var discovery = new TunerHardwareDiscovery((devices, errors, token) -> cancelled.set(true), backend);
        discovery.scan(cancelled::get);
        assertFalse(backend.availabilityChecked);
        assertFalse(backend.opened);
    }

    @Test void closeFailureDoesNotEraseDevicesAndRemainsNonblocking()
    {
        FakeSdrplay backend = new FakeSdrplay(LoadState.AVAILABLE);
        backend.found.add(new TunerHardwareDiscovery.Detected("RSP1B", "example-serial"));
        backend.closeFailure = true;
        var result = discovery(backend).scan(() -> false);
        assertTrue(backend.closed);
        assertEquals(1, result.devices().size());
        assertEquals(1, result.errors().size());
    }

    private static TunerHardwareDiscovery discovery(FakeSdrplay backend)
    {
        return new TunerHardwareDiscovery((devices, errors, cancelled) -> {}, backend);
    }

    private static class FakeSdrplay implements TunerHardwareDiscovery.SdrplayAccess,
        TunerHardwareDiscovery.SdrplaySession
    {
        final LoadState state;
        final List<TunerHardwareDiscovery.Detected> found = new ArrayList<>();
        boolean availabilityChecked, opened, closed, enumerated, enumerationFailure, closeFailure;
        boolean supported = true;
        Runnable onEnumerate = () -> {};

        FakeSdrplay(LoadState state) { this.state = state; }
        @Override public LoadState availability() { availabilityChecked = true; return state; }
        @Override public TunerHardwareDiscovery.SdrplaySession open() { opened = true; return this; }
        @Override public boolean isAvailable() { return supported; }
        @Override public List<TunerHardwareDiscovery.Detected> devices()
        {
            enumerated = true;
            if(enumerationFailure) throw new IllegalStateException("test error");
            onEnumerate.run();
            return found;
        }
        @Override public void close()
        {
            closed = true;
            if(closeFailure) throw new IllegalStateException("test close error");
        }
    }
}
