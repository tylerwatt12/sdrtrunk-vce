package io.github.dsheirer.source.tuner.manager;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.dsheirer.source.tuner.TunerController;
import io.github.dsheirer.source.tuner.TunerType;
import io.github.dsheirer.source.tuner.airspy.AirspyTunerConfiguration;
import io.github.dsheirer.source.tuner.airspy.AirspyTunerController;
import io.github.dsheirer.source.tuner.airspy.hf.AirspyHfTunerConfiguration;
import io.github.dsheirer.source.tuner.airspy.hf.AirspyHfTunerController;
import io.github.dsheirer.source.tuner.configuration.TunerConfiguration;
import io.github.dsheirer.source.tuner.hackrf.HackRFTunerConfiguration;
import io.github.dsheirer.source.tuner.hackrf.HackRFTunerController.HackRFSampleRate;
import io.github.dsheirer.source.tuner.hackrf.HackRFTunerController;
import io.github.dsheirer.source.tuner.hydrasdr.HydraSdrTunerConfiguration;
import io.github.dsheirer.source.tuner.hydrasdr.HydraSdrTunerController;
import io.github.dsheirer.source.tuner.recording.RecordingTunerConfiguration;
import io.github.dsheirer.source.tuner.rtl.RTL2832TunerController.SampleRate;
import io.github.dsheirer.source.tuner.rtl.e4k.E4KTunerConfiguration;
import io.github.dsheirer.source.tuner.rtl.e4k.E4KEmbeddedTuner;
import io.github.dsheirer.source.tuner.rtl.fc0013.FC0013TunerConfiguration;
import io.github.dsheirer.source.tuner.rtl.fc0013.FC0013EmbeddedTuner;
import io.github.dsheirer.source.tuner.rtl.r8x.R8xTunerConfiguration;
import io.github.dsheirer.source.tuner.rtl.r8x.R8xEmbeddedTuner;
import io.github.dsheirer.source.tuner.sdrplay.RspSampleRate;
import io.github.dsheirer.source.tuner.sdrplay.RspTunerConfiguration;
import io.github.dsheirer.source.tuner.sdrplay.RspTunerController;
import io.github.dsheirer.source.tuner.sdrplay.DiscoveredRspTuner;
import io.github.dsheirer.source.tuner.sdrplay.rspDuo.RspDuoTuner1Configuration;
import io.github.dsheirer.source.tuner.sdrplay.rspDuo.RspDuoTuner2Configuration;
import io.github.dsheirer.source.tuner.sdrplay.rspDuo.IControlRspDuo;
import io.github.dsheirer.source.tuner.sdrplay.rspDuo.DiscoveredRspDuoTuner2;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Device-specific tuner settings described in Java.  The browser renders these descriptors without knowing tuner
 * families, controller classes, or hardware validation rules.  Definitions bind saved values and hardware writes; the application service owns locking and persistence.
 */
public final class TunerSettingCatalog
{
    static final String MINIMUM_FREQUENCY = "minimum_frequency_mhz";
    static final String MAXIMUM_FREQUENCY = "maximum_frequency_mhz";
    static final String RESET_FREQUENCY_EXTENTS = "reset_frequency_extents";
    private static final Set<String> FREQUENCY_GROUP_SETTINGS = Set.of("frequency_mhz", MINIMUM_FREQUENCY,
        MAXIMUM_FREQUENCY, "sample_rate", "center_frequency_locked");
    private static final Set<String> CALIBRATION_GROUP_SETTINGS = Set.of("frequency_correction_ppm",
        "automatic_ppm");
    private static final Set<String> GAIN_GROUP_SETTINGS = Set.of("gain", "if_gain", "mixer_gain", "lna_gain",
        "mixer_agc", "lna_agc", "agc", "lna", "attenuation", "master_gain", "vga_gain", "amplifier",
        "baseband_gain_reduction", "agc_mode");
    private static final Map<DiscoveredTuner,Map<String,List<Option>>> OPTION_CACHE =
        Collections.synchronizedMap(new WeakHashMap<>());

    private TunerSettingCatalog()
    {
    }

    public record Option(Object value, String label)
    {
    }

