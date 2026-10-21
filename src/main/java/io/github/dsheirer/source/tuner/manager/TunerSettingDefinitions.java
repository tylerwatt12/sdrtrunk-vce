/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.source.tuner.manager;

import io.github.dsheirer.source.tuner.TunerController;
import io.github.dsheirer.source.tuner.airspy.AirspyTunerConfiguration;
import io.github.dsheirer.source.tuner.airspy.AirspyTunerController;
import io.github.dsheirer.source.tuner.airspy.hf.AirspyHfTunerConfiguration;
import io.github.dsheirer.source.tuner.airspy.hf.AirspyHfTunerController;
import io.github.dsheirer.source.tuner.configuration.TunerConfiguration;
import io.github.dsheirer.source.tuner.hackrf.HackRFTunerConfiguration;
import io.github.dsheirer.source.tuner.hackrf.HackRFTunerController;
import io.github.dsheirer.source.tuner.hydrasdr.HydraSdrTunerConfiguration;
import io.github.dsheirer.source.tuner.hydrasdr.HydraSdrTunerController;
import io.github.dsheirer.source.tuner.recording.RecordingTunerConfiguration;
import io.github.dsheirer.source.tuner.rtl.RTL2832TunerConfiguration;
import io.github.dsheirer.source.tuner.rtl.RTL2832TunerController;
import io.github.dsheirer.source.tuner.rtl.e4k.E4KTunerConfiguration;
import io.github.dsheirer.source.tuner.rtl.e4k.E4KEmbeddedTuner;
import io.github.dsheirer.source.tuner.rtl.fc0013.FC0013TunerConfiguration;
import io.github.dsheirer.source.tuner.rtl.fc0013.FC0013EmbeddedTuner;
import io.github.dsheirer.source.tuner.rtl.r8x.R8xTunerConfiguration;
import io.github.dsheirer.source.tuner.rtl.r8x.R8xEmbeddedTuner;
import io.github.dsheirer.source.tuner.sdrplay.RspSampleRate;
import io.github.dsheirer.source.tuner.sdrplay.RspTunerConfiguration;
import io.github.dsheirer.source.tuner.sdrplay.RspTunerController;
import io.github.dsheirer.source.tuner.sdrplay.api.parameter.control.AgcMode;
import io.github.dsheirer.source.tuner.sdrplay.api.parameter.tuner.Rsp2AntennaSelection;
import io.github.dsheirer.source.tuner.sdrplay.api.parameter.tuner.RspDuoAmPort;
import io.github.dsheirer.source.tuner.sdrplay.api.parameter.tuner.RspDxAntenna;
import io.github.dsheirer.source.tuner.sdrplay.api.parameter.tuner.HdrModeBandwidth;
import io.github.dsheirer.source.tuner.sdrplay.rsp1a.Rsp1aTunerConfiguration;
import io.github.dsheirer.source.tuner.sdrplay.rsp1a.IControlRsp1a;
import io.github.dsheirer.source.tuner.sdrplay.rsp1b.Rsp1bTunerConfiguration;
import io.github.dsheirer.source.tuner.sdrplay.rsp1b.IControlRsp1b;
import io.github.dsheirer.source.tuner.sdrplay.rsp2.Rsp2TunerConfiguration;
import io.github.dsheirer.source.tuner.sdrplay.rsp2.IControlRsp2;
import io.github.dsheirer.source.tuner.sdrplay.rspDuo.RspDuoTuner1Configuration;
import io.github.dsheirer.source.tuner.sdrplay.rspDuo.RspDuoTuner2Configuration;
import io.github.dsheirer.source.tuner.sdrplay.rspDuo.IControlRspDuo;
import io.github.dsheirer.source.tuner.sdrplay.rspDuo.IControlRspDuoTuner1;
import io.github.dsheirer.source.tuner.sdrplay.rspDuo.IControlRspDuoTuner2;
import io.github.dsheirer.source.tuner.sdrplay.rspDx.RspDxTunerConfiguration;
import io.github.dsheirer.source.tuner.sdrplay.rspDx.IControlRspDx;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;

