/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */

package io.github.dsheirer.channel;

import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.alias.AliasMatchRegistry;
import io.github.dsheirer.configuration.ChannelConfigurationPolicy;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.dsp.squelch.NoiseSquelch;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.am.DecodeConfigAM;
import io.github.dsheirer.module.decode.analog.DecodeConfigAnalog;
import io.github.dsheirer.module.decode.config.AuxDecodeConfiguration;
import io.github.dsheirer.module.decode.config.DecodeConfiguration;
import io.github.dsheirer.module.decode.dmr.DMRChannelMode;
import io.github.dsheirer.module.decode.dmr.DecodeConfigDMR;
import io.github.dsheirer.module.decode.dmr.channel.TimeslotFrequency;
import io.github.dsheirer.module.decode.nbfm.DecodeConfigNBFM;
import io.github.dsheirer.module.decode.nxdn.DecodeConfigNXDN;
import io.github.dsheirer.module.decode.nxdn.NXDNChannelMode;
import io.github.dsheirer.module.decode.nxdn.channel.ChannelFrequency;
import io.github.dsheirer.module.decode.nxdn.layer3.proprietary.Encoding;
import io.github.dsheirer.module.decode.nxdn.layer3.type.TransmissionMode;
import io.github.dsheirer.module.decode.p25.P25SiteIdentity;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Conventional;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import io.github.dsheirer.module.decode.p25.phase1.Modulation;
import io.github.dsheirer.module.decode.p25.phase2.DecodeConfigP25Phase2;
import io.github.dsheirer.module.decode.p25.phase2.enumeration.ScrambleParameters;
import io.github.dsheirer.module.log.EventLogType;
import io.github.dsheirer.module.log.config.EventLogConfiguration;
import io.github.dsheirer.record.RecorderType;
import io.github.dsheirer.record.config.RecordConfiguration;
import io.github.dsheirer.source.config.SourceConfigTuner;
import io.github.dsheirer.source.config.SourceConfigTunerMultipleFrequency;
import io.github.dsheirer.source.config.SourceConfiguration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Explicit adapter between the protocol-neutral web contract and receiver runtime configuration classes. */
public final class ChannelDefinitionCodec
{
    private static final long MINIMUM_FREQUENCY_HZ = 1L;
    private static final long MAXIMUM_FREQUENCY_HZ = 9_999_999_999L;
    private static final int MAXIMUM_TEXT_LENGTH = 256;
    private final ChannelProtocolRegistry mRegistry;

    public ChannelDefinitionCodec(ChannelProtocolRegistry registry)
    {
        mRegistry = Objects.requireNonNull(registry);
    }