    public record Dependency(@JsonProperty("setting_id") String settingId, Object equals)
    {
    }

    public record SettingDescriptor(String id, String label, String group, String kind, Object value,
                                    List<Option> options, Number minimum, Number maximum, Number step, String unit,
                                    String scope, boolean editable, String availability,
                                    @JsonProperty("unavailable_reason") String unavailableReason,
                                    List<Dependency> dependencies)
    {
    }

    /** Stable planner identifier, or null for sources the external planner cannot model. */
    public static String plannerModel(TunerType type)
    {
        if(type == null)
        {
            return null;
        }

        return switch(type)
        {
            case AIRSPY_R820T, HYDRASDR_R828D -> "airspy";
            case AIRSPY_HF_PLUS -> "airspy-hf";
            case ELONICS_E4000 -> "rtl-e4k";
            case FITIPOWER_FC0013 -> "rtl-fc0013";
            case RAFAELMICRO_R820T, RAFAELMICRO_R828D -> "rtl-r8x";
            case HACKRF_ONE, HACKRF_JAWBREAKER, HACKRF_RAD1O -> "hackrf";
            case RSP_DUO_1, RSP_DUO_2 -> "sdrplay-duo";
            case RSP_1, RSP_1A, RSP_1B, RSP_2, RSP_DX -> "sdrplay";
            default -> null;
        };
    }

    public static List<SettingDescriptor> describe(DiscoveredTuner discovered)
    {
        return describe(discovered, specs(discovered.getTunerConfiguration()));
    }

    /** The same renderer contract is used for built-in and newly supplied typed family bindings. */
    static List<SettingDescriptor> describe(DiscoveredTuner discovered, List<TunerSettingDefinition<?,?>> definitions)
    {
        TunerConfiguration configuration = discovered.getTunerConfiguration();

        if(configuration == null || configuration instanceof RecordingTunerConfiguration)
        {
            return List.of();
        }

        TunerController controller = discovered.hasTuner() ? discovered.getTuner().getTunerController() : null;
        List<SettingDescriptor> descriptors = new ArrayList<>();

        for(TunerSettingDefinition<?,?> spec: definitions)
        {
            List<Option> options = options(spec, configuration, controller, discovered);
            boolean modeEditable = editableForConfiguration(configuration, spec);
            boolean dependencyEditable = dependencyEditable(configuration, spec.id());
            boolean setupAllowed = !requiresSetup(configuration, spec.id()) ||
                discovered.getOperatorState() != DiscoveredTuner.OperatorState.LIVE;
            boolean editable = (!spec.dynamicOptions() || !options.isEmpty()) && setupAllowed &&
                !("lna".equals(spec.id()) && configuration instanceof RspTunerConfiguration &&
                    !(controller instanceof RspTunerController<?>)) &&
                modeEditable && dependencyEditable &&
                !("device".equals(spec.scope()) && isRspDuoSlave(discovered));
            String availability = "Fixed".equals(spec.unavailableReason(configuration)) ? "read_only" :
                requiresSetup(configuration, spec.id()) ? "setup" : "live";
            descriptors.add(new SettingDescriptor(spec.id(), spec.label(), group(spec.id()),
                kind(spec), read(configuration, spec), options, publicBound(spec, spec.minimum()),
                "lna".equals(spec.id()) && controller instanceof RspTunerController<?> rsp ?
                    rsp.getControlRsp().getMaximumLNASetting() : publicBound(spec, spec.maximum()),
                spec.step(), spec.unit(), spec.scope(), editable, availability,
                editable ? null : unavailableReason(discovered, configuration, spec, options,
                    setupAllowed, modeEditable, dependencyEditable), dependencies(spec.id())));
        }

        if(hasFrequencyExtents(configuration))
        {
            descriptors.add(new SettingDescriptor(RESET_FREQUENCY_EXTENTS, "Reset frequency limits", "frequency",
                "action", null, List.of(), null, null, null, null, "tuner", true, "live", null, List.of()));
        }

        return List.copyOf(descriptors);
    }