/** Typed family bindings. Adding a hardware setting requires one definition, not a second application switch. */
final class TunerSettingDefinitions
{
    private TunerSettingDefinitions() {}

    static List<TunerSettingDefinition<?,?>> forConfiguration(TunerConfiguration configuration)
    {
        List<TunerSettingDefinition<?,?>> settings = new ArrayList<>();
        if(configuration == null || configuration instanceof RecordingTunerConfiguration) return settings;
        settings.add(setting(TunerConfiguration.class, Double.class, "frequency_correction_ppm", "Frequency correction",
            TunerConfiguration::getFrequencyCorrection, TunerConfiguration::setFrequencyCorrection,
            (h,c,v) -> h.setFrequencyCorrection(v)).range(-200.0, 200.0, 0.01, "ppm"));
        long minimum = TunerSettingCatalog.hardwareMinimum(configuration);
        long maximum = TunerSettingCatalog.hardwareMaximum(configuration);
        if(minimum > 0 && maximum > minimum)
        {
            settings.add(setting(TunerConfiguration.class, Long.class, TunerSettingCatalog.MINIMUM_FREQUENCY,
                "Minimum frequency", TunerConfiguration::getMinimumFrequency, TunerConfiguration::setMinimumFrequency,
                (h,c,v) -> h.setMinimumFrequency(v)).range(minimum, maximum, 0.000001, "MHz"));
            settings.add(setting(TunerConfiguration.class, Long.class, TunerSettingCatalog.MAXIMUM_FREQUENCY,
                "Maximum frequency", TunerConfiguration::getMaximumFrequency, TunerConfiguration::setMaximumFrequency,
                (h,c,v) -> h.setMaximumFrequency(v)).range(minimum, maximum, 0.000001, "MHz"));
            minimum = Math.max(minimum, configuration.getMinimumFrequency());
            maximum = configuration.getMaximumFrequency() > 0 ? Math.min(maximum, configuration.getMaximumFrequency()) : maximum;
            if(maximum > minimum)
                settings.add(setting(TunerConfiguration.class, Long.class, "frequency_mhz", "Center frequency",
                    TunerConfiguration::getFrequency, TunerConfiguration::setFrequency,
                    (h,c,v) -> h.setFrequency(v)).range(minimum, maximum, 0.000001, "MHz"));
        }
        settings.add(setting(TunerConfiguration.class, Boolean.class, "automatic_ppm", "Automatic PPM correction",
            TunerConfiguration::getAutoPPMCorrectionEnabled, TunerConfiguration::setAutoPPMCorrectionEnabled,
            (h,c,v) -> h.getTunerFrequencyErrorManager().setEnabled(v)));
        settings.add(setting(TunerConfiguration.class, Boolean.class, "center_frequency_locked", "Lock center",
            TunerConfiguration::isCenterFrequencyLocked, TunerConfiguration::setCenterFrequencyLocked,
            (h,c,v) -> h.setCenterFrequencyLocked(v)));

        if(configuration instanceof AirspyTunerConfiguration) airspy(settings);
        else if(configuration instanceof HydraSdrTunerConfiguration) hydra(settings);
        else if(configuration instanceof AirspyHfTunerConfiguration) hf(settings);
        else if(configuration instanceof RTL2832TunerConfiguration) rtl(settings, configuration);
        else if(configuration instanceof HackRFTunerConfiguration) hackrf(settings);
        else if(configuration instanceof RspTunerConfiguration) rsp(settings, configuration);
        return List.copyOf(settings);
    }