    public ChannelDefinition fromChannel(Channel channel)
    {
        Objects.requireNonNull(channel, "Channel cannot be null");
        DecodeConfiguration decoder = Objects.requireNonNull(channel.getDecodeConfiguration(),
            "Channel decoder cannot be null");
        ChannelProtocolRegistry.Profile profile = mRegistry.require(decoder.getDecoderType());
        Map<String,Object> settings = new LinkedHashMap<>();
        List<ChannelDefinition.FrequencyMapEntry> frequencyMap = new ArrayList<>();

        switch(decoder.getDecoderType())
        {
            case AM, NBFM -> readAnalog((DecodeConfigNBFM)decoder, settings, decoder.getDecoderType() == DecoderType.AM);
            case DMR -> {
                DecodeConfigDMR dmr = (DecodeConfigDMR)decoder;
                settings.put("channel_mode", dmr.getChannelMode().name());
                settings.put("traffic_channel_pool_size", dmr.getTrafficChannelPoolSize());
                settings.put("ignore_data_calls", dmr.getIgnoreDataCalls());
                settings.put("ignore_crc_checksums", dmr.getIgnoreCRCChecksums());
                settings.put("use_compressed_talkgroups", dmr.isUseCompressedTalkgroups());
                dmr.getTimeslotMap().forEach(entry -> frequencyMap.add(new ChannelDefinition.FrequencyMapEntry(
                    entry.getNumber(), entry.getDownlinkFrequency(), entry.getUplinkFrequency())));
            }
            case NXDN -> {
                DecodeConfigNXDN nxdn = (DecodeConfigNXDN)decoder;
                settings.put("channel_mode", nxdn.getChannelMode().name());
                settings.put("transmission_mode", nxdn.getTransmissionMode().name());
                settings.put("talker_alias_encoding", nxdn.getEncoding().name());
                settings.put("traffic_channel_pool_size", nxdn.getTrafficChannelPoolSize());
                settings.put("ignore_data_calls", nxdn.isIgnoreDataCalls());
                settings.put("ignore_encrypted_calls", nxdn.isIgnoreEncryptedCalls());
                if(nxdn.getChannelMap() != null)
                {
                    nxdn.getChannelMap().forEach(entry -> frequencyMap.add(
                        new ChannelDefinition.FrequencyMapEntry(entry.getChannel(), entry.getDownlink(),
                            entry.getUplink())));
                }
            }
            case P25_CONVENTIONAL -> settings.put("modulation",
                ((DecodeConfigP25Conventional)decoder).getModulation().name());
            case P25_PHASE1 -> {
                DecodeConfigP25Phase1 p25 = (DecodeConfigP25Phase1)decoder;
                readP25(p25, settings);
                settings.put("modulation", p25.getModulation().name());
            }
            case P25_PHASE2 -> {
                DecodeConfigP25Phase2 p25 = (DecodeConfigP25Phase2)decoder;
                readP25(p25, settings);
                settings.put("auto_detect_scramble_parameters", p25.isAutoDetectScrambleParameters());
                ScrambleParameters scramble = p25.getScrambleParameters();
                settings.put("scramble_wacn", scramble != null ? scramble.getWACN() : 0);
                settings.put("scramble_system", scramble != null ? scramble.getSystem() : 0);
                settings.put("scramble_nac", scramble != null ? scramble.getNAC() : 0);
            }
            default -> throw new IllegalArgumentException("Unsupported channel decoder: " + decoder.getDecoderType());
        }

        return new ChannelDefinition(channel.getConfigurationId(), profile.id(), channel.getSystem(), channel.getSite(),
            channel.getName(), channel.hasRadioResolveId() ? channel.getRadioResolveId() : null,
            channel.getAliasListId(), sourceFrom(channel.getSourceConfiguration()), settings, frequencyMap,
            enumNames(channel.getEventLogConfiguration() != null ? channel.getEventLogConfiguration().getLoggers() :
                List.of()),
            enumNames(channel.getRecordConfiguration() != null ? channel.getRecordConfiguration().getRecorders() :
                List.of()),
            enumNames(channel.getAuxDecodeConfiguration() != null ?
                channel.getAuxDecodeConfiguration().getAuxDecoders() : List.of()), observedFrom(channel));
    }

    /** Builds a detached runtime candidate. Existing learned state is retained only for an in-place edit. */
    public Channel toChannel(ChannelDefinition submitted, AliasListDefinition aliasList, Channel existing)
    {
        Objects.requireNonNull(submitted, "Channel definition cannot be null");
        ChannelProtocolRegistry.Profile profile = mRegistry.require(submitted.protocolId());
        if(existing != null && existing.getDecodeConfiguration().getDecoderType() != profile.decoderType())
        {
            throw new IllegalArgumentException("Channel protocol cannot be changed; clone a new channel instead");
        }
        validateCommon(submitted, aliasList, profile);
        Map<String,Object> settings = mRegistry.validateSettings(profile, submitted.settings());
        SourceConfiguration source = sourceTo(submitted.source(), profile);
        DecodeConfiguration decoder = decoderTo(profile, settings, submitted.frequencyMap(), existing);
        if(profile.channelKind() != null)
        {
            Channel probe = new Channel();
            probe.setDecodeConfiguration(decoder);
            probe.setSourceConfiguration(source);
            if(ChannelConfigurationPolicy.requireChannelKind(probe) != profile.channelKind())
            {
                throw new IllegalArgumentException("Channel kind does not match protocol profile");
            }
        }

        Channel channel = new Channel(requiredName(submitted.name()));
        if(existing != null)
        {
            channel.setConfigurationId(existing.getConfigurationId());
        }
        else if(submitted.configurationId() != null && !submitted.configurationId().isBlank())
        {
            throw new IllegalArgumentException("New channels cannot choose a configuration ID");
        }
        channel.setSystem(optionalText(submitted.system(), "System"));
        channel.setSite(optionalText(submitted.site(), "Site"));
        channel.setName(requiredName(submitted.name()));
        channel.setRadioResolveId(validRadioResolveId(submitted.radioResolveId()));
        channel.setAliasListDefinition(aliasList);
        channel.setSourceConfiguration(source);
        channel.setDecodeConfiguration(decoder);
        channel.setAuxDecodeConfiguration(auxiliaryTo(profile, submitted.auxiliaryDecoders()));
        channel.setEventLogConfiguration(loggersTo(profile, submitted.eventLogs()));
        channel.setRecordConfiguration(recordersTo(profile, submitted.recorders()));
        if(existing != null)
        {
            channel.setAutoStart(existing.getAutoStart());
            channel.setAutoStartOrder(existing.getAutoStartOrder());
            channel.setP25SiteIdentity(existing.getP25SiteIdentity());
        }
        return channel;
    }

