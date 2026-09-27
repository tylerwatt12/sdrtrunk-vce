package io.github.dsheirer.web.tuner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.source.tuner.TunerClass;
import io.github.dsheirer.source.tuner.Tuner;
import io.github.dsheirer.source.tuner.TunerType;
import io.github.dsheirer.source.tuner.manager.DiscoveredRecordingTuner;
import io.github.dsheirer.source.tuner.manager.DiscoveredTuner;
import io.github.dsheirer.source.tuner.airspy.AirspyTunerConfiguration;
import io.github.dsheirer.source.tuner.recording.RecordingTunerConfiguration;
import io.github.dsheirer.source.tuner.rtl.r8x.r820t.R820TTunerConfiguration;
import io.github.dsheirer.source.tuner.sdrplay.api.device.DeviceInfo;
import io.github.dsheirer.source.tuner.sdrplay.api.device.DeviceType;
import io.github.dsheirer.source.tuner.sdrplay.rspDuo.DiscoveredRspDuoTuner1;
import io.github.dsheirer.source.tuner.sdrplay.rspDuo.DiscoveredRspDuoTuner2;
import io.github.dsheirer.source.tuner.test.TestTuner;
import io.github.dsheirer.web.http.ApiHttpResponse;
import java.util.List;
import org.junit.jupiter.api.Test;

class TunerAdministrationServiceTest
{
    @Test
    void includesDisabledAndErrorTunersWithoutLeakingRecordingPaths()
    {
        RecordingTunerConfiguration configuration = new RecordingTunerConfiguration("recording-test");
        configuration.setPath("/private/debug/large-baseband.wav");
        configuration.setFrequency(851_012_500L);
        DiscoveredRecordingTuner recording = new DiscoveredRecordingTuner(configuration);
        FakeDiscoveredTuner error = new FakeDiscoveredTuner("hardware-serial");
        error.setErrorMessage("Sensitive /private/device/path");
        TunerAdministrationService service = new TunerAdministrationService(() -> List.of(recording, error),
            tuner -> "should-not-be-looked-up");

        TunerAdministrationService.Snapshot snapshot = service.snapshot();
        assertEquals(2, snapshot.tuners().size());
        TunerAdministrationService.Item recordingItem = snapshot.tuners().getFirst();
        assertEquals("large-baseband.wav", recordingItem.name());
        assertEquals("disabled", recordingItem.status());
        assertEquals(851_012_500L, recordingItem.configuredFrequencyHz());
        assertNull(recordingItem.spectrumTargetId());
        assertFalse(recordingItem.spectrumAvailable());
        assertNull(recordingItem.planner());
        assertEquals(List.of(), recordingItem.settings());
        assertEquals("error", snapshot.tuners().get(1).status());
        assertTrue(recordingItem.id().startsWith("tuner-"));
        assertFalse(recordingItem.id().contains("private"));
        String json = ApiHttpResponse.normalizePayload(snapshot).toString();
        assertFalse(json.contains("/private/"));
        assertFalse(json.contains("Sensitive"));
    }

    @Test
    void groupsRspDuoMembersWithoutExposingSerialsInIdentifiers()
    {
        DeviceInfo firstInfo = new DeviceInfo(DeviceType.RSPduo, "SERIAL-123");
        DeviceInfo secondInfo = firstInfo.copy();
        DiscoveredRspDuoTuner1 first = new DiscoveredRspDuoTuner1(firstInfo);
        DiscoveredRspDuoTuner2 second = new DiscoveredRspDuoTuner2(secondInfo);
        TunerAdministrationService service = new TunerAdministrationService(() -> List.of(first, second),
            tuner -> null);

        List<TunerAdministrationService.Item> items = service.snapshot().tuners();
        assertEquals(2, items.size());
        assertEquals(items.get(0).deviceGroup().id(), items.get(1).deviceGroup().id());
        assertEquals("rsp_duo", items.get(0).deviceGroup().kind());
        assertEquals("tuner_1", items.get(0).deviceGroup().role());
        assertEquals("tuner_2", items.get(1).deviceGroup().role());
        assertNotEquals(items.get(0).id(), items.get(1).id());
        assertFalse(items.get(0).deviceGroup().id().contains("SERIAL-123"));
    }