    private static void airspy(List<TunerSettingDefinition<?,?>> s)
    {
        Class<AirspyTunerConfiguration> t = AirspyTunerConfiguration.class;
        s.add(setting(t, Integer.class, "sample_rate", "Sample rate", AirspyTunerConfiguration::getSampleRate,
            AirspyTunerConfiguration::setSampleRate, TunerSettingDefinitions::applyConfiguration).dynamicRate());
        s.add(setting(t, AirspyTunerController.Gain.class, "gain", "Gain preset", AirspyTunerConfiguration::getGain,
            AirspyTunerConfiguration::setGain, (h,c,v) -> ((AirspyTunerController)h).setGain(v)));
        s.add(setting(t, Integer.class, "if_gain", "IF gain", AirspyTunerConfiguration::getIFGain, AirspyTunerConfiguration::setIFGain,
            (h,c,v) -> ((AirspyTunerController)h).setIFGain(v)).range(0,15,1,null).unavailableWhen(c -> gainReason(c.getGain().name(), false, null)));
        s.add(setting(t, Integer.class, "mixer_gain", "Mixer gain", AirspyTunerConfiguration::getMixerGain, AirspyTunerConfiguration::setMixerGain,
            (h,c,v) -> ((AirspyTunerController)h).setMixerGain(v)).range(0,15,1,null).unavailableWhen(c -> gainReason(c.getGain().name(), c.isMixerAGC(), "Mixer")));
        s.add(setting(t, Integer.class, "lna_gain", "LNA gain", AirspyTunerConfiguration::getLNAGain, AirspyTunerConfiguration::setLNAGain,
            (h,c,v) -> ((AirspyTunerController)h).setLNAGain(v)).range(0,14,1,null).unavailableWhen(c -> gainReason(c.getGain().name(), c.isLNAAGC(), "LNA")));
        s.add(setting(t, Boolean.class, "mixer_agc", "Mixer AGC", AirspyTunerConfiguration::isMixerAGC, AirspyTunerConfiguration::setMixerAGC,
            (h,c,v) -> ((AirspyTunerController)h).setMixerAGC(v)).unavailableWhen(c -> gainReason(c.getGain().name(), false, null)));
        s.add(setting(t, Boolean.class, "lna_agc", "LNA AGC", AirspyTunerConfiguration::isLNAAGC, AirspyTunerConfiguration::setLNAAGC,
            (h,c,v) -> ((AirspyTunerController)h).setLNAAGC(v)).unavailableWhen(c -> gainReason(c.getGain().name(), false, null)));
    }

    private static void hydra(List<TunerSettingDefinition<?,?>> s)
    {
        Class<HydraSdrTunerConfiguration> t = HydraSdrTunerConfiguration.class;
        s.add(setting(t, Integer.class, "sample_rate", "Sample rate", HydraSdrTunerConfiguration::getSampleRate,
            HydraSdrTunerConfiguration::setSampleRate, TunerSettingDefinitions::applyConfiguration).dynamicRate());
        s.add(setting(t, HydraSdrTunerController.Gain.class, "gain", "Gain preset", HydraSdrTunerConfiguration::getGain, HydraSdrTunerConfiguration::setGain,
            (h,c,v) -> ((HydraSdrTunerController)h).setGain(v)));
        s.add(setting(t, Integer.class, "if_gain", "IF gain", HydraSdrTunerConfiguration::getIFGain, HydraSdrTunerConfiguration::setIFGain,
            (h,c,v) -> ((HydraSdrTunerController)h).setIFGain(v)).range(0,15,1,null).unavailableWhen(c -> gainReason(c.getGain().name(), false, null)));
        s.add(setting(t, Integer.class, "mixer_gain", "Mixer gain", HydraSdrTunerConfiguration::getMixerGain, HydraSdrTunerConfiguration::setMixerGain,
            (h,c,v) -> ((HydraSdrTunerController)h).setMixerGain(v)).range(0,15,1,null).unavailableWhen(c -> gainReason(c.getGain().name(), c.isMixerAGC(), "Mixer")));
        s.add(setting(t, Integer.class, "lna_gain", "LNA gain", HydraSdrTunerConfiguration::getLNAGain, HydraSdrTunerConfiguration::setLNAGain,
            (h,c,v) -> ((HydraSdrTunerController)h).setLNAGain(v)).range(0,14,1,null).unavailableWhen(c -> gainReason(c.getGain().name(), c.isLNAAGC(), "LNA")));
        s.add(setting(t, Boolean.class, "mixer_agc", "Mixer AGC", HydraSdrTunerConfiguration::isMixerAGC, HydraSdrTunerConfiguration::setMixerAGC,
            (h,c,v) -> ((HydraSdrTunerController)h).setMixerAGC(v)).unavailableWhen(c -> gainReason(c.getGain().name(), false, null)));
        s.add(setting(t, Boolean.class, "lna_agc", "LNA AGC", HydraSdrTunerConfiguration::isLNAAGC, HydraSdrTunerConfiguration::setLNAAGC,
            (h,c,v) -> ((HydraSdrTunerController)h).setLNAAGC(v)).unavailableWhen(c -> gainReason(c.getGain().name(), false, null)));
        s.add(setting(t, Boolean.class, "bias_t", "Bias T", HydraSdrTunerConfiguration::isBiasT, HydraSdrTunerConfiguration::setBiasT,
            (h,c,v) -> ((HydraSdrTunerController)h).setBiasT(v)));
    }