    /** Canonical clone through the neutral codec; runtime identity, learned state, and autostart are intentionally reset. */
    public Channel cloneChannel(Channel source, AliasListDefinition aliasList)
    {
        ChannelDefinition definition = fromChannel(source);
        ChannelDefinition clone = new ChannelDefinition(null, definition.protocolId(), definition.system(),
            definition.site(), definition.name(), null, definition.aliasListId(), definition.source(),
            definition.settings(), definition.frequencyMap(), definition.eventLogs(), definition.recorders(),
            definition.auxiliaryDecoders(), ChannelDefinition.Observed.EMPTY);
        Channel result = toChannel(clone, aliasList, null);
        result.setAutoStart(false);
        result.setAutoStartOrder(null);
        return result;
    }

    private static void readAnalog(DecodeConfigNBFM analog, Map<String,Object> settings, boolean am)
    {
        settings.put("bandwidth", analog.getBandwidth().name());
        settings.put("talkgroup", analog.getTalkgroup());
        if(!am) settings.put("deemphasis", analog.getDeemphasis().name());
        settings.put("high_pass_enabled", analog.isAudioFilter());
        settings.put("low_pass_enabled", analog.isLowPassEnabled());
        settings.put("low_pass_cutoff_hz", analog.getLowPassCutoff());
        settings.put("voice_enhance_percent", analog.getVoiceEnhanceAmount());
        settings.put("bass_boost_db", analog.getBassBoostDb());
        settings.put("output_gain", analog.getOutputGain());
        settings.put("squelch_noise_open", analog.getSquelchNoiseOpenThreshold());
        settings.put("squelch_noise_close", analog.getSquelchNoiseCloseThreshold());
        settings.put("squelch_hysteresis_open", analog.getSquelchHysteresisOpenThreshold());
        settings.put("squelch_hysteresis_close", analog.getSquelchHysteresisCloseThreshold());
        settings.put("squelch_trim_enabled", analog.isSquelchTailRemovalEnabled());
        settings.put("squelch_tail_ms", analog.getSquelchTailRemovalMs());
        settings.put("squelch_head_ms", analog.getSquelchHeadRemovalMs());
    }

    private static void readP25(DecodeConfigP25 p25, Map<String,Object> settings)
    {
        settings.put("traffic_channel_pool_size", p25.getTrafficChannelPoolSize());
        settings.put("ignore_data_calls", p25.getIgnoreDataCalls());
        settings.put("learn_announced_control_channels", p25.getLearnAnnouncedControlChannels());
        settings.put("use_bandplan_override", p25.getUseP25BandplanOverride());
    }

    private DecodeConfiguration decoderTo(ChannelProtocolRegistry.Profile profile, Map<String,Object> settings,
                                          List<ChannelDefinition.FrequencyMapEntry> map, Channel existing)
    {
        return switch(profile.decoderType())
        {
            case AM -> analogTo(new DecodeConfigAM(), settings, true);
            case NBFM -> analogTo(new DecodeConfigNBFM(), settings, false);
            case DMR -> dmrTo(profile, settings, map);
            case NXDN -> nxdnTo(profile, settings, map);
            case P25_CONVENTIONAL -> {
                DecodeConfigP25Conventional p25 = new DecodeConfigP25Conventional();
                p25.setModulation(enumValue(Modulation.class, text(settings, "modulation"), "Modulation"));
                yield p25;
            }
            case P25_PHASE1 -> {
                DecodeConfigP25Phase1 p25 = new DecodeConfigP25Phase1();
                writeP25(p25, settings, existing);
                p25.setModulation(enumValue(Modulation.class, text(settings, "modulation"), "Modulation"));
                yield p25;
            }
            case P25_PHASE2 -> {
                DecodeConfigP25Phase2 p25 = new DecodeConfigP25Phase2();
                writeP25(p25, settings, existing);
                boolean auto = bool(settings, "auto_detect_scramble_parameters");
                p25.setAutoDetectScrambleParameters(auto);
                if(!auto)
                {
                    p25.setScrambleParameters(new ScrambleParameters(integer(settings, "scramble_wacn"),
                        integer(settings, "scramble_system"), integer(settings, "scramble_nac")));
                }
                yield p25;
            }
            default -> throw new IllegalArgumentException("Unsupported channel decoder: " + profile.decoderType());
        };
    }