    /** UI grouping is owned by the setting catalog, never inferred from tuner models in the browser. */
    private static String group(String settingId)
    {
        if(FREQUENCY_GROUP_SETTINGS.contains(settingId))
        {
            return "frequency";
        }
        if(CALIBRATION_GROUP_SETTINGS.contains(settingId))
        {
            return "calibration";
        }
        if(GAIN_GROUP_SETTINGS.contains(settingId))
        {
            return "gain";
        }
        return "hardware";
    }

    /** Validate and normalize a submitted value before a one-shot hardware write. */
    public static Object validate(DiscoveredTuner discovered, String settingId, Object rawValue)
    {
        return validate(discovered, settingId, rawValue, false);
    }

    /** Validates a center retune while a receiver-owned browse lease temporarily unlocks runtime hardware. */
    static Object validate(DiscoveredTuner discovered, String settingId, Object rawValue,
                           boolean temporaryCenterUnlock)
    {
        TunerConfiguration configuration = discovered.getTunerConfiguration();
        if(RESET_FREQUENCY_EXTENTS.equals(settingId))
        {
            if(!hasFrequencyExtents(configuration) || !Boolean.TRUE.equals(rawValue))
            {
                throw new IllegalArgumentException("Reset frequency limits requires true");
            }
            return true;
        }
        TunerSettingDefinition<?,?> spec = definition(configuration, settingId);
        return validate(discovered, spec, rawValue, temporaryCenterUnlock);
    }

    static Object validate(DiscoveredTuner discovered, TunerSettingDefinition<?,?> spec, Object rawValue,
                           boolean temporaryCenterUnlock)
    {
        TunerConfiguration configuration = discovered.getTunerConfiguration();
        TunerController controller = discovered.hasTuner() ? discovered.getTuner().getTunerController() : null;
        String settingId = spec.id();
        if(!editableForConfiguration(configuration, spec))
            throw new IllegalArgumentException("Setting is not editable in the current tuner mode");
        if(!dependencyEditable(configuration, settingId) &&
            !(temporaryCenterUnlock && "frequency_mhz".equals(settingId)))
            throw new IllegalArgumentException("frequency_correction_ppm".equals(settingId) ?
                "Turn off Auto PPM" : "Unlock center");
        Class<?> type = spec.valueType();
        List<Option> options = options(spec, configuration, controller, discovered);
        if(spec.dynamicOptions() && options.isEmpty())
            throw new IllegalArgumentException("This setting requires the tuner to be available");
        Object value = isFrequencyMHz(spec.id()) ? parseFrequencyMHz(rawValue) : coerce(rawValue, type);
        if("lna".equals(spec.id()) && configuration instanceof RspTunerConfiguration)
        {
            if(!(controller instanceof RspTunerController<?> rsp) ||
                ((Number)value).intValue() < 0 ||
                ((Number)value).intValue() > rsp.getControlRsp().getMaximumLNASetting())
                throw new IllegalArgumentException("LNA setting is unavailable or out of range");
        }
        if(type.isEnum())
        {
            if(options.stream().noneMatch(option -> Objects.equals(option.value(), ((Enum<?>)value).name())))
                throw new IllegalArgumentException("Unsupported tuner option");
        }
        else if(!options.isEmpty() && options.stream().noneMatch(option ->
            Objects.equals(String.valueOf(option.value()), String.valueOf(value))))
            throw new IllegalArgumentException("Unsupported tuner option");
        if(value instanceof Number number &&
            ((spec.minimum() != null && number.doubleValue() < spec.minimum().doubleValue()) ||
                (spec.maximum() != null && number.doubleValue() > spec.maximum().doubleValue())))
            throw new IllegalArgumentException("Tuner setting is out of range");
        if("sample_rate".equals(settingId)) validateSampleRateSpan(configuration, value);
        return value;
    }

    static String scope(TunerConfiguration configuration, String settingId)
    {
        if(RESET_FREQUENCY_EXTENTS.equals(settingId) && hasFrequencyExtents(configuration))
        {
            return "tuner";
        }
        return specs(configuration).stream().filter(candidate -> candidate.id().equals(settingId))
            .map(TunerSettingDefinition::scope).findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown tuner setting"));
    }

    static boolean requiresSetup(TunerConfiguration configuration, String settingId)
    {
        return "sample_rate".equals(settingId);
    }