    private static void hf(List<TunerSettingDefinition<?,?>> s)
    {
        Class<AirspyHfTunerConfiguration> t = AirspyHfTunerConfiguration.class;
        s.add(setting(t, Integer.class, "sample_rate", "Sample rate", AirspyHfTunerConfiguration::getSampleRate,
            AirspyHfTunerConfiguration::setSampleRate, TunerSettingDefinitions::applyConfiguration).dynamicRate().unavailableWhen(c -> "Fixed"));
        s.add(setting(t, Boolean.class, "agc", "AGC", AirspyHfTunerConfiguration::isAgc, AirspyHfTunerConfiguration::setAgc,
            (h,c,v) -> ((AirspyHfTunerController)h).setAgc(v)));
        s.add(setting(t, Boolean.class, "lna", "LNA", AirspyHfTunerConfiguration::isLna, AirspyHfTunerConfiguration::setLna,
            (h,c,v) -> ((AirspyHfTunerController)h).setLna(v)));
        s.add(setting(t, Integer.class, "attenuation", "Attenuation", AirspyHfTunerConfiguration::getAttenuationValue,
            AirspyHfTunerConfiguration::setAttenuationValue, (h,c,v) -> ((AirspyHfTunerController)h).setAttenuation(c.getAttenuation())).range(0,8,1,"step"));
    }