    private static <T extends DecodeConfigNBFM> T analogTo(T analog, Map<String,Object> settings, boolean am)
    {
        DecodeConfigAnalog.Bandwidth bandwidth = enumValue(DecodeConfigAnalog.Bandwidth.class,
            text(settings, "bandwidth"), "Bandwidth");
        if(am && !bandwidth.isAM() || !am && !bandwidth.isFM())
        {
            throw new IllegalArgumentException("Bandwidth is not supported by this analog protocol");
        }
        analog.setBandwidth(bandwidth);
        analog.setTalkgroup(integer(settings, "talkgroup"));
        analog.setDeemphasis(am ? DecodeConfigNBFM.DeemphasisMode.NONE :
            enumValue(DecodeConfigNBFM.DeemphasisMode.class, text(settings, "deemphasis"), "De-emphasis"));
        analog.setAudioFilter(bool(settings, "high_pass_enabled"));
        analog.setLowPassEnabled(bool(settings, "low_pass_enabled"));
        analog.setLowPassCutoff(integer(settings, "low_pass_cutoff_hz"));
        analog.setVoiceEnhanceAmount(decimal(settings, "voice_enhance_percent"));
        analog.setBassBoostDb(decimal(settings, "bass_boost_db"));
        analog.setOutputGain(decimal(settings, "output_gain"));
        float noiseOpen = decimal(settings, "squelch_noise_open");
        float noiseClose = decimal(settings, "squelch_noise_close");
        int hysteresisOpen = integer(settings, "squelch_hysteresis_open");
        int hysteresisClose = integer(settings, "squelch_hysteresis_close");
        //The individual model setters do not enforce the relationship; validate the pair through the runtime policy.
        new NoiseSquelch(noiseOpen, noiseClose, hysteresisOpen, hysteresisClose);
        analog.setSquelchNoiseOpenThreshold(noiseOpen);
        analog.setSquelchNoiseCloseThreshold(noiseClose);
        analog.setSquelchHysteresisOpenThreshold(hysteresisOpen);
        analog.setSquelchHysteresisCloseThreshold(hysteresisClose);
        analog.setSquelchTailRemovalEnabled(bool(settings, "squelch_trim_enabled"));
        analog.setSquelchTailRemovalMs(integer(settings, "squelch_tail_ms"));
        analog.setSquelchHeadRemovalMs(integer(settings, "squelch_head_ms"));
        return analog;
    }

    private static DecodeConfigDMR dmrTo(ChannelProtocolRegistry.Profile profile, Map<String,Object> settings,
                                         List<ChannelDefinition.FrequencyMapEntry> submitted)
    {
        DecodeConfigDMR dmr = new DecodeConfigDMR();
        dmr.setChannelMode(enumValue(DMRChannelMode.class, text(settings, "channel_mode"), "Channel mode"));
        dmr.setTrafficChannelPoolSize(integer(settings, "traffic_channel_pool_size"));
        dmr.setIgnoreDataCalls(bool(settings, "ignore_data_calls"));
        dmr.setIgnoreCRCChecksums(bool(settings, "ignore_crc_checksums"));
        dmr.setUseCompressedTalkgroups(bool(settings, "use_compressed_talkgroups"));
        List<TimeslotFrequency> result = new ArrayList<>();
        validateMap(profile, submitted).forEach(entry -> {
            TimeslotFrequency mapping = new TimeslotFrequency();
            mapping.setNumber(entry.number());
            mapping.setDownlinkFrequency(entry.downlinkHz());
            mapping.setUplinkFrequency(entry.uplinkHz());
            result.add(mapping);
        });
        dmr.setTimeslotMap(result);
        return dmr;
    }