    private static boolean dependencyEditable(TunerConfiguration configuration, String settingId)
    {
        if("frequency_correction_ppm".equals(settingId))
        {
            return !configuration.getAutoPPMCorrectionEnabled();
        }
        if("frequency_mhz".equals(settingId))
        {
            return !configuration.isCenterFrequencyLocked();
        }
        return true;
    }

    private static List<Dependency> dependencies(String settingId)
    {
        if("frequency_correction_ppm".equals(settingId))
        {
            return List.of(new Dependency("automatic_ppm", false));
        }
        if("frequency_mhz".equals(settingId))
        {
            return List.of(new Dependency("center_frequency_locked", false));
        }
        return List.of();
    }

    private static String unavailableReason(DiscoveredTuner discovered, TunerConfiguration configuration, TunerSettingDefinition<?,?> spec,
                                            List<Option> options, boolean setupAllowed, boolean modeEditable,
                                            boolean dependencyEditable)
    {
        if(!setupAllowed) return "Use Setup";
        if(!dependencyEditable) return "frequency_correction_ppm".equals(spec.id()) ?
            "Turn off Auto PPM" : "Unlock center";
        if("device".equals(spec.scope()) && isRspDuoSlave(discovered)) return "Tuner 1";
        if(spec.dynamicOptions() && options.isEmpty()) return "Tuner unavailable";
        if(!modeEditable)
        {
            return Objects.requireNonNullElse(spec.unavailableReason(configuration), "Unavailable");
        }
        return "Unavailable";
    }