    private static void rtl(List<TunerSettingDefinition<?,?>> s, TunerConfiguration configuration)
    {
        s.add(setting(RTL2832TunerConfiguration.class, RTL2832TunerController.SampleRate.class, "sample_rate", "Sample rate",
            RTL2832TunerConfiguration::getSampleRate, RTL2832TunerConfiguration::setSampleRate, TunerSettingDefinitions::applyConfiguration));
        s.add(setting(RTL2832TunerConfiguration.class, Boolean.class, "bias_t", "Bias T", RTL2832TunerConfiguration::isBiasT,
            RTL2832TunerConfiguration::setBiasT, (h,c,v) -> ((RTL2832TunerController)h).setBiasT(v)));
        if(configuration instanceof E4KTunerConfiguration)
        {
            Class<E4KTunerConfiguration> t = E4KTunerConfiguration.class;
            s.add(setting(t, E4KEmbeddedTuner.E4KGain.class, "master_gain", "Master gain", E4KTunerConfiguration::getMasterGain,
                E4KTunerConfiguration::setMasterGain, (h,c,v) -> e4k(h).setGain(v, true)));
            s.add(setting(t, E4KEmbeddedTuner.E4KMixerGain.class, "mixer_gain", "Mixer gain", E4KTunerConfiguration::getMixerGain,
                E4KTunerConfiguration::setMixerGain, (h,c,v) -> e4k(h).setMixerGain(v, true)).unavailableWhen(c -> manualReason(c.getMasterGain().name())));
            s.add(setting(t, E4KEmbeddedTuner.E4KLNAGain.class, "lna_gain", "LNA gain", E4KTunerConfiguration::getLNAGain,
                E4KTunerConfiguration::setLNAGain, (h,c,v) -> e4k(h).setLNAGain(v, true)).unavailableWhen(c -> manualReason(c.getMasterGain().name())));
            s.add(setting(t, E4KEmbeddedTuner.IFGain.class, "if_gain", "IF gain", E4KTunerConfiguration::getIFGain,
                E4KTunerConfiguration::setIFGain, (h,c,v) -> e4k(h).setIFGain(v, true)).unavailableWhen(c -> manualReason(c.getMasterGain().name())));
        }
        else if(configuration instanceof FC0013TunerConfiguration)
        {
            Class<FC0013TunerConfiguration> t = FC0013TunerConfiguration.class;
            s.add(setting(t, FC0013EmbeddedTuner.LNAGain.class, "lna_gain", "LNA gain", FC0013TunerConfiguration::getLnaGain,
                FC0013TunerConfiguration::setLnaGain, (h,c,v) -> fc0013(h).setGain(c.getAGC(), v)).unavailableWhen(c -> c.getAGC() ? "Turn off AGC" : null));
            s.add(setting(t, Boolean.class, "agc", "AGC", FC0013TunerConfiguration::getAGC, FC0013TunerConfiguration::setAGC,
                (h,c,v) -> fc0013(h).setGain(v, c.getLnaGain())));
        }
        else if(configuration instanceof R8xTunerConfiguration)
        {
            Class<R8xTunerConfiguration> t = R8xTunerConfiguration.class;
            s.add(setting(t, R8xEmbeddedTuner.MasterGain.class, "master_gain", "Master gain", R8xTunerConfiguration::getMasterGain,
                R8xTunerConfiguration::setMasterGain, (h,c,v) -> r8x(h).setGain(v, true)));
            s.add(setting(t, R8xEmbeddedTuner.MixerGain.class, "mixer_gain", "Mixer gain", R8xTunerConfiguration::getMixerGain,
                R8xTunerConfiguration::setMixerGain, (h,c,v) -> r8x(h).setMixerGain(v, true)).unavailableWhen(c -> manualReason(c.getMasterGain().name())));
            s.add(setting(t, R8xEmbeddedTuner.LNAGain.class, "lna_gain", "LNA gain", R8xTunerConfiguration::getLNAGain,
                R8xTunerConfiguration::setLNAGain, (h,c,v) -> r8x(h).setLNAGain(v, true)).unavailableWhen(c -> manualReason(c.getMasterGain().name())));
            s.add(setting(t, R8xEmbeddedTuner.VGAGain.class, "vga_gain", "VGA gain", R8xTunerConfiguration::getVGAGain,
                R8xTunerConfiguration::setVGAGain, (h,c,v) -> r8x(h).setVGAGain(v, true)).unavailableWhen(c -> manualReason(c.getMasterGain().name())));
        }
    }

    private static void hackrf(List<TunerSettingDefinition<?,?>> s)
    {
        Class<HackRFTunerConfiguration> t = HackRFTunerConfiguration.class;
        s.add(setting(t, HackRFTunerController.HackRFSampleRate.class, "sample_rate", "Sample rate", HackRFTunerConfiguration::getSampleRate,
            HackRFTunerConfiguration::setSampleRate, TunerSettingDefinitions::applyConfiguration));
        s.add(setting(t, HackRFTunerController.HackRFLNAGain.class, "lna_gain", "LNA gain", HackRFTunerConfiguration::getLNAGain,
            HackRFTunerConfiguration::setLNAGain, (h,c,v) -> ((HackRFTunerController)h).setLNAGain(v)));
        s.add(setting(t, HackRFTunerController.HackRFVGAGain.class, "vga_gain", "VGA gain", HackRFTunerConfiguration::getVGAGain,
            HackRFTunerConfiguration::setVGAGain, (h,c,v) -> ((HackRFTunerController)h).setVGAGain(v)));
        s.add(setting(t, Boolean.class, "amplifier", "RF amplifier", HackRFTunerConfiguration::getAmplifierEnabled,
            HackRFTunerConfiguration::setAmplifierEnabled, (h,c,v) -> ((HackRFTunerController)h).setAmplifierEnabled(v)));
    }