    private static DecodeConfigNXDN nxdnTo(ChannelProtocolRegistry.Profile profile, Map<String,Object> settings,
                                           List<ChannelDefinition.FrequencyMapEntry> submitted)
    {
        DecodeConfigNXDN nxdn = new DecodeConfigNXDN();
        nxdn.setChannelMode(enumValue(NXDNChannelMode.class, text(settings, "channel_mode"), "Channel mode"));
        nxdn.setTransmissionMode(enumValue(TransmissionMode.class, text(settings, "transmission_mode"),
            "Transmission mode"));
        nxdn.setEncoding(enumValue(Encoding.class, text(settings, "talker_alias_encoding"),
            "Talker alias encoding"));
        nxdn.setTrafficChannelPoolSize(integer(settings, "traffic_channel_pool_size"));
        nxdn.setIgnoreDataCalls(bool(settings, "ignore_data_calls"));
        nxdn.setIgnoreEncryptedCalls(bool(settings, "ignore_encrypted_calls"));
        List<ChannelFrequency> result = validateMap(profile, submitted).stream()
            .map(entry -> new ChannelFrequency(entry.number(), entry.downlinkHz(), entry.uplinkHz())).toList();
        nxdn.setChannelMap(result);
        return nxdn;
    }

    private static List<ChannelDefinition.FrequencyMapEntry> validateMap(ChannelProtocolRegistry.Profile profile,
        List<ChannelDefinition.FrequencyMapEntry> submitted)
    {
        ChannelProtocolRegistry.FieldRule rule = Objects.requireNonNull(profile.fields().get("frequency_map"),
            "Frequency-map protocol rule is missing");
        int minimumNumber = rule.numberMinimum() != null ? rule.numberMinimum() : 1;
        int maximumNumber = rule.numberMaximum() != null ? rule.numberMaximum() : Integer.MAX_VALUE;
        List<ChannelDefinition.FrequencyMapEntry> entries = submitted != null ? submitted : List.of();
        if(entries.size() > 4096)
        {
            throw new IllegalArgumentException("Frequency map cannot exceed 4,096 entries");
        }
        Set<Integer> numbers = new HashSet<>();
        for(ChannelDefinition.FrequencyMapEntry entry: entries)
        {
            if(entry == null || entry.number() < minimumNumber || entry.number() > maximumNumber ||
                !numbers.add(entry.number()))
            {
                throw new IllegalArgumentException("Frequency map numbers must be positive and unique");
            }
            requireFrequency(entry.downlinkHz(), "Map downlink frequency");
            if(entry.uplinkHz() < 0 || entry.uplinkHz() > MAXIMUM_FREQUENCY_HZ ||
                !rule.showUplink() && entry.uplinkHz() != 0)
            {
                throw new IllegalArgumentException("Map uplink frequency is invalid");
            }
        }
        return List.copyOf(entries);
    }

    private static void writeP25(DecodeConfigP25 target, Map<String,Object> settings, Channel existing)
    {
        target.setTrafficChannelPoolSize(integer(settings, "traffic_channel_pool_size"));
        target.setIgnoreDataCalls(bool(settings, "ignore_data_calls"));
        target.setLearnAnnouncedControlChannels(bool(settings, "learn_announced_control_channels"));
        target.setUseP25BandplanOverride(bool(settings, "use_bandplan_override"));
        if(existing != null && existing.getDecodeConfiguration() instanceof DecodeConfigP25 current)
        {
            target.setLearnedControlFrequencies(current.getLearnedControlFrequencies());
        }
    }