    static boolean isEditableInCurrentMode(TunerConfiguration configuration, String settingId)
    {
        if(RESET_FREQUENCY_EXTENTS.equals(settingId))
        {
            return hasFrequencyExtents(configuration);
        }
        TunerSettingDefinition<?,?> spec = specs(configuration).stream().filter(candidate -> candidate.id().equals(settingId)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown tuner setting"));
        return editableForConfiguration(configuration, spec);
    }

    static boolean isRspDuoSlave(DiscoveredTuner tuner)
    {
        return tuner instanceof DiscoveredRspDuoTuner2 duo &&
            duo.getDeviceInfo().getDeviceSelectionMode().isSlaveMode();
    }

    private static boolean editableForConfiguration(TunerConfiguration configuration,
                                                     TunerSettingDefinition<?,?> spec)
    {
        return spec.unavailableReason(configuration) == null;
    }

    static Object read(TunerConfiguration configuration, String settingId)
    {
        return read(configuration, definition(configuration, settingId));
    }

    static Object readRaw(TunerConfiguration configuration, String settingId)
    {
        return definition(configuration, settingId).read(configuration);
    }

    static void write(TunerConfiguration configuration, String settingId, Object value)
    {
        definition(configuration, settingId).write(configuration, value);
    }

    static void apply(TunerController controller, TunerConfiguration configuration, String settingId) throws Exception
    {
        definition(configuration, settingId).apply(controller, configuration);
    }

    static TunerSettingDefinition<?,?> definition(TunerConfiguration configuration, String settingId)
    {
        return specs(configuration).stream().filter(candidate -> candidate.id().equals(settingId)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown tuner setting"));
    }

    private static String kind(TunerSettingDefinition<?,?> spec)
    {
        if(isFrequencyMHz(spec.id())) return "decimal";
        Class<?> type = spec.valueType();
        return type == Boolean.class ? "boolean" :
            type.isEnum() || spec.dynamicOptions() ? "choice" :
                type == Double.class ? "decimal" : "integer";
    }

    private static Object read(TunerConfiguration configuration, TunerSettingDefinition<?,?> spec)
    {
        Object value = spec.read(configuration);
        if(MINIMUM_FREQUENCY.equals(spec.id()) && value instanceof Number number && number.longValue() <= 0)
            value = hardwareMinimum(configuration);
        else if(MAXIMUM_FREQUENCY.equals(spec.id()) && value instanceof Number number && number.longValue() <= 0)
            value = hardwareMaximum(configuration);
        return publicValue(spec, value);
    }

    private static Object publicValue(TunerSettingDefinition<?,?> spec, Object value)
    {
        if(isFrequencyMHz(spec.id()) && value instanceof Number frequency)
        {
            return frequency.doubleValue() / 1_000_000.0;
        }
        return publicValue(value);
    }

    private static Number publicBound(TunerSettingDefinition<?,?> spec, Number value)
    {
        return isFrequencyMHz(spec.id()) && value != null ?
            value.doubleValue() / 1_000_000.0 : value;
    }

    private static boolean isFrequencyMHz(String settingId)
    {
        return Set.of("frequency_mhz", MINIMUM_FREQUENCY, MAXIMUM_FREQUENCY).contains(settingId);
    }

    private static long parseFrequencyMHz(Object raw)
    {
        try
        {
            double mhz = Double.parseDouble(String.valueOf(raw));
            double hertz = mhz * 1_000_000.0;
            if(Double.isFinite(hertz) && hertz >= 0 && hertz <= Long.MAX_VALUE &&
                Math.abs(hertz - Math.rint(hertz)) < 0.01)
            {
                return Math.round(hertz);
            }
        }
        catch(NumberFormatException ignored)
        {
        }
        throw new IllegalArgumentException("Frequency must have no more than six decimal places in MHz");
    }

    private static Object publicValue(Object value)
    {
        return value instanceof Enum<?> option ? option.name() : value;
    }

    private static Object coerce(Object raw, Class<?> type)
    {
        if(type == boolean.class || type == Boolean.class)
        {
            if(raw instanceof Boolean value)
            {
                return value;
            }
        }
        else if(type == int.class || type == Integer.class)
        {
            try
            {
                return Integer.parseInt(String.valueOf(raw));
            }
            catch(NumberFormatException ignored)
            {
            }
        }
        else if(type == long.class || type == Long.class)
        {
            try
            {
                return Long.parseLong(String.valueOf(raw));
            }
            catch(NumberFormatException ignored)
            {
            }
        }
        else if(type == double.class || type == Double.class)
        {
            try
            {
                double value = Double.parseDouble(String.valueOf(raw));
                if(Double.isFinite(value))
                {
                    return value;
                }
            }
            catch(NumberFormatException ignored)
            {
            }
        }
        else if(type.isEnum() && raw instanceof String name)
        {
            try
            {
                @SuppressWarnings({"unchecked", "rawtypes"})
                Object value = Enum.valueOf((Class<? extends Enum>)type, name);
                return value;
            }
            catch(IllegalArgumentException ignored)
            {
            }
        }

        throw new IllegalArgumentException("Invalid tuner setting value");
    }

    private static List<Option> options(TunerSettingDefinition<?,?> spec, TunerConfiguration configuration, TunerController controller,
                                        DiscoveredTuner discovered)
    {
        Class<?> type = spec.valueType();

        if(type == RspSampleRate.class)
        {
            Set<RspSampleRate> supported;

            if(configuration instanceof RspDuoTuner1Configuration ||
                configuration instanceof RspDuoTuner2Configuration)
            {
                if(controller instanceof RspTunerController<?> rsp &&
                    rsp.getControlRsp() instanceof IControlRspDuo duo)
                {
                    supported = duo.getSupportedSampleRates();
                }
                else if(discovered instanceof DiscoveredRspTuner<?> discoveredRsp)
                {
                    if(discoveredRsp.getDeviceInfo().getDeviceSelectionMode().isSlaveMode())
                    {
                        return List.of();
                    }

                    supported = discoveredRsp.getDeviceInfo().getDeviceSelectionMode().isMasterMode() ?
                        RspSampleRate.getDualTunerSampleRates() : RspSampleRate.getSingleTunerSampleRates();
                }
                else
                {
                    return List.of();
                }
            }
            else
            {
                supported = RspSampleRate.getSingleTunerSampleRates();
            }

            return supported.stream().map(rate -> new Option(rate.name(), rate.toString())).toList();
        }

        if(type.isEnum())
        {
            return Arrays.stream(type.getEnumConstants()).map(option -> (Enum<?>)option)
                .filter(option -> !option.name().equals("UNDEFINED"))
                .filter(option -> !(option instanceof HackRFSampleRate rate) || rate.isValidSampleRate())
                .map(option -> new Option(option.name(), option.toString())).toList();
        }

        if(!spec.dynamicOptions())
        {
            return List.of();
        }

        List<Option> resolved = List.of();
        if(controller instanceof AirspyTunerController airspy)
        {
            resolved = airspy.getSampleRates().stream().map(rate -> new Option(rate.getRate(), rate.getLabel())).toList();
        }
        else if(controller instanceof HydraSdrTunerController hydra)
        {
            resolved = hydra.getSampleRates().stream().map(rate -> new Option(rate.getRate(), rate.getLabel())).toList();
        }
        else if(controller instanceof AirspyHfTunerController hf)
        {
            resolved = hf.getAvailableSampleRates().stream().map(rate -> new Option(rate.getSampleRate(), rate.toString()))
                .toList();
        }

        synchronized(OPTION_CACHE)
        {
            if(!resolved.isEmpty())
            {
                Map<String,List<Option>> cached = new java.util.HashMap<>(OPTION_CACHE.getOrDefault(discovered,
                    Map.of()));
                cached.put(spec.id(), List.copyOf(resolved));
                OPTION_CACHE.put(discovered, Map.copyOf(cached));
                return resolved;
            }

            List<Option> cached = OPTION_CACHE.getOrDefault(discovered, Map.of()).get(spec.id());
            if(cached != null && !cached.isEmpty())
            {
                return cached;
            }
        }

        Object configured = read(configuration, spec);
        return configured instanceof Number number && number.longValue() > 0 ?
            List.of(new Option(number.longValue(), number.longValue() + " Hz")) : List.of();
    }

    static List<TunerSettingDefinition<?,?>> specs(TunerConfiguration configuration)
    {
        return TunerSettingDefinitions.forConfiguration(configuration);
    }

    static boolean isFrequencyExtent(String settingId)
    {
        return MINIMUM_FREQUENCY.equals(settingId) || MAXIMUM_FREQUENCY.equals(settingId) ||
            RESET_FREQUENCY_EXTENTS.equals(settingId);
    }

    private static boolean hasFrequencyExtents(TunerConfiguration configuration)
    {
        return configuration != null && !(configuration instanceof RecordingTunerConfiguration) &&
            hardwareMinimum(configuration) > 0 && hardwareMaximum(configuration) > hardwareMinimum(configuration);
    }

    /**
     * Resolve an entire extent request before changing either controller bound.  A single edited bound may move the
     * other one just as the desktop editor does; two explicitly requested bounds must already fit the sample rate.
     */
    static FrequencyExtents resolveFrequencyExtents(TunerConfiguration configuration, Map<String,Object> requested,
                                                    long sampleRateHz)
    {
        if(!hasFrequencyExtents(configuration) || requested.keySet().stream().noneMatch(
            TunerSettingCatalog::isFrequencyExtent))
        {
            throw new IllegalArgumentException("Tuner frequency limits are unavailable");
        }

        long hardwareMinimum = hardwareMinimum(configuration);
        long hardwareMaximum = hardwareMaximum(configuration);
        boolean reset = Boolean.TRUE.equals(requested.get(RESET_FREQUENCY_EXTENTS));
        boolean editMinimum = requested.containsKey(MINIMUM_FREQUENCY);
        boolean editMaximum = requested.containsKey(MAXIMUM_FREQUENCY);
        long minimum = reset ? hardwareMinimum : editMinimum ?
            ((Number)requested.get(MINIMUM_FREQUENCY)).longValue() :
            configuration.getMinimumFrequency() > 0 ? configuration.getMinimumFrequency() : hardwareMinimum;
        long maximum = reset ? hardwareMaximum : editMaximum ?
            ((Number)requested.get(MAXIMUM_FREQUENCY)).longValue() :
            configuration.getMaximumFrequency() > 0 ? configuration.getMaximumFrequency() : hardwareMaximum;
        long minimumGap = Math.max(1, sampleRateHz);

        if(minimum < hardwareMinimum || minimum > hardwareMaximum ||
            maximum < hardwareMinimum || maximum > hardwareMaximum)
        {
            throw new IllegalArgumentException("Frequency limit is outside the tuner's supported range");
        }
        if(maximum - minimum < minimumGap)
        {
            if(!reset && editMinimum && !editMaximum && minimum <= hardwareMaximum - minimumGap)
            {
                maximum = minimum + minimumGap;
            }
            else if(!reset && editMaximum && !editMinimum && maximum >= hardwareMinimum + minimumGap)
            {
                minimum = maximum - minimumGap;
            }
            else
            {
                throw new IllegalArgumentException("Frequency limits must span at least the sample rate");
            }
        }

        return new FrequencyExtents(minimum, maximum);
    }

    static void validateSampleRateSpan(TunerConfiguration configuration, Object rate)
    {
        if(!hasFrequencyExtents(configuration))
        {
            return;
        }

        long hertz = rate instanceof Number number ? number.longValue() :
            rate instanceof HackRFSampleRate hackrf ? hackrf.getRate() :
            rate instanceof SampleRate rtl ? rtl.getRate() :
            rate instanceof RspSampleRate rsp ? rsp.getEffectiveSampleRate() : 0;
        long minimum = configuration.getMinimumFrequency() > 0 ? configuration.getMinimumFrequency() :
            hardwareMinimum(configuration);
        long maximum = configuration.getMaximumFrequency() > 0 ? configuration.getMaximumFrequency() :
            hardwareMaximum(configuration);

        if(hertz <= 0 || maximum - minimum < hertz)
        {
            throw new IllegalArgumentException("Sample rate exceeds configured frequency limits; widen them first");
        }
    }

    record FrequencyExtents(long minimumHz, long maximumHz)
    {
    }

    static long hardwareMinimum(TunerConfiguration configuration)
    {
        if(configuration instanceof AirspyTunerConfiguration) return AirspyTunerController.MINIMUM_TUNABLE_FREQUENCY_HZ;
        if(configuration instanceof HydraSdrTunerConfiguration) return HydraSdrTunerController.MINIMUM_TUNABLE_FREQUENCY_HZ;
        if(configuration instanceof AirspyHfTunerConfiguration) return AirspyHfTunerController.MINIMUM_TUNABLE_FREQUENCY_HZ;
        if(configuration instanceof HackRFTunerConfiguration) return HackRFTunerController.MINIMUM_TUNABLE_FREQUENCY_HZ;
        if(configuration instanceof E4KTunerConfiguration) return E4KEmbeddedTuner.MINIMUM_TUNABLE_FREQUENCY_HZ;
        if(configuration instanceof FC0013TunerConfiguration) return FC0013EmbeddedTuner.MINIMUM_TUNABLE_FREQUENCY_HZ;
        if(configuration instanceof R8xTunerConfiguration) return R8xEmbeddedTuner.MINIMUM_TUNABLE_FREQUENCY_HZ;
        if(configuration instanceof RspTunerConfiguration) return 100_000L;
        return 0;
    }

    static long hardwareMaximum(TunerConfiguration configuration)
    {
        if(configuration instanceof AirspyTunerConfiguration) return AirspyTunerController.MAXIMUM_TUNABLE_FREQUENCY_HZ;
        if(configuration instanceof HydraSdrTunerConfiguration) return HydraSdrTunerController.MAXIMUM_TUNABLE_FREQUENCY_HZ;
        if(configuration instanceof AirspyHfTunerConfiguration) return AirspyHfTunerController.MAXIMUM_TUNABLE_FREQUENCY_HZ;
        if(configuration instanceof HackRFTunerConfiguration) return HackRFTunerController.MAXIMUM_TUNABLE_FREQUENCY_HZ;
        if(configuration instanceof E4KTunerConfiguration) return E4KEmbeddedTuner.MAXIMUM_TUNABLE_FREQUENCY_HZ;
        if(configuration instanceof FC0013TunerConfiguration) return FC0013EmbeddedTuner.MAXIMUM_TUNABLE_FREQUENCY_HZ;
        if(configuration instanceof R8xTunerConfiguration) return R8xEmbeddedTuner.MAXIMUM_TUNABLE_FREQUENCY_HZ;
        if(configuration instanceof RspTunerConfiguration) return 2_000_000_000L;
        return 0;
    }


}
