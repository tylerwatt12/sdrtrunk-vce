package io.github.dsheirer.source.tuner.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.github.dsheirer.source.tuner.TunerClass;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TunerRegistryOwnershipTest
{
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
        List<DiscoveredTunerRegistry.Change> changes = new ArrayList<>();
        registry.addChangeListener((change, tuner, index) -> changes.add(change));
        List<FakeDiscoveredTuner> tuners = new ArrayList<>();

        for(int index = 0; index < 20; index++)
        {
            FakeDiscoveredTuner tuner = new FakeDiscoveredTuner("hotplug-" + index);
            tuners.add(tuner);
            registry.add(tuner);
        }
        for(FakeDiscoveredTuner tuner: tuners)
        {
            registry.remove(tuner);
        }

        assertEquals(0, registry.snapshot().size());
        assertEquals(20, changes.stream().filter(change -> change == DiscoveredTunerRegistry.Change.ADDED).count());
        assertEquals(20, changes.stream().filter(change -> change == DiscoveredTunerRegistry.Change.REMOVED).count());
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
