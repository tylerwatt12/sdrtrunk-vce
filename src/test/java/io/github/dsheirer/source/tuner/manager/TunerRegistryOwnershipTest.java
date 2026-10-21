package io.github.dsheirer.source.tuner.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.github.dsheirer.source.tuner.TunerClass;
import io.github.dsheirer.source.tuner.sdrplay.api.DeviceSelectionMode;
import io.github.dsheirer.source.tuner.sdrplay.api.device.DeviceInfo;
import io.github.dsheirer.source.tuner.sdrplay.api.device.DeviceType;
import io.github.dsheirer.source.tuner.sdrplay.rspDuo.DiscoveredRspDuoTuner1;
import io.github.dsheirer.source.tuner.sdrplay.rspDuo.DiscoveredRspDuoTuner2;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class TunerRegistryOwnershipTest
{
    @Test
    void removalOfEitherRspDuoHalfRetiresBothWithoutAStartupCommand() throws Exception
    {
        for(boolean removeMaster: new boolean[]{true,false})
        {
            CountDownLatch stopped = new CountDownLatch(2);
            DeviceInfo masterInfo = new DeviceInfo(DeviceType.RSPduo, "paired-removal-test");
            masterInfo.setDeviceSelectionMode(DeviceSelectionMode.MASTER_TUNER_1);
            DeviceInfo slaveInfo = masterInfo.copy();
            slaveInfo.setDeviceSelectionMode(DeviceSelectionMode.SLAVE_TUNER_2);
            var master = new DiscoveredRspDuoTuner1(masterInfo)
            {
                @Override public void start() { throw new AssertionError("removal cannot start hardware"); }
                @Override public void stop() { stopped.countDown(); }
            };
            var slave = new DiscoveredRspDuoTuner2(slaveInfo)
            {
                @Override public void start() { throw new AssertionError("removal cannot start hardware"); }
                @Override public void stop() { stopped.countDown(); }
            };
            var registry = new TunerManager(null).getDiscoveredTunerRegistry();
            registry.add(master);
            registry.add(slave);
            if(removeMaster) master.tunerRemoved();
            else slave.tunerRemoved();
            assertTrue(registry.snapshot().isEmpty());
            assertTrue(stopped.await(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void receiverInventoryNeedsNoDesktopModel()
    {
        TunerManager manager = new TunerManager(null);
        FakeDiscoveredTuner disabled = new FakeDiscoveredTuner("disabled");
        disabled.setEnabled(false);
        FakeDiscoveredTuner error = new FakeDiscoveredTuner("error");
        error.setErrorMessage("Unavailable");
        manager.getDiscoveredTunerRegistry().add(disabled);
        manager.getDiscoveredTunerRegistry().add(error);

        assertEquals(2, manager.getDiscoveredTunerRegistry().snapshot().size());
        assertFalse(manager.getDiscoveredTunerRegistry().availableTuners().contains(disabled));
        assertSame(error, manager.getDiscoveredTunerRegistry().find("error"));
        manager.getDiscoveredTunerRegistry().release();
    }

    @Test
    void rapidHotplugLeavesOneCurrentReceiverSnapshot()
    {
        TunerManager manager = new TunerManager(null);
        var registry = manager.getDiscoveredTunerRegistry();
        List<FakeDiscoveredTuner> tuners = new ArrayList<>();

        for(int index = 0; index < 20; index++)
        {
            FakeDiscoveredTuner tuner = new FakeDiscoveredTuner("hotplug-" + index);
            tuners.add(tuner);
            registry.add(tuner);
            assertSame(tuner, registry.find(tuner.getId()));
        }
        for(FakeDiscoveredTuner tuner: tuners)
        {
            registry.remove(tuner);
            assertFalse(registry.contains(tuner));
        }

        assertEquals(0, registry.snapshot().size());
    }

    private static final class FakeDiscoveredTuner extends DiscoveredTuner
    {
        private final String mId;

        private FakeDiscoveredTuner(String id)
        {
            mId = id;
        }

        @Override
        public TunerClass getTunerClass()
        {
            return TunerClass.TEST_TUNER;
        }

        @Override
        public String getId()
        {
            return mId;
        }

        @Override
        public void start()
        {
        }
    }
}
