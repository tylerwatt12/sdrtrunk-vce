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
import io.github.dsheirer.source.tuner.rtl.RTL2832TunerConfiguration;
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
import io.github.dsheirer.source.tuner.sdrplay.rsp1a.Rsp1aTunerConfiguration;
import io.github.dsheirer.source.tuner.sdrplay.rsp1b.Rsp1bTunerConfiguration;
import io.github.dsheirer.source.tuner.sdrplay.rsp2.Rsp2TunerConfiguration;
import io.github.dsheirer.source.tuner.sdrplay.rspDuo.RspDuoTuner1Configuration;
import io.github.dsheirer.source.tuner.sdrplay.rspDuo.RspDuoTuner2Configuration;
import io.github.dsheirer.source.tuner.sdrplay.rspDuo.IControlRspDuo;
import io.github.dsheirer.source.tuner.sdrplay.rspDuo.DiscoveredRspDuoTuner2;
import io.github.dsheirer.source.tuner.sdrplay.rspDx.RspDxTunerConfiguration;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
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
 * families, controller classes, or hardware validation rules.  No method in this class writes to a tuner.
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
        TunerConfiguration configuration = discovered.getTunerConfiguration();

        if(configuration == null || configuration instanceof RecordingTunerConfiguration)
        {
            return List.of();
        }

        TunerController controller = discovered.hasTuner() ? discovered.getTuner().getTunerController() : null;
        List<SettingDescriptor> descriptors = new ArrayList<>();

        for(Spec spec: specs(configuration))
        {
            try
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
                String availability = !editableForConfiguration(configuration, spec) &&
                    configuration instanceof AirspyHfTunerConfiguration && "sample_rate".equals(spec.id()) ?
                    "read_only" : requiresSetup(configuration, spec.id()) ? "setup" : "live";
                descriptors.add(new SettingDescriptor(spec.id(), spec.label(), group(spec.id()),
                    kind(spec, configuration), read(configuration, spec), options, publicBound(spec, spec.minimum()),
                    "lna".equals(spec.id()) && controller instanceof RspTunerController<?> rsp ?
                        rsp.getControlRsp().getMaximumLNASetting() : publicBound(spec, spec.maximum()),
                    spec.step(), spec.unit(), spec.scope(), editable, availability,
                    editable ? null : unavailableReason(discovered, configuration, spec, options,
                        setupAllowed, modeEditable, dependencyEditable), dependencies(spec.id())));
            }
            catch(ReflectiveOperationException e)
            {
                throw new IllegalStateException("Invalid tuner setting provider: " + spec.id(), e);
            }
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
        TunerConfiguration configuration = discovered.getTunerConfiguration();
        if(RESET_FREQUENCY_EXTENTS.equals(settingId))
        {
            if(!hasFrequencyExtents(configuration) || !Boolean.TRUE.equals(rawValue))
            {
                throw new IllegalArgumentException("Reset frequency limits requires true");
            }
            return true;
        }
        TunerController controller = discovered.hasTuner() ? discovered.getTuner().getTunerController() : null;
        Spec spec = specs(configuration).stream().filter(candidate -> candidate.id().equals(settingId)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown tuner setting"));

        try
        {
            if(!editableForConfiguration(configuration, spec))
            {
                throw new IllegalArgumentException("Setting is not editable in the current tuner mode");
            }

            if(!dependencyEditable(configuration, settingId))
            {
                throw new IllegalArgumentException("frequency_correction_ppm".equals(settingId) ?
                    "Turn off Auto PPM" : "Unlock center");
            }

            Class<?> type = getter(configuration, spec).getReturnType();
            List<Option> options = options(spec, configuration, controller, discovered);

            if(spec.dynamicOptions() && options.isEmpty())
            {
                throw new IllegalArgumentException("This setting requires the tuner to be available");
            }

            Object value = isFrequencyMHz(spec.id()) ? parseFrequencyMHz(rawValue) :
                coerce(rawValue, type);

            if("lna".equals(spec.id()) && configuration instanceof RspTunerConfiguration)
            {
                if(!(controller instanceof RspTunerController<?> rsp) ||
                    ((Number)value).intValue() < 0 ||
                    ((Number)value).intValue() > rsp.getControlRsp().getMaximumLNASetting())
                {
                    throw new IllegalArgumentException("LNA setting is unavailable or out of range");
                }
            }

            if(type.isEnum())
            {
                if(options.stream().noneMatch(option -> Objects.equals(option.value(), ((Enum<?>)value).name())))
                {
                    throw new IllegalArgumentException("Unsupported tuner option");
                }
            }
            else if(!options.isEmpty() && options.stream().noneMatch(option ->
                Objects.equals(String.valueOf(option.value()), String.valueOf(value))))
            {
                throw new IllegalArgumentException("Unsupported tuner option");
            }

            if(value instanceof Number number)
            {
                double numeric = number.doubleValue();

                if((spec.minimum() != null && numeric < spec.minimum().doubleValue()) ||
                    (spec.maximum() != null && numeric > spec.maximum().doubleValue()))
                {
                    throw new IllegalArgumentException("Tuner setting is out of range");
                }
            }

            if("sample_rate".equals(settingId))
            {
                validateSampleRateSpan(configuration, value);
            }

            return value;
        }
        catch(ReflectiveOperationException e)
        {
            throw new IllegalStateException("Invalid tuner setting provider: " + settingId, e);
        }
    }

    static String scope(TunerConfiguration configuration, String settingId)
    {
        if(RESET_FREQUENCY_EXTENTS.equals(settingId) && hasFrequencyExtents(configuration))
        {
            return "tuner";
        }
        return specs(configuration).stream().filter(candidate -> candidate.id().equals(settingId))
            .map(Spec::scope).findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown tuner setting"));
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

    private static String unavailableReason(DiscoveredTuner discovered, TunerConfiguration configuration, Spec spec,
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
            if(configuration instanceof AirspyHfTunerConfiguration && "sample_rate".equals(spec.id())) return "Fixed";
            String gainReason = gainUnavailableReason(configuration, spec.id());
            if(gainReason != null) return gainReason;
            if(Set.of("if_gain", "mixer_gain", "lna_gain", "vga_gain").contains(spec.id())) return "Use manual gain";
            return "Unavailable";
        }
        return "Unavailable";
    }

    private static String gainUnavailableReason(TunerConfiguration configuration, String settingId)
    {
        if(configuration instanceof AirspyTunerConfiguration airspy)
        {
            return presetGainUnavailableReason(settingId, airspy.getGain().name(),
                airspy.isMixerAGC(), airspy.isLNAAGC());
        }
        if(configuration instanceof HydraSdrTunerConfiguration hydra)
        {
            return presetGainUnavailableReason(settingId, hydra.getGain().name(),
                hydra.isMixerAGC(), hydra.isLNAAGC());
        }
        if(configuration instanceof FC0013TunerConfiguration fc0013 && "lna_gain".equals(settingId) &&
            fc0013.getAGC())
        {
            return "Turn off AGC";
        }
        return null;
    }

    private static String presetGainUnavailableReason(String settingId, String preset, boolean mixerAgc,
                                                      boolean lnaAgc)
    {
        if(Set.of("if_gain", "mixer_gain", "lna_gain", "mixer_agc", "lna_agc").contains(settingId) &&
            !"CUSTOM".equals(preset))
        {
            return "Use Custom gain";
        }
        if("mixer_gain".equals(settingId) && mixerAgc) return "Turn off Mixer AGC";
        if("lna_gain".equals(settingId) && lnaAgc) return "Turn off LNA AGC";
        return null;
    }

    static boolean isEditableInCurrentMode(TunerConfiguration configuration, String settingId)
    {
        if(RESET_FREQUENCY_EXTENTS.equals(settingId))
        {
            return hasFrequencyExtents(configuration);
        }
        Spec spec = specs(configuration).stream().filter(candidate -> candidate.id().equals(settingId)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown tuner setting"));
        return editableForConfiguration(configuration, spec);
    }

    static boolean isRspDuoSlave(DiscoveredTuner tuner)
    {
        return tuner instanceof DiscoveredRspDuoTuner2 duo &&
            duo.getDeviceInfo().getDeviceSelectionMode().isSlaveMode();
    }

    private static boolean editableForConfiguration(TunerConfiguration configuration, Spec spec)
    {
        if(configuration instanceof AirspyHfTunerConfiguration && "sample_rate".equals(spec.id()))
        {
            // The desktop editor deliberately fixes the HF+ rate because alternatives were unreliable.
            return false;
        }
        if(configuration instanceof AirspyTunerConfiguration airspy)
        {
            return editableForPreset(spec.id(), airspy.getGain().name(), airspy.isMixerAGC(), airspy.isLNAAGC());
        }
        if(configuration instanceof HydraSdrTunerConfiguration hydra)
        {
            return editableForPreset(spec.id(), hydra.getGain().name(), hydra.isMixerAGC(), hydra.isLNAAGC());
        }
        if(configuration instanceof E4KTunerConfiguration e4k &&
            Set.of("if_gain", "mixer_gain", "lna_gain").contains(spec.id()))
        {
            return "MANUAL".equals(e4k.getMasterGain().name());
        }
        if(configuration instanceof R8xTunerConfiguration r8x &&
            Set.of("mixer_gain", "lna_gain", "vga_gain").contains(spec.id()))
        {
            return "MANUAL".equals(r8x.getMasterGain().name());
        }
        if(configuration instanceof FC0013TunerConfiguration fc0013 && "lna_gain".equals(spec.id()))
        {
            return !fc0013.getAGC();
        }
        return true;
    }

    private static boolean editableForPreset(String id, String preset, boolean mixerAgc, boolean lnaAgc)
    {
        boolean manualField = Set.of("if_gain", "mixer_gain", "lna_gain", "mixer_agc", "lna_agc").contains(id);

        if(manualField && !"CUSTOM".equals(preset))
        {
            return false;
        }

        return !("mixer_gain".equals(id) && mixerAgc) && !("lna_gain".equals(id) && lnaAgc);
    }

    static Object read(TunerConfiguration configuration, String settingId)
    {
        Spec spec = specs(configuration).stream().filter(candidate -> candidate.id().equals(settingId)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown tuner setting"));

        try
        {
            return read(configuration, spec);
        }
        catch(ReflectiveOperationException e)
        {
            throw new IllegalStateException("Invalid tuner setting provider: " + settingId, e);
        }
    }

    static Object readRaw(TunerConfiguration configuration, String settingId)
    {
        Spec spec = specs(configuration).stream().filter(candidate -> candidate.id().equals(settingId)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown tuner setting"));

        try
        {
            return getter(configuration, spec).invoke(configuration);
        }
        catch(ReflectiveOperationException e)
        {
            throw new IllegalStateException("Invalid tuner setting provider: " + settingId, e);
        }
    }

    static void write(TunerConfiguration configuration, String settingId, Object value)
    {
        Spec spec = specs(configuration).stream().filter(candidate -> candidate.id().equals(settingId)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown tuner setting"));

        try
        {
            Method getter = getter(configuration, spec);
            Method setter = configuration.getClass().getMethod(spec.setter(), getter.getReturnType());
            setter.invoke(configuration, value);
        }
        catch(InvocationTargetException e)
        {
            throw new IllegalArgumentException("Tuner rejected setting", e.getCause());
        }
        catch(ReflectiveOperationException e)
        {
            throw new IllegalStateException("Invalid tuner setting provider: " + settingId, e);
        }
    }

    private static String kind(Spec spec, TunerConfiguration configuration) throws ReflectiveOperationException
    {
        if(isFrequencyMHz(spec.id()))
        {
            return "decimal";
        }
        Class<?> type = getter(configuration, spec).getReturnType();
        return type == boolean.class || type == Boolean.class ? "boolean" :
            type.isEnum() || spec.dynamicOptions() ? "choice" :
                type == double.class || type == Double.class ? "decimal" : "integer";
    }

    private static Object read(TunerConfiguration configuration, Spec spec) throws ReflectiveOperationException
    {
        Object value = getter(configuration, spec).invoke(configuration);
        if(MINIMUM_FREQUENCY.equals(spec.id()) && value instanceof Number number && number.longValue() <= 0)
        {
            value = hardwareMinimum(configuration);
        }
        else if(MAXIMUM_FREQUENCY.equals(spec.id()) && value instanceof Number number && number.longValue() <= 0)
        {
            value = hardwareMaximum(configuration);
        }
        return publicValue(spec, value);
    }

    private static Object publicValue(Spec spec, Object value)
    {
        if(isFrequencyMHz(spec.id()) && value instanceof Number frequency)
        {
            return frequency.doubleValue() / 1_000_000.0;
        }
        return publicValue(value);
    }

    private static Number publicBound(Spec spec, Number value)
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

    private static Method getter(TunerConfiguration configuration, Spec spec) throws NoSuchMethodException
    {
        return configuration.getClass().getMethod(spec.getter());
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

    private static List<Option> options(Spec spec, TunerConfiguration configuration, TunerController controller,
                                        DiscoveredTuner discovered)
        throws ReflectiveOperationException
    {
        Class<?> type = getter(configuration, spec).getReturnType();

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

    private static List<Spec> specs(TunerConfiguration configuration)
    {
        List<Spec> specs = new ArrayList<>();

        if(configuration instanceof RecordingTunerConfiguration)
        {
            return specs;
        }

        specs.add(spec("frequency_correction_ppm", "Frequency correction", "getFrequencyCorrection",
            "setFrequencyCorrection", -200.0, 200.0, 1.0, "ppm"));
        long hardwareMinimum = hardwareMinimum(configuration);
        long hardwareMaximum = hardwareMaximum(configuration);
        if(hardwareMinimum > 0 && hardwareMaximum > hardwareMinimum)
        {
            specs.add(spec(MINIMUM_FREQUENCY, "Minimum frequency", "getMinimumFrequency",
                "setMinimumFrequency", hardwareMinimum, hardwareMaximum, 0.000001, "MHz"));
            specs.add(spec(MAXIMUM_FREQUENCY, "Maximum frequency", "getMaximumFrequency",
                "setMaximumFrequency", hardwareMinimum, hardwareMaximum, 0.000001, "MHz"));
            long minimum = Math.max(hardwareMinimum, configuration.getMinimumFrequency());
            long maximum = configuration.getMaximumFrequency() > 0 ?
                Math.min(hardwareMaximum, configuration.getMaximumFrequency()) : hardwareMaximum;
            if(maximum > minimum)
            {
                specs.add(spec("frequency_mhz", "Center frequency", "getFrequency", "setFrequency",
                    minimum, maximum, 0.000001, "MHz"));
            }
        }
        specs.add(spec("automatic_ppm", "Automatic PPM correction", "getAutoPPMCorrectionEnabled",
            "setAutoPPMCorrectionEnabled"));
        specs.add(spec("center_frequency_locked", "Lock center", "isCenterFrequencyLocked",
            "setCenterFrequencyLocked"));

        if(configuration instanceof AirspyTunerConfiguration || configuration instanceof HydraSdrTunerConfiguration)
        {
            specs.add(dynamicRate());
            specs.add(spec("gain", "Gain preset", "getGain", "setGain"));
            specs.add(spec("if_gain", "IF gain", "getIFGain", "setIFGain", 0, 15, 1, null));
            specs.add(spec("mixer_gain", "Mixer gain", "getMixerGain", "setMixerGain", 0, 15, 1, null));
            specs.add(spec("lna_gain", "LNA gain", "getLNAGain", "setLNAGain", 0, 14, 1, null));
            specs.add(spec("mixer_agc", "Mixer AGC", "isMixerAGC", "setMixerAGC"));
            specs.add(spec("lna_agc", "LNA AGC", "isLNAAGC", "setLNAAGC"));
            if(configuration instanceof HydraSdrTunerConfiguration)
            {
                specs.add(spec("bias_t", "Bias T", "isBiasT", "setBiasT"));
            }
        }
        else if(configuration instanceof AirspyHfTunerConfiguration)
        {
            specs.add(dynamicRate());
            specs.add(spec("agc", "AGC", "isAgc", "setAgc"));
            specs.add(spec("lna", "LNA", "isLna", "setLna"));
            specs.add(spec("attenuation", "Attenuation", "getAttenuationValue", "setAttenuationValue",
                0, 8, 1, "step"));
        }
        else if(configuration instanceof RTL2832TunerConfiguration)
        {
            specs.add(spec("sample_rate", "Sample rate", "getSampleRate", "setSampleRate"));
            specs.add(spec("bias_t", "Bias T", "isBiasT", "setBiasT"));
            if(configuration instanceof E4KTunerConfiguration)
            {
                specs.add(spec("master_gain", "Master gain", "getMasterGain", "setMasterGain"));
                specs.add(spec("mixer_gain", "Mixer gain", "getMixerGain", "setMixerGain"));
                specs.add(spec("lna_gain", "LNA gain", "getLNAGain", "setLNAGain"));
                specs.add(spec("if_gain", "IF gain", "getIFGain", "setIFGain"));
            }
            else if(configuration instanceof FC0013TunerConfiguration)
            {
                specs.add(spec("lna_gain", "LNA gain", "getLnaGain", "setLnaGain"));
                specs.add(spec("agc", "AGC", "getAGC", "setAGC"));
            }
            else if(configuration instanceof R8xTunerConfiguration)
            {
                specs.add(spec("master_gain", "Master gain", "getMasterGain", "setMasterGain"));
                specs.add(spec("mixer_gain", "Mixer gain", "getMixerGain", "setMixerGain"));
                specs.add(spec("lna_gain", "LNA gain", "getLNAGain", "setLNAGain"));
                specs.add(spec("vga_gain", "VGA gain", "getVGAGain", "setVGAGain"));
            }
        }
        else if(configuration instanceof HackRFTunerConfiguration)
        {
            specs.add(spec("sample_rate", "Sample rate", "getSampleRate", "setSampleRate"));
            specs.add(spec("lna_gain", "LNA gain", "getLNAGain", "setLNAGain"));
            specs.add(spec("vga_gain", "VGA gain", "getVGAGain", "setVGAGain"));
            specs.add(spec("amplifier", "RF amplifier", "getAmplifierEnabled", "setAmplifierEnabled"));
        }
        else if(configuration instanceof RspTunerConfiguration)
        {
            if(configuration instanceof RspDuoTuner1Configuration ||
                configuration instanceof RspDuoTuner2Configuration)
            {
                specs.add(new Spec("sample_rate", "Sample rate", "getSampleRate", "setSampleRate",
                    null, null, null, "Hz", "device", true));
            }
            else
            {
                specs.add(spec("sample_rate", "Sample rate", "getSampleRate", "setSampleRate"));
            }
            specs.add(spec("baseband_gain_reduction", "Baseband gain reduction", "getBasebandGainReduction",
                "setBasebandGainReduction", 20, 59, 1, "dB"));
            specs.add(spec("lna", "LNA state", "getLNA", "setLNA", 0, null, 1, null));
            specs.add(spec("agc_mode", "AGC mode", "getAgcMode", "setAgcMode"));
            if(configuration instanceof Rsp1aTunerConfiguration || configuration instanceof Rsp1bTunerConfiguration ||
                configuration instanceof Rsp2TunerConfiguration || configuration instanceof RspDuoTuner1Configuration ||
                configuration instanceof RspDuoTuner2Configuration || configuration instanceof RspDxTunerConfiguration)
            {
                specs.add(spec("rf_notch", "RF notch", "isRfNotch", "setRfNotch"));
            }
            if(configuration instanceof Rsp1aTunerConfiguration || configuration instanceof Rsp1bTunerConfiguration ||
                configuration instanceof RspDuoTuner1Configuration || configuration instanceof RspDuoTuner2Configuration ||
                configuration instanceof RspDxTunerConfiguration)
            {
                specs.add(spec("dab_notch", "DAB notch", "isRfDabNotch", "setRfDabNotch"));
            }
            if(configuration instanceof Rsp1aTunerConfiguration || configuration instanceof Rsp1bTunerConfiguration ||
                configuration instanceof Rsp2TunerConfiguration || configuration instanceof RspDuoTuner2Configuration ||
                configuration instanceof RspDxTunerConfiguration)
            {
                specs.add(spec("bias_t", "Bias T", "isBiasT", "setBiasT"));
            }
            if(configuration instanceof Rsp2TunerConfiguration)
            {
                specs.add(spec("antenna", "Antenna", "getAntennaSelection", "setAntennaSelection"));
                specs.add(spec("external_reference", "External reference", "isExternalReferenceOutput",
                    "setExternalReferenceOutput", "device"));
            }
            if(configuration instanceof RspDuoTuner1Configuration)
            {
                specs.add(spec("am_port", "AM port", "getAmPort", "setAmPort"));
                specs.add(spec("am_notch", "AM notch", "isAmNotch", "setAmNotch"));
            }
            if(configuration instanceof RspDuoTuner1Configuration || configuration instanceof RspDuoTuner2Configuration)
            {
                specs.add(spec("external_reference", "External reference", "isExternalReferenceOutput",
                    "setExternalReferenceOutput", "device"));
            }
            if(configuration instanceof RspDxTunerConfiguration)
            {
                specs.add(spec("antenna", "Antenna", "getAntenna", "setAntenna"));
                specs.add(spec("hdr_mode", "HDR mode", "isHdrMode", "setHdrMode"));
                specs.add(spec("hdr_bandwidth", "HDR bandwidth", "getHdrModeBandwidth", "setHdrModeBandwidth"));
            }
        }

        return specs;
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

    private static long hardwareMinimum(TunerConfiguration configuration)
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

    private static long hardwareMaximum(TunerConfiguration configuration)
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

    private static Spec dynamicRate()
    {
        return new Spec("sample_rate", "Sample rate", "getSampleRate", "setSampleRate", null, null,
            null, "Hz", "tuner", true);
    }

    private static Spec spec(String id, String label, String getter, String setter)
    {
        return new Spec(id, label, getter, setter, null, null, null, null, "tuner", false);
    }

    private static Spec spec(String id, String label, String getter, String setter, String scope)
    {
        return new Spec(id, label, getter, setter, null, null, null, null, scope, false);
    }

    private static Spec spec(String id, String label, String getter, String setter, Number minimum, Number maximum,
                             Number step, String unit)
    {
        return new Spec(id, label, getter, setter, minimum, maximum, step, unit, "tuner", false);
    }

    private record Spec(String id, String label, String getter, String setter, Number minimum, Number maximum,
                        Number step, String unit, String scope, boolean dynamicOptions)
    {
    }
}