    private SourceConfiguration sourceTo(ChannelDefinition.Source submitted, ChannelProtocolRegistry.Profile profile)
    {
        List<Long> frequencies = normalizedFrequencies(submitted.frequenciesHz());
        if(profile.sourceMode() == ChannelProtocolRegistry.SourceMode.SINGLE)
        {
            if(frequencies.size() != 1)
            {
                throw new IllegalArgumentException(profile.label() + " requires exactly one frequency");
            }
            requireAbsent(submitted.minimumFrequencyHz(), "Minimum frequency");
            requireAbsent(submitted.maximumFrequencyHz(), "Maximum frequency");
            requireAbsent(submitted.preferredFrequencyHz(), "Preferred frequency");
            requireAbsent(submitted.rotationDelayMs(), "Frequency rotation delay");
            SourceConfigTuner source = new SourceConfigTuner();
            source.setFrequency(frequencies.get(0));
            source.setPreferredTuner(optionalText(submitted.preferredTuner(), "Preferred tuner"));
            return source;
        }

        if(frequencies.isEmpty())
        {
            throw new IllegalArgumentException(profile.label() + " requires at least one control frequency");
        }
        Long minimum = optionalFrequency(submitted.minimumFrequencyHz(), "Minimum frequency");
        Long maximum = optionalFrequency(submitted.maximumFrequencyHz(), "Maximum frequency");
        if(minimum != null && maximum != null && minimum > maximum)
        {
            throw new IllegalArgumentException("Minimum frequency cannot exceed maximum frequency");
        }
        Long preferred = submitted.preferredFrequencyHz();
        if(preferred != null && !frequencies.contains(preferred))
        {
            throw new IllegalArgumentException("Preferred frequency must be one of the control frequencies");
        }
        ChannelProtocolRegistry.FieldRule rotationRule = profile.fields().get("source.rotation_delay_ms");
        int rotation = submitted.rotationDelayMs() != null ? submitted.rotationDelayMs() :
            ((Number)Objects.requireNonNull(rotationRule.defaultValue(), "Rotation default is missing")).intValue();
        if(rotationRule.minimum() != null && rotation < rotationRule.minimum() ||
            rotationRule.maximum() != null && rotation > rotationRule.maximum())
        {
            throw new IllegalArgumentException("Frequency rotation delay is outside the allowed range");
        }
        SourceConfigTunerMultipleFrequency source = new SourceConfigTunerMultipleFrequency();
        source.setFrequencies(frequencies);
        source.setMinimumFrequency(minimum);
        source.setMaximumFrequency(maximum);
        if(preferred != null) source.setPreferredFrequency(preferred);
        source.setPreferredTuner(optionalText(submitted.preferredTuner(), "Preferred tuner"));
        source.setFrequencyRotationDelay(rotation);
        return source;
    }

    private static ChannelDefinition.Source sourceFrom(SourceConfiguration source)
    {
        if(source instanceof SourceConfigTuner tuner)
        {
            return new ChannelDefinition.Source(List.of(tuner.getFrequency()), null, null, null,
                tuner.getPreferredTuner(), null);
        }
        if(source instanceof SourceConfigTunerMultipleFrequency multiple)
        {
            return new ChannelDefinition.Source(multiple.getFrequencies(), multiple.getMinimumFrequency(),
                multiple.getMaximumFrequency(), multiple.getPersistedPreferredFrequency(),
                multiple.getPreferredTuner(), multiple.getFrequencyRotationDelay());
        }
        throw new IllegalArgumentException("Only active tuner channel sources can be administered on the web");
    }

    private static EventLogConfiguration loggersTo(ChannelProtocolRegistry.Profile profile, List<String> values)
    {
        EventLogConfiguration configuration = new EventLogConfiguration();
        uniqueNames(values, profile.eventLogs(), "event log").forEach(value ->
            configuration.addLogger(enumValue(EventLogType.class, value, "Event log")));
        return configuration;
    }

    private static RecordConfiguration recordersTo(ChannelProtocolRegistry.Profile profile, List<String> values)
    {
        RecordConfiguration configuration = new RecordConfiguration();
        uniqueNames(values, profile.recorders(), "recorder").forEach(value ->
            configuration.addRecorder(enumValue(RecorderType.class, value, "Recorder")));
        return configuration;
    }

    private static AuxDecodeConfiguration auxiliaryTo(ChannelProtocolRegistry.Profile profile, List<String> values)
    {
        AuxDecodeConfiguration configuration = new AuxDecodeConfiguration();
        uniqueNames(values, profile.auxiliaryDecoders(), "auxiliary decoder").forEach(value ->
            configuration.addAuxDecoder(enumValue(DecoderType.class, value, "Auxiliary decoder")));
        return configuration;
    }

    private static Set<String> uniqueNames(List<String> values, Set<String> allowed, String label)
    {
        Set<String> unique = new LinkedHashSet<>();
        if(values != null)
        {
            for(String value: values)
            {
                if(value == null || !allowed.contains(value) || !unique.add(value))
                {
                    throw new IllegalArgumentException("Unsupported or duplicate " + label + ": " + value);
                }
            }
        }
        return unique;
    }

    private static void validateCommon(ChannelDefinition submitted, AliasListDefinition aliasList,
                                       ChannelProtocolRegistry.Profile profile)
    {
        requiredName(submitted.name());
        optionalText(submitted.system(), "System");
        optionalText(submitted.site(), "Site");
        validRadioResolveId(submitted.radioResolveId());
        if(aliasList == null || aliasList.getId() != submitted.aliasListId() ||
            !AliasMatchRegistry.isChannelCompatible(aliasList, profile.decoderType()))
        {
            throw new IllegalArgumentException("Channel requires a compatible Alias List");
        }
    }

