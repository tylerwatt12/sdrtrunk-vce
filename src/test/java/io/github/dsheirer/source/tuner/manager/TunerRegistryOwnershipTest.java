package io.github.dsheirer.source.tuner.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.github.dsheirer.source.tuner.TunerClass;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import javax.swing.SwingUtilities;
import javax.swing.event.TableModelEvent;
import org.junit.jupiter.api.Test;

class TunerRegistryOwnershipTest
{
    @Test
    void receiverInventoryWorksBeforeTheDesktopModelIsConstructed() throws Exception
    {
        TunerManager manager = new TunerManager(null);
        Field desktopModel = TunerManager.class.getDeclaredField("mDiscoveredTunerModel");
        desktopModel.setAccessible(true);
        assertNull(desktopModel.get(manager));

        FakeDiscoveredTuner disabled = new FakeDiscoveredTuner("disabled");
        disabled.setEnabled(false);
        FakeDiscoveredTuner error = new FakeDiscoveredTuner("error");
        error.setErrorMessage("Unavailable");
        manager.getDiscoveredTunerRegistry().add(disabled);
        manager.getDiscoveredTunerRegistry().add(error);

        assertEquals(2, manager.getDiscoveredTunerRegistry().snapshot().size());
        assertFalse(manager.getDiscoveredTunerRegistry().availableTuners().contains(disabled));
        assertSame(error, manager.getDiscoveredTunerRegistry().find("error"));
        assertNull(desktopModel.get(manager));
        assertEquals(2, manager.getDiscoveredTunerModel().getTunersSnapshot().size());

        manager.getDiscoveredTunerRegistry().release();
    }

    @Test
    void rapidHotplugUsesOneCurrentSnapshotRatherThanStaleRowIndexes() throws Exception
    {
        TunerManager manager = new TunerManager(null);
        var model = manager.getDiscoveredTunerModel();
        List<Integer> eventTypes = new ArrayList<>();
        model.addTableModelListener(event -> eventTypes.add(event.getType()));
        List<FakeDiscoveredTuner> tuners = new ArrayList<>();

        for(int index = 0; index < 20; index++)
        {
            FakeDiscoveredTuner tuner = new FakeDiscoveredTuner("hotplug-" + index);
            tuners.add(tuner);
            manager.getDiscoveredTunerRegistry().add(tuner);
        }
        for(FakeDiscoveredTuner tuner: tuners)
        {
            manager.getDiscoveredTunerRegistry().remove(tuner);
        }

        SwingUtilities.invokeAndWait(() -> { });
        assertEquals(0, model.getRowCount());
        assertFalse(eventTypes.isEmpty());
        assertEquals(1, eventTypes.stream().distinct().count());
        assertEquals(TableModelEvent.UPDATE, eventTypes.getFirst());
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