    private static void rsp(List<TunerSettingDefinition<?,?>> s, TunerConfiguration configuration)
    {
        TunerSettingDefinition<RspTunerConfiguration,RspSampleRate> rate = setting(RspTunerConfiguration.class, RspSampleRate.class,
            "sample_rate", "Sample rate", RspTunerConfiguration::getSampleRate, RspTunerConfiguration::setSampleRate,
            (h,c,v) -> ((RspTunerController<?>)h).setSampleRate(v));
        if(configuration instanceof RspDuoTuner1Configuration || configuration instanceof RspDuoTuner2Configuration)
            rate.deviceScope().dynamicRate();
        s.add(rate);
        s.add(setting(RspTunerConfiguration.class, Integer.class, "baseband_gain_reduction", "Baseband gain reduction",
            RspTunerConfiguration::getBasebandGainReduction, RspTunerConfiguration::setBasebandGainReduction,
            (h,c,v) -> ((RspTunerController<?>)h).getControlRsp().setGain(((RspTunerController<?>)h).getControlRsp().getLNA(), v)).range(20,59,1,"dB"));
        s.add(setting(RspTunerConfiguration.class, Integer.class, "lna", "LNA state", RspTunerConfiguration::getLNA, RspTunerConfiguration::setLNA,
            (h,c,v) -> ((RspTunerController<?>)h).getControlRsp().setGain(v, c.getBasebandGainReduction())).range(0,null,1,null));
        s.add(setting(RspTunerConfiguration.class, AgcMode.class, "agc_mode", "AGC mode", RspTunerConfiguration::getAgcMode,
            RspTunerConfiguration::setAgcMode, (h,c,v) -> ((RspTunerController<?>)h).getControlRsp().setAgcMode(v)));
        if(configuration instanceof Rsp1aTunerConfiguration)
        {
            Class<Rsp1aTunerConfiguration> t = Rsp1aTunerConfiguration.class;
            s.add(setting(t, Boolean.class, "rf_notch", "RF notch", Rsp1aTunerConfiguration::isRfNotch, Rsp1aTunerConfiguration::setRfNotch, (h,c,v) -> ((IControlRsp1a)rspControl(h)).setRfNotch(v)));
            s.add(setting(t, Boolean.class, "dab_notch", "DAB notch", Rsp1aTunerConfiguration::isRfDabNotch, Rsp1aTunerConfiguration::setRfDabNotch, (h,c,v) -> ((IControlRsp1a)rspControl(h)).setRfDabNotch(v)));
            s.add(setting(t, Boolean.class, "bias_t", "Bias T", Rsp1aTunerConfiguration::isBiasT, Rsp1aTunerConfiguration::setBiasT, (h,c,v) -> ((IControlRsp1a)rspControl(h)).setBiasT(v)));
        }
        else if(configuration instanceof Rsp1bTunerConfiguration)
        {
            Class<Rsp1bTunerConfiguration> t = Rsp1bTunerConfiguration.class;
            s.add(setting(t, Boolean.class, "rf_notch", "RF notch", Rsp1bTunerConfiguration::isRfNotch, Rsp1bTunerConfiguration::setRfNotch, (h,c,v) -> ((IControlRsp1b)rspControl(h)).setRfNotch(v)));
            s.add(setting(t, Boolean.class, "dab_notch", "DAB notch", Rsp1bTunerConfiguration::isRfDabNotch, Rsp1bTunerConfiguration::setRfDabNotch, (h,c,v) -> ((IControlRsp1b)rspControl(h)).setRfDabNotch(v)));
            s.add(setting(t, Boolean.class, "bias_t", "Bias T", Rsp1bTunerConfiguration::isBiasT, Rsp1bTunerConfiguration::setBiasT, (h,c,v) -> ((IControlRsp1b)rspControl(h)).setBiasT(v)));
        }
        else if(configuration instanceof Rsp2TunerConfiguration)
        {
            Class<Rsp2TunerConfiguration> t = Rsp2TunerConfiguration.class;
            s.add(setting(t, Boolean.class, "rf_notch", "RF notch", Rsp2TunerConfiguration::isRfNotch, Rsp2TunerConfiguration::setRfNotch, (h,c,v) -> ((IControlRsp2)rspControl(h)).setRfNotch(v)));
            s.add(setting(t, Boolean.class, "bias_t", "Bias T", Rsp2TunerConfiguration::isBiasT, Rsp2TunerConfiguration::setBiasT, (h,c,v) -> ((IControlRsp2)rspControl(h)).setBiasT(v)));
            s.add(setting(t, Rsp2AntennaSelection.class, "antenna", "Antenna", Rsp2TunerConfiguration::getAntennaSelection, Rsp2TunerConfiguration::setAntennaSelection, (h,c,v) -> ((IControlRsp2)rspControl(h)).setAntennaSelection(v)));
            s.add(setting(t, Boolean.class, "external_reference", "External reference", Rsp2TunerConfiguration::isExternalReferenceOutput, Rsp2TunerConfiguration::setExternalReferenceOutput, (h,c,v) -> ((IControlRsp2)rspControl(h)).setExternalReferenceOutput(v)).deviceScope());
        }
        else if(configuration instanceof RspDuoTuner1Configuration)
        {
            Class<RspDuoTuner1Configuration> t = RspDuoTuner1Configuration.class;
            s.add(setting(t, Boolean.class, "rf_notch", "RF notch", RspDuoTuner1Configuration::isRfNotch, RspDuoTuner1Configuration::setRfNotch, (h,c,v) -> ((IControlRspDuo)rspControl(h)).setRfNotch(v)));
            s.add(setting(t, Boolean.class, "dab_notch", "DAB notch", RspDuoTuner1Configuration::isRfDabNotch, RspDuoTuner1Configuration::setRfDabNotch, (h,c,v) -> ((IControlRspDuo)rspControl(h)).setRfDabNotch(v)));
            s.add(setting(t, RspDuoAmPort.class, "am_port", "AM port", RspDuoTuner1Configuration::getAmPort, RspDuoTuner1Configuration::setAmPort, (h,c,v) -> ((IControlRspDuoTuner1)rspControl(h)).setAmPort(v)));
            s.add(setting(t, Boolean.class, "am_notch", "AM notch", RspDuoTuner1Configuration::isAmNotch, RspDuoTuner1Configuration::setAmNotch, (h,c,v) -> ((IControlRspDuoTuner1)rspControl(h)).setAmNotch(v)));
            s.add(setting(t, Boolean.class, "external_reference", "External reference", RspDuoTuner1Configuration::isExternalReferenceOutput, RspDuoTuner1Configuration::setExternalReferenceOutput, (h,c,v) -> ((IControlRspDuo)rspControl(h)).setExternalReferenceOutput(v)).deviceScope());
        }
        else if(configuration instanceof RspDuoTuner2Configuration)
        {
            Class<RspDuoTuner2Configuration> t = RspDuoTuner2Configuration.class;
            s.add(setting(t, Boolean.class, "rf_notch", "RF notch", RspDuoTuner2Configuration::isRfNotch, RspDuoTuner2Configuration::setRfNotch, (h,c,v) -> ((IControlRspDuo)rspControl(h)).setRfNotch(v)));
            s.add(setting(t, Boolean.class, "dab_notch", "DAB notch", RspDuoTuner2Configuration::isRfDabNotch, RspDuoTuner2Configuration::setRfDabNotch, (h,c,v) -> ((IControlRspDuo)rspControl(h)).setRfDabNotch(v)));
            s.add(setting(t, Boolean.class, "bias_t", "Bias T", RspDuoTuner2Configuration::isBiasT, RspDuoTuner2Configuration::setBiasT, (h,c,v) -> ((IControlRspDuoTuner2)rspControl(h)).setBiasT(v)));
            s.add(setting(t, Boolean.class, "external_reference", "External reference", RspDuoTuner2Configuration::isExternalReferenceOutput, RspDuoTuner2Configuration::setExternalReferenceOutput, (h,c,v) -> ((IControlRspDuo)rspControl(h)).setExternalReferenceOutput(v)).deviceScope());
        }
        else if(configuration instanceof RspDxTunerConfiguration)
        {
            Class<RspDxTunerConfiguration> t = RspDxTunerConfiguration.class;
            s.add(setting(t, Boolean.class, "rf_notch", "RF notch", RspDxTunerConfiguration::isRfNotch, RspDxTunerConfiguration::setRfNotch, (h,c,v) -> ((IControlRspDx)rspControl(h)).setRfNotch(v)));
            s.add(setting(t, Boolean.class, "dab_notch", "DAB notch", RspDxTunerConfiguration::isRfDabNotch, RspDxTunerConfiguration::setRfDabNotch, (h,c,v) -> ((IControlRspDx)rspControl(h)).setRfDabNotch(v)));
            s.add(setting(t, Boolean.class, "bias_t", "Bias T", RspDxTunerConfiguration::isBiasT, RspDxTunerConfiguration::setBiasT, (h,c,v) -> ((IControlRspDx)rspControl(h)).setBiasT(v)));
            s.add(setting(t, RspDxAntenna.class, "antenna", "Antenna", RspDxTunerConfiguration::getAntenna, RspDxTunerConfiguration::setAntenna, (h,c,v) -> ((IControlRspDx)rspControl(h)).setAntenna(v)));
            s.add(setting(t, Boolean.class, "hdr_mode", "HDR mode", RspDxTunerConfiguration::isHdrMode, RspDxTunerConfiguration::setHdrMode, (h,c,v) -> ((IControlRspDx)rspControl(h)).setHighDynamicRange(v)));
            s.add(setting(t, HdrModeBandwidth.class, "hdr_bandwidth", "HDR bandwidth", RspDxTunerConfiguration::getHdrModeBandwidth, RspDxTunerConfiguration::setHdrModeBandwidth, (h,c,v) -> ((IControlRspDx)rspControl(h)).setHdrModeBandwidth(v)));
        }
    }