    private static List<Long> normalizedFrequencies(List<Long> values)
    {
        LinkedHashSet<Long> unique = new LinkedHashSet<>();
        if(values != null)
        {
            for(Long value: values)
            {
                if(value == null) throw new IllegalArgumentException("Frequency cannot be null");
                requireFrequency(value, "Frequency");
                if(!unique.add(value)) throw new IllegalArgumentException("Frequencies must be unique");
            }
        }
        if(unique.size() > 256) throw new IllegalArgumentException("Channel cannot exceed 256 frequencies");
        return List.copyOf(unique);
    }

    private static long requireFrequency(long value, String label)
    {
        if(value < MINIMUM_FREQUENCY_HZ || value > MAXIMUM_FREQUENCY_HZ)
        {
            throw new IllegalArgumentException(label + " must be between 1 and 9,999,999,999 Hz");
        }
        return value;
    }

    private static Long optionalFrequency(Long value, String label)
    {
        return value != null ? requireFrequency(value, label) : null;
    }

    private static void requireAbsent(Object value, String label)
    {
        if(value != null) throw new IllegalArgumentException(label + " is not supported for this protocol");
    }

    private static String requiredName(String value)
    {
        String name = optionalText(value, "Name");
        if(name == null) throw new IllegalArgumentException("Name cannot be blank");
        return name;
    }

    private static String optionalText(String value, String label)
    {
        if(value == null || value.isBlank()) return null;
        String normalized = value.trim();
        if(normalized.length() > MAXIMUM_TEXT_LENGTH)
        {
            throw new IllegalArgumentException(label + " cannot exceed " + MAXIMUM_TEXT_LENGTH + " characters");
        }
        return normalized;
    }

    private static String validRadioResolveId(String value)
    {
        String normalized = optionalText(value, "RadioResolve ID");
        if(normalized == null) return null;
        try
        {
            return UUID.fromString(normalized).toString();
        }
        catch(IllegalArgumentException exception)
        {
            throw new IllegalArgumentException("RadioResolve ID must be blank or a valid UUID");
        }
    }

    private static boolean bool(Map<String,Object> settings, String key)
    {
        Object value = settings.get(key);
        if(value instanceof Boolean bool) return bool;
        throw new IllegalArgumentException(key + " must be true or false");
    }

    private static int integer(Map<String,Object> settings, String key)
    {
        Object value = settings.get(key);
        if(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)
        {
            long number = ((Number)value).longValue();
            if(number >= Integer.MIN_VALUE && number <= Integer.MAX_VALUE) return (int)number;
        }
        throw new IllegalArgumentException(key + " must be an integer");
    }

    private static float decimal(Map<String,Object> settings, String key)
    {
        Object value = settings.get(key);
        if(value instanceof Number number && Float.isFinite(number.floatValue())) return number.floatValue();
        throw new IllegalArgumentException(key + " must be a number");
    }

    private static String text(Map<String,Object> settings, String key)
    {
        Object value = settings.get(key);
        if(value instanceof String text && !text.isBlank()) return text;
        throw new IllegalArgumentException(key + " must be text");
    }

    private static <T extends Enum<T>> T enumValue(Class<T> type, String value, String label)
    {
        try
        {
            return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
        }
        catch(RuntimeException exception)
        {
            throw new IllegalArgumentException(label + " is not an allowed value");
        }
    }

    private static List<String> enumNames(List<? extends Enum<?>> values)
    {
        return values != null ? values.stream().filter(Objects::nonNull).map(Enum::name).toList() : List.of();
    }

    private static ChannelDefinition.Observed observedFrom(Channel channel)
    {
        List<Long> learned = channel.getDecodeConfiguration() instanceof DecodeConfigP25 p25 ?
            p25.getLearnedControlFrequencies() : List.of();
        P25SiteIdentity identity = channel.getP25SiteIdentity();
        Map<String,Object> site = identity != null ? Map.of("wacn", identity.wacn(), "system", identity.system(),
            "rfss", identity.rfss(), "site", identity.site(), "display", identity.display()) : Map.of();
        return new ChannelDefinition.Observed(learned, site);
    }
}
