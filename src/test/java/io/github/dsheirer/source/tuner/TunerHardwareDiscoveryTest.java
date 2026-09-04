package io.github.dsheirer.source.tuner;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TunerHardwareDiscoveryTest
{
    @Test void cancelledInventoryNeverLoadsNativeLibrariesOrInventsImportedDevices()
    {
        var inventory=new TunerHardwareDiscovery().scan(()->true);
        assertTrue(inventory.devices().isEmpty()); assertTrue(inventory.errors().isEmpty());
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
}
