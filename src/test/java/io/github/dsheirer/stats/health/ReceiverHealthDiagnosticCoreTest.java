/* Copyright (C) 2026 Dennis Sheirer */
package io.github.dsheirer.stats.health;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dsheirer.dsp.filter.channelizer.PolyphaseChannelManager;
import io.github.dsheirer.source.tuner.usb.USBTunerController;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ReceiverHealthDiagnosticCoreTest
{
    @Test
    void firstIntervalAndGcDeltaAreUnknownThenAccountForFixedDelayAndCollectionTime()
    {
        ReceiverHealthDiagnosticCore core = new ReceiverHealthDiagnosticCore();
        var first = core.begin(7, 100, 1_000, nanos(1_000));
        first.host(33, 1_000, 2_000, 50, 4, 12);
        Map<String,Object> firstSnapshot = first.finish(nanos(1_020));
        assertEquals(false, firstSnapshot.get("sample_interval_available"));
        assertEquals(false, map(firstSnapshot.get("host")).get("gc_delta_available"));
        var next = core.begin(7, 100, 2_100, nanos(2_100));
        next.host(34, 1_100, 2_000, 55, 6, 19);
        Map<String,Object> snapshot = next.finish(nanos(2_125));
        assertEquals(true, snapshot.get("sample_interval_available"));
        assertEquals(1_100L, snapshot.get("sample_interval_ms"));
        assertEquals(80L, snapshot.get("sample_lateness_ms"));
        assertEquals(25L, snapshot.get("sample_duration_ms"));
        assertEquals(2L, map(snapshot.get("host")).get("gc_collection_count"));
        assertEquals(7L, map(snapshot.get("host")).get("gc_collection_time_ms"));
        assertFalse(map(snapshot.get("host")).containsKey("pause_ms"));
        var resumed = core.begin(8, 100, 3_000, nanos(3_000));
        resumed.host(35, 1_100, 2_000, 55, 6, 19);
        Map<String,Object> resumedSnapshot = resumed.finish(nanos(3_000));
        assertEquals(false, resumedSnapshot.get("sample_interval_available"));
        assertEquals(false, map(resumedSnapshot.get("host")).get("gc_delta_available"));
        assertEquals(8L, resumedSnapshot.get("capture_generation"));
    }

    @Test
    void unavailableReadingsAreExplicitAndDoNotSeedAFutureDelta()
    {
        ReceiverHealthDiagnosticCore core = new ReceiverHealthDiagnosticCore();
        var first = core.begin(1, 1, 1, 1);
        first.host(-1, 0, 1, 0, -1, -1);
        first.web(Map.of(), Map.of(), Map.of());
        Map<String,Object> snapshot = first.finish(2);
        assertEquals(false, map(snapshot.get("host")).get("cpu_available"));
        assertEquals(false, map(snapshot.get("host")).get("gc_available"));
        assertEquals(false, map(snapshot.get("web")).get("live_available"));
        assertEquals(false, map(snapshot.get("web")).get("audio_available"));
        assertEquals(false, map(snapshot.get("web")).get("diagnostics_available"));
        var next = core.begin(1, 1, 2, 3);
        next.host(0, 0, 1, 0, 20, 30);
        assertEquals(false, map(next.finish(4).get("host")).get("gc_delta_available"));
        core.disabled();
        var reenabled = core.begin(1, 1, 3, 5);
        assertEquals(false, reenabled.finish(6).get("sample_interval_available"));
    }

    @Test
    void deviceIdentityIsStableButStreamRebindAndCaptureResetCounterDeltas() throws Exception
    {
        ReceiverHealthDiagnosticCore core = new ReceiverHealthDiagnosticCore();
        Object controller = new Object();
        Object manager = new Object();
        Map<String,Object> first = deviceSample(core, 1, controller, manager, 1, 10, 3);
        Map<String,Object> second = deviceSample(core, 1, controller, manager, 1, 15, 5);
        assertEquals(first.get("id"), second.get("id"));
        assertEquals(first.get("epoch"), second.get("epoch"));
        assertEquals(true, map(second.get("usb")).get("delta_available"));
        assertEquals(5L, map(second.get("usb")).get("new_completed"));
        assertEquals(2L, map(second.get("native")).get("new_dropped_buffers"));
        Map<String,Object> stream = deviceSample(core, 1, controller, manager, 2, 20, 7);
        assertEquals(false, map(stream.get("usb")).get("delta_available"));
        assertEquals(false, map(stream.get("native")).get("delta_available"));
        assertEquals((long)second.get("epoch") + 1, stream.get("epoch"));
        Map<String,Object> rebound = deviceSample(core, 1, controller, new Object(), 2, 25, 9);
        assertEquals(false, map(rebound.get("native")).get("delta_available"));
        assertEquals(first.get("id"), rebound.get("id"));
        Map<String,Object> reenabled = deviceSample(core, 2, controller, manager, 2, 30, 11);
        assertEquals(false, map(reenabled.get("usb")).get("delta_available"));
        assertThrows(UnsupportedOperationException.class, () -> map(second.get("usb")).put("new_completed", 99));
        assertEquals(5L, map(second.get("usb")).get("new_completed"));
    }

    @Test
    void boundedCoreAlwaysIncludesHostDespiteHugeNamesAndManyChannelsAndFitsARecord() throws Exception
    {
        ReceiverHealthDiagnosticCore core = new ReceiverHealthDiagnosticCore();
        var sample = core.begin(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, 1);
        sample.tunersObserved(100);
        sample.host(Double.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Double.MAX_VALUE,
            Long.MAX_VALUE, Long.MAX_VALUE);
        sample.web(Map.of("active_clients", Long.MAX_VALUE), Map.of("active_feeds", Long.MAX_VALUE),
            Map.of("channel_sessions", Long.MAX_VALUE));
        List<Map<String,Object>> alerts = new ArrayList<>();
        String hugeName = "private name ".repeat(10_000);
        for(int index = 0; index < 100; index++)
        {
            var tuner = sample.tuner("sensitive-id-" + index, hugeName + index, null, null, true, true,
                Integer.MAX_VALUE);
            if(tuner != null)
            {
                tuner.usb(usb(Map.of()), Long.MAX_VALUE, true, Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE);
                tuner.nativeQueue(nativeQueue(Long.MAX_VALUE));
                tuner.pipeline(new PolyphaseChannelManager.PipelineStatus(Integer.MAX_VALUE, Integer.MAX_VALUE,
                    Integer.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, List.of()), Long.MAX_VALUE, Long.MAX_VALUE,
                    Long.MAX_VALUE, Long.MAX_VALUE);
            }
        }
        for(int index = 0; index < 256; index++) alerts.add(Map.of("occurrence_id", Long.MAX_VALUE,
            "scope", hugeName + index % ReceiverHealthDiagnosticCore.MAXIMUM_TUNERS));
        sample.activeAlerts(alerts);
        Map<String,Object> snapshot = sample.finish(1);
        assertEquals(ReceiverHealthDiagnosticCore.MAXIMUM_TUNERS, rows(snapshot.get("tuners")).size());
        assertEquals(100 - ReceiverHealthDiagnosticCore.MAXIMUM_TUNERS, snapshot.get("tuners_omitted"));
        assertEquals(Long.MAX_VALUE, map(snapshot.get("host")).get("heap_used_bytes"));
        byte[] serialized = new ObjectMapper().writeValueAsBytes(snapshot);
        assertTrue(serialized.length < 48 * 1024, "Leave 16 KiB for bounded alert metadata; core bytes=" + serialized.length);
        String text = new String(serialized, java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(text.contains("private name"));
        assertFalse(text.contains("sensitive-id"));
    }

    @Test
    void linksAlertOccurrencesToTunerNumbersAndMarksAmbiguousNames()
    {
        ReceiverHealthDiagnosticCore core = new ReceiverHealthDiagnosticCore();
        var sample = core.begin(1, 0, 0, 0);
        sample.tunersObserved(3);
        sample.tuner("serial one", "Receiver", null, null, true, false, 1);
        sample.tuner("serial two", "Receiver", null, null, true, false, 1);
        sample.tuner("serial three", "Receiver Two", null, null, true, false, 1);
        sample.activeAlerts(List.of(Map.of("occurrence_id", 2L, "scope", "Receiver · 100.0 MHz"),
            Map.of("occurrence_id", 3L, "scope", "Receiver Two · 101.0 MHz"),
            Map.of("occurrence_id", 4L, "scope", "Computer")));
        Map<String,Object> snapshot = sample.finish(1);
        assertEquals(1, snapshot.get("alert_tuners_ambiguous"));
        assertEquals(List.of(Map.of("occurrence_id", 3L, "tuner_id", 3L)), snapshot.get("alert_tuners"));
    }

    private static Map<String,Object> deviceSample(ReceiverHealthDiagnosticCore core, long generation,
        Object controller, Object manager, long stream, long completed, long dropped) throws Exception
    {
        var sample = core.begin(generation, 0, 0, 0);
        sample.tunersObserved(1);
        var tuner = sample.tuner("sensitive serial", "Receiver", controller, manager, true, false, 2);
        tuner.usb(usb(Map.of("streamSequence", stream, "completedTransferCount", completed)), 100, true, 100, 10, 10);
        tuner.nativeQueue(nativeQueue(dropped));
        return rows(sample.finish(1).get("tuners")).getFirst();
    }

    private static PolyphaseChannelManager.NativeBufferQueueStatus nativeQueue(long dropped)
    {
        return new PolyphaseChannelManager.NativeBufferQueueStatus(Long.MAX_VALUE, Long.MAX_VALUE,
            Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, dropped, Long.MAX_VALUE, Long.MAX_VALUE);
    }

    private static USBTunerController.UsbTransferHealthSnapshot usb(Map<String,Object> overrides) throws Exception
    {
        RecordComponent[] components = USBTunerController.UsbTransferHealthSnapshot.class.getRecordComponents();
        Object[] arguments = new Object[components.length];
        Class<?>[] types = new Class<?>[components.length];
        for(int index = 0; index < components.length; index++)
        {
            var component = components[index];
            types[index] = component.getType();
            Object defaultValue;
            if(component.getType() == long.class) defaultValue = Long.MAX_VALUE;
            else if(component.getType() == int.class) defaultValue = Integer.MAX_VALUE;
            else if(component.getType() == boolean.class) defaultValue = true;
            else defaultValue = "sensitive device description";
            arguments[index] = overrides.getOrDefault(component.getName(), defaultValue);
        }
        return USBTunerController.UsbTransferHealthSnapshot.class.getConstructor(types).newInstance(arguments);
    }

    private static long nanos(long milliseconds) { return TimeUnit.MILLISECONDS.toNanos(milliseconds); }
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object value) { return (Map<String,Object>)value; }
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> rows(Object value)
    { return (List<Map<String,Object>>)value; }
}