    @Test
    void settingsProviderFailureKeepsTunerVisibleWithoutLeakingExceptionDetails()
    {
        FakeDiscoveredTuner tuner = new FakeDiscoveredTuner("hardware-serial");
        TunerAdministrationService service = new TunerAdministrationService(() -> List.of(tuner),
            target -> null, discovered -> { throw new IllegalStateException("secret device path"); });

        List<TunerAdministrationService.Item> items = service.snapshot().tuners();
        assertEquals(1, items.size());
        assertEquals(List.of(), items.getFirst().settings());
        assertEquals("Tuner settings are temporarily unavailable", items.getFirst().maintenanceError());
        assertFalse(ApiHttpResponse.normalizePayload(service.snapshot()).toString().contains("secret device path"));
    }

    @Test
    void exposesMeasuredDecoderFrequencyErrorOnlyForAnAvailableTuner()
    {
        FakeDiscoveredTuner tuner = new FakeDiscoveredTuner("measured-error");
        TestTuner live = new TestTuner(tuner);
        live.getTunerController().setMeasuredFrequencyError(100);
        tuner.install(live);
        TunerAdministrationService service = new TunerAdministrationService(() -> List.of(tuner), target -> null);

        TunerAdministrationService.MeasuredError error = service.snapshot().tuners().getFirst().measuredError();
        assertEquals(100, error.hertz());
        assertEquals(100 / (live.getTunerController().getFrequency() / 1_000_000.0), error.ppm());
    }

    @Test
    void setupExposesSpectrumAtZeroChannelsButIdleLiveDoesNot()
    {
        FakeDiscoveredTuner tuner = new FakeDiscoveredTuner("setup-spectrum");
        tuner.install(new TestTuner(tuner));
        TunerAdministrationService service = new TunerAdministrationService(() -> List.of(tuner),
            target -> "setup-target");

        assertFalse(service.snapshot().tuners().getFirst().spectrumAvailable());
        assertTrue(tuner.holdForSetup());
        TunerAdministrationService.Item setup = service.snapshot().tuners().getFirst();
        assertTrue(setup.spectrumAvailable());
        assertEquals("setup-target", setup.spectrumTargetId());
    }

    @Test
    void rfAnalysisIncludesDisabledRtlAndEveryEnabledAirspyButNotRemovedOrRecordingTuners()
    {
        FakeDiscoveredTuner first = new FakeDiscoveredTuner("first-airspy");
        FakeDiscoveredTuner second = new FakeDiscoveredTuner("second-airspy");
        FakeDiscoveredTuner disabled = new FakeDiscoveredTuner("disabled-rtl");
        FakeDiscoveredTuner removed = new FakeDiscoveredTuner("removed-rtl");
        FakeDiscoveredTuner unsupported = new FakeDiscoveredTuner("test-generator");
        first.setTunerConfiguration(new AirspyTunerConfiguration(first.getId()));
        second.setTunerConfiguration(new AirspyTunerConfiguration(second.getId()));
        first.install(plannerTuner(first, TunerType.AIRSPY_R820T));
        second.install(plannerTuner(second, TunerType.AIRSPY_R820T));
        disabled.setTunerConfiguration(new R820TTunerConfiguration(disabled.getId()));
        disabled.setEnabled(false);
        removed.setTunerConfiguration(new R820TTunerConfiguration(removed.getId()));
        removed.tunerRemoved();
        unsupported.install(new TestTuner(unsupported));
        RecordingTunerConfiguration recordingConfiguration = new RecordingTunerConfiguration("recording-test");
        DiscoveredRecordingTuner recording = new DiscoveredRecordingTuner(recordingConfiguration);
        TunerAdministrationService service = new TunerAdministrationService(
            () -> List.of(first, second, disabled, removed, unsupported, recording), tuner -> null);

        List<TunerAdministrationService.PlannerTarget> targets = service.plannerTargets();
        assertEquals(3, targets.size(), "Distinct physical devices must not be collapsed by model or rate");
        assertEquals("airspy", targets.get(0).model());
        assertEquals("airspy", targets.get(1).model());
        assertEquals(2_400_000L, targets.get(0).rateHz());
        assertNotEquals(targets.get(0).id(), targets.get(1).id());
        assertEquals("rtl-r8x", targets.get(2).model());
        assertEquals(2_400_000L, targets.get(2).rateHz(), "Disabled RTL must use saved sample rate");
    }

    private static Tuner plannerTuner(FakeDiscoveredTuner discovered, TunerType type)
    {
        return new TestTuner(discovered)
        {
            @Override public TunerType getTunerType() { return type; }
        };
    }

    private static final class FakeDiscoveredTuner extends DiscoveredTuner
    {
        private final String mId;

        private FakeDiscoveredTuner(String id)
        {
            mId = id;
        }

        private void install(Tuner tuner)
        {
            mTuner = tuner;
        }

        @Override
        public TunerClass getTunerClass()
        {
            return TunerClass.UNKNOWN;
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