    static <C extends TunerConfiguration,V> TunerSettingDefinition<C,V> setting(Class<C> configurationType,
        Class<V> valueType, String id, String label, Function<C,V> read, BiConsumer<C,V> write,
        TunerSettingDefinition.HardwareWrite<C,V> apply)
    {
        return new TunerSettingDefinition<>(configurationType, valueType, id, label, read, write, apply);
    }

    private static void applyConfiguration(TunerController controller, TunerConfiguration configuration, Object value)
        throws Exception
    {
        // Only sample-rate definitions use whole-configuration apply; their caller requires Setup and idle hardware.
        controller.apply(configuration);
    }

    private static E4KEmbeddedTuner e4k(TunerController h) { return (E4KEmbeddedTuner)((RTL2832TunerController)h).getEmbeddedTuner(); }
    private static FC0013EmbeddedTuner fc0013(TunerController h) { return (FC0013EmbeddedTuner)((RTL2832TunerController)h).getEmbeddedTuner(); }
    private static R8xEmbeddedTuner r8x(TunerController h) { return (R8xEmbeddedTuner)((RTL2832TunerController)h).getEmbeddedTuner(); }
    private static Object rspControl(TunerController h) { return ((RspTunerController<?>)h).getControlRsp(); }
    private static String manualReason(String preset) { return "MANUAL".equals(preset) ? null : "Use manual gain"; }
    private static String gainReason(String preset, boolean agc, String stage)
    {
        return !"CUSTOM".equals(preset) ? "Use Custom gain" : agc ? "Turn off " + stage + " AGC" : null;
    }
}
