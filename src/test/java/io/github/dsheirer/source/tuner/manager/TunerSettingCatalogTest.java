package io.github.dsheirer.source.tuner.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.source.tuner.TunerType;
import io.github.dsheirer.source.tuner.TunerFactory;
import io.github.dsheirer.source.tuner.TunerClass;
import io.github.dsheirer.source.tuner.airspy.AirspyTunerConfiguration;
import io.github.dsheirer.source.tuner.airspy.AirspyTunerController;
import io.github.dsheirer.source.tuner.airspy.AirspyTunerController.Gain;
import io.github.dsheirer.source.tuner.hackrf.HackRFTunerConfiguration;
import io.github.dsheirer.source.tuner.hackrf.HackRFTunerController.HackRFSampleRate;
import io.github.dsheirer.source.tuner.recording.RecordingTunerConfiguration;
import io.github.dsheirer.source.tuner.sdrplay.RspSampleRate;
import io.github.dsheirer.source.tuner.sdrplay.api.DeviceSelectionMode;
import io.github.dsheirer.source.tuner.sdrplay.api.device.DeviceInfo;
import io.github.dsheirer.source.tuner.sdrplay.api.device.DeviceType;
import io.github.dsheirer.source.tuner.sdrplay.rspDuo.DiscoveredRspDuoTuner1;
import io.github.dsheirer.source.tuner.sdrplay.rspDuo.DiscoveredRspDuoTuner2;
import io.github.dsheirer.source.tuner.sdrplay.rspDuo.RspDuoTuner1Configuration;
import io.github.dsheirer.source.tuner.sdrplay.rspDuo.RspDuoTuner2Configuration;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TunerSettingCatalogTest
{
    @Test
    void rspDuoRateAndSharedSettingsFollowPhysicalDeviceMode()
    {
        DeviceInfo masterInfo = new DeviceInfo(DeviceType.RSPduo, "test");
        masterInfo.setDeviceSelectionMode(DeviceSelectionMode.MASTER_TUNER_1);
        DiscoveredRspDuoTuner1 master = new DiscoveredRspDuoTuner1(masterInfo);
        master.setTunerConfiguration(new RspDuoTuner1Configuration(master.getId()));
        master.setEnabled(false);

        TunerSettingCatalog.SettingDescriptor masterRate = setting(master, "sample_rate");
        assertEquals("device", masterRate.scope());
        assertTrue(masterRate.editable());
        assertTrue(masterRate.options().stream().allMatch(option ->
            RspSampleRate.valueOf((String)option.value()).isDualTunerSampleRate()));
        assertThrows(IllegalArgumentException.class, () ->
            TunerSettingCatalog.validate(master, "sample_rate", RspSampleRate.RATE_8_000.name()));
        assertEquals(RspSampleRate.DUO_RATE_2_000,
            TunerSettingCatalog.validate(master, "sample_rate", RspSampleRate.DUO_RATE_2_000.name()));

        DeviceInfo slaveInfo = masterInfo.copy();
        slaveInfo.setDeviceSelectionMode(DeviceSelectionMode.SLAVE_TUNER_2);
        DiscoveredRspDuoTuner2 slave = new DiscoveredRspDuoTuner2(slaveInfo);
        slave.setTunerConfiguration(new RspDuoTuner2Configuration(slave.getId()));

        assertFalse(setting(slave, "sample_rate").editable());
        assertFalse(setting(slave, "external_reference").editable());
        assertTrue(setting(slave, "sample_rate").options().isEmpty());
    }

    @Test
    void recordingHasNoPathSettingAndNoPlannerModel()
    {
        RecordingTunerConfiguration configuration = new RecordingTunerConfiguration("recording-test");
        configuration.setPath("/private/large-recording.wav");
        DiscoveredRecordingTuner recording = new DiscoveredRecordingTuner(configuration);

        assertTrue(TunerSettingCatalog.describe(recording).isEmpty());
        assertEquals(null, TunerSettingCatalog.plannerModel(TunerType.RECORDING));
        assertEquals("rtl-r8x", TunerSettingCatalog.plannerModel(TunerType.RAFAELMICRO_R820T));
    }

    @Test
    void everySupportedFamilyHasValidBackendDescriptors()
    {
        for(TunerType type: EnumSet.of(TunerType.AIRSPY_R820T, TunerType.AIRSPY_HF_PLUS,
            TunerType.HYDRASDR_R828D, TunerType.HACKRF_ONE, TunerType.ELONICS_E4000,
            TunerType.FITIPOWER_FC0013, TunerType.RAFAELMICRO_R820T, TunerType.RAFAELMICRO_R828D,
            TunerType.RSP_1, TunerType.RSP_1A, TunerType.RSP_1B, TunerType.RSP_2,
            TunerType.RSP_DUO_1, TunerType.RSP_DUO_2, TunerType.RSP_DX))
        {
            FakeDiscoveredTuner tuner = new FakeDiscoveredTuner(type.name());
            tuner.setTunerConfiguration(TunerFactory.getTunerConfiguration(type, tuner.getId()));
            assertFalse(TunerSettingCatalog.describe(tuner).isEmpty(), type.name());
        }
    }

    @Test
    void hackRfRejectsLegacySampleRatesAndAirspyGainRespectsPresetAndAgc()
    {
        FakeDiscoveredTuner hackrf = new FakeDiscoveredTuner("hackrf");
        hackrf.setTunerConfiguration(new HackRFTunerConfiguration(hackrf.getId()));
        assertTrue(setting(hackrf, "sample_rate").options().stream()
            .allMatch(option -> HackRFSampleRate.valueOf((String)option.value()).isValidSampleRate()));
        assertThrows(IllegalArgumentException.class, () ->
            TunerSettingCatalog.validate(hackrf, "sample_rate", HackRFSampleRate.RATE_2_3.name()));
        HackRFTunerConfiguration hackrfConfiguration = (HackRFTunerConfiguration)hackrf.getTunerConfiguration();
        hackrfConfiguration.setMinimumFrequency(100_000_000L);
        hackrfConfiguration.setMaximumFrequency(102_000_000L);
        assertThrows(IllegalArgumentException.class, () ->
            TunerSettingCatalog.validate(hackrf, "sample_rate", HackRFSampleRate.RATE_5_0.name()));
        hackrfConfiguration.setMaximumFrequency(106_000_000L);
        assertEquals(HackRFSampleRate.RATE_5_0,
            TunerSettingCatalog.validate(hackrf, "sample_rate", HackRFSampleRate.RATE_5_0.name()));

        FakeDiscoveredTuner airspy = new FakeDiscoveredTuner("airspy");
        AirspyTunerConfiguration configuration = new AirspyTunerConfiguration(airspy.getId());
        airspy.setTunerConfiguration(configuration);
        assertFalse(setting(airspy, "if_gain").editable());
        assertThrows(IllegalArgumentException.class, () -> TunerSettingCatalog.validate(airspy, "if_gain", 5));

        configuration.setGain(Gain.CUSTOM);
        assertTrue(setting(airspy, "if_gain").editable());
        assertTrue(setting(airspy, "mixer_gain").editable());
        TunerSettingCatalog.SettingDescriptor frequency = setting(airspy, "frequency_mhz");
        assertFalse(frequency.requiresIdle());
        assertEquals("MHz", frequency.unit());
        assertEquals("decimal", frequency.kind());
        assertEquals("frequency", frequency.group());
        assertEquals("calibration", setting(airspy, "frequency_correction_ppm").group());
        assertEquals("gain", setting(airspy, "if_gain").group());
        assertEquals(851_012_500L, TunerSettingCatalog.validate(airspy, "frequency_mhz", 851.0125));
        assertThrows(IllegalArgumentException.class, () ->
            TunerSettingCatalog.validate(airspy, "frequency_mhz", 7_000.0));
        assertThrows(IllegalArgumentException.class, () ->
            TunerSettingCatalog.validate(airspy, "frequency_mhz", 851.0125001));
        configuration.setMixerAGC(true);
        assertFalse(setting(airspy, "mixer_gain").editable());
        assertThrows(IllegalArgumentException.class, () -> TunerSettingCatalog.validate(airspy, "mixer_gain", 5));
    }

    @Test
    void frequencyLimitsAndCalibrationAreGenericLiveSettingsAndResetIsAnAction()
    {
        FakeDiscoveredTuner tuner = new FakeDiscoveredTuner("airspy-extents");
        AirspyTunerConfiguration configuration = new AirspyTunerConfiguration(tuner.getId());
        tuner.setTunerConfiguration(configuration);

        TunerSettingCatalog.SettingDescriptor minimum = setting(tuner, TunerSettingCatalog.MINIMUM_FREQUENCY);
        TunerSettingCatalog.SettingDescriptor maximum = setting(tuner, TunerSettingCatalog.MAXIMUM_FREQUENCY);
        TunerSettingCatalog.SettingDescriptor reset = setting(tuner, TunerSettingCatalog.RESET_FREQUENCY_EXTENTS);
        assertEquals("decimal", minimum.kind());
        assertEquals("MHz", minimum.unit());
        assertFalse(minimum.requiresIdle());
        assertFalse(maximum.requiresIdle());
        assertFalse(setting(tuner, "frequency_correction_ppm").requiresIdle());
        assertFalse(setting(tuner, "center_frequency_locked").requiresIdle());
        assertTrue(setting(tuner, "sample_rate").requiresIdle());
        assertTrue(minimum.editable());
        assertEquals(AirspyTunerController.MINIMUM_TUNABLE_FREQUENCY_HZ / 1_000_000.0, minimum.value());
        assertEquals(AirspyTunerController.MAXIMUM_TUNABLE_FREQUENCY_HZ / 1_000_000.0, maximum.value());
        assertEquals("action", reset.kind());
        assertEquals("frequency", reset.group());
        assertEquals(null, reset.value());
        assertFalse(reset.requiresIdle());
        assertEquals(195_000_000L, TunerSettingCatalog.validate(tuner,
            TunerSettingCatalog.MINIMUM_FREQUENCY, 195.0));
        assertEquals(true, TunerSettingCatalog.validate(tuner,
            TunerSettingCatalog.RESET_FREQUENCY_EXTENTS, true));
        assertThrows(IllegalArgumentException.class, () -> TunerSettingCatalog.validate(tuner,
            TunerSettingCatalog.RESET_FREQUENCY_EXTENTS, false));
        assertThrows(IllegalArgumentException.class, () -> TunerSettingCatalog.validate(tuner,
            TunerSettingCatalog.MINIMUM_FREQUENCY, 1.0));
        assertThrows(IllegalArgumentException.class, () -> TunerSettingCatalog.validate(tuner,
            TunerSettingCatalog.MAXIMUM_FREQUENCY, 195.0000001));
    }

    @Test
    void frequencyLimitsPreserveSampleRateGapAndRejectAmbiguousPair()
    {
        AirspyTunerConfiguration configuration = new AirspyTunerConfiguration("airspy-extents");
        configuration.setMinimumFrequency(100_000_000L);
        configuration.setMaximumFrequency(200_000_000L);

        assertEquals(new TunerSettingCatalog.FrequencyExtents(195_000_000L, 205_000_000L),
            TunerSettingCatalog.resolveFrequencyExtents(configuration,
                Map.of(TunerSettingCatalog.MINIMUM_FREQUENCY, 195_000_000L), 10_000_000L));
        assertEquals(new TunerSettingCatalog.FrequencyExtents(95_000_000L, 105_000_000L),
            TunerSettingCatalog.resolveFrequencyExtents(configuration,
                Map.of(TunerSettingCatalog.MAXIMUM_FREQUENCY, 105_000_000L), 10_000_000L));
        assertThrows(IllegalArgumentException.class, () -> TunerSettingCatalog.resolveFrequencyExtents(configuration,
            Map.of(TunerSettingCatalog.MINIMUM_FREQUENCY, 195_000_000L,
                TunerSettingCatalog.MAXIMUM_FREQUENCY, 200_000_000L), 10_000_000L));
        assertEquals(new TunerSettingCatalog.FrequencyExtents(AirspyTunerController.MINIMUM_TUNABLE_FREQUENCY_HZ,
            AirspyTunerController.MAXIMUM_TUNABLE_FREQUENCY_HZ),
            TunerSettingCatalog.resolveFrequencyExtents(configuration,
                Map.of(TunerSettingCatalog.RESET_FREQUENCY_EXTENTS, true), 10_000_000L));
    }

    private static TunerSettingCatalog.SettingDescriptor setting(DiscoveredTuner tuner, String id)
    {
        List<TunerSettingCatalog.SettingDescriptor> settings = TunerSettingCatalog.describe(tuner);
        return settings.stream().filter(setting -> setting.id().equals(id)).findFirst().orElseThrow();
    }

    private static final class FakeDiscoveredTuner extends DiscoveredTuner
    {
        private final String mId;

        private FakeDiscoveredTuner(String id)
        {
            mId = id;
            setEnabled(false);
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
