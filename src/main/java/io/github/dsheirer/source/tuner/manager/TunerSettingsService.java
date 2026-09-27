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
import io.github.dsheirer.source.tuner.rtl.RTL2832TunerConfiguration;
import io.github.dsheirer.source.tuner.rtl.RTL2832TunerController;
import io.github.dsheirer.source.tuner.rtl.e4k.E4KEmbeddedTuner;
import io.github.dsheirer.source.tuner.rtl.e4k.E4KTunerConfiguration;
import io.github.dsheirer.source.tuner.rtl.fc0013.FC0013EmbeddedTuner;
import io.github.dsheirer.source.tuner.rtl.fc0013.FC0013TunerConfiguration;
import io.github.dsheirer.source.tuner.rtl.r8x.R8xEmbeddedTuner;
import io.github.dsheirer.source.tuner.rtl.r8x.R8xTunerConfiguration;
import io.github.dsheirer.source.tuner.sdrplay.RspTunerConfiguration;
import io.github.dsheirer.source.tuner.sdrplay.RspTunerController;
import io.github.dsheirer.source.tuner.sdrplay.rspDuo.DiscoveredRspDuoTuner1;
import io.github.dsheirer.source.tuner.sdrplay.rspDuo.DiscoveredRspDuoTuner2;
import java.util.HashMap;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Admin-initiated tuner settings and enable/disable actions.  Requests update a bounded latest-value map and return
 * immediately.  A single dedicated worker serializes hardware and persistence work.  Disruptive settings wait until
 * the tuner is idle; designated gain controls can be applied live.  Both use the lifecycle reservation shared with
 * channel allocation.
 */
public final class TunerSettingsService implements AutoCloseable
{
    private static final Logger mLog = LoggerFactory.getLogger(TunerSettingsService.class);
    private final Runnable mPersist;
    private final Predicate<DiscoveredTuner> mContains;
    private final Function<String,DiscoveredTuner> mFind;
    private final ScheduledThreadPoolExecutor mExecutor;
    private final Map<DiscoveredTuner,Map<String,Object>> mPending = new ConcurrentHashMap<>();
    private final Map<DiscoveredTuner,Boolean> mPendingEnabled = new ConcurrentHashMap<>();
    private final Map<DiscoveredTuner,String> mErrors = new ConcurrentHashMap<>();
    private final AtomicBoolean mWorkerScheduled = new AtomicBoolean();
    private volatile boolean mClosed;

    public TunerSettingsService(TunerManager manager)
    {
        this(manager.getTunerConfigurationManager()::saveConfigurations,
            manager.getDiscoveredTunerRegistry()::contains, manager.getDiscoveredTunerRegistry()::find);
    }

    TunerSettingsService(Runnable persist, Predicate<DiscoveredTuner> contains,
                         Function<String,DiscoveredTuner> find)
    {
        mPersist = Objects.requireNonNull(persist);
        mContains = Objects.requireNonNull(contains);
        mFind = Objects.requireNonNull(find);
        mExecutor = new ScheduledThreadPoolExecutor(1, task ->
        {
            Thread thread = new Thread(task, "tuner-settings-worker");
            thread.setDaemon(true);
            return thread;
        });
        mExecutor.setRemoveOnCancelPolicy(true);
    }

    public List<TunerSettingCatalog.SettingDescriptor> describe(DiscoveredTuner tuner)
    {
        Map<String,Object> pending = mPending.get(tuner);
        List<TunerSettingCatalog.SettingDescriptor> descriptors = TunerSettingCatalog.describe(tuner,
            pending == null ? Map.of() : pending);

        if(!TunerSettingCatalog.isRspDuoSlave(tuner))
        {
            return descriptors;
        }

        DiscoveredTuner master = rspDuoSibling(tuner);
        if(master == null || master.getTunerConfiguration() == null)
        {
            return descriptors;
        }

        Map<String,Object> masterPending = mPending.get(master);
        Map<String,TunerSettingCatalog.SettingDescriptor> shared = new HashMap<>();
        for(TunerSettingCatalog.SettingDescriptor descriptor: TunerSettingCatalog.describe(master,
            masterPending == null ? Map.of() : masterPending))
        {
            if("device".equals(descriptor.scope()))
            {
                shared.put(descriptor.id(), descriptor);
            }
        }

        return descriptors.stream().map(descriptor ->
        {
            TunerSettingCatalog.SettingDescriptor masterDescriptor = shared.get(descriptor.id());
            if(masterDescriptor == null)
            {
                return descriptor;
            }
            return new TunerSettingCatalog.SettingDescriptor(descriptor.id(), descriptor.label(), descriptor.kind(),
                masterDescriptor.value(), masterDescriptor.pendingValue(), descriptor.options(), descriptor.minimum(),
                descriptor.maximum(), descriptor.step(), descriptor.unit(), descriptor.scope(),
                descriptor.requiresIdle(), false);
        }).toList();
    }

    /**
     * Validates a setting using its hardware family provider and keeps only the latest requested value per field.
     * The worker persists a value only after the applicable idle requirement and live controller have accepted it.
     */
    public MutationResult set(DiscoveredTuner tuner, String settingId, Object submittedValue)
    {
        ensureOpen();
        ensurePresent(tuner);
        TunerConfiguration configuration = configuration(tuner);
        if("device".equals(TunerSettingCatalog.scope(configuration, settingId)) &&
            TunerSettingCatalog.isRspDuoSlave(tuner))
        {
            throw new IllegalArgumentException("Shared RSPduo settings are edited from tuner 1");
        }
        Object value = TunerSettingCatalog.validate(tuner, settingId, submittedValue);

        mPending.compute(tuner, (ignored, existing) ->
        {
            Map<String,Object> updated = new HashMap<>(existing == null ? Map.of() : existing);
            updated.put(settingId, value);
            return Map.copyOf(updated);
        });
        mErrors.remove(tuner);
        schedule(false);
        return new MutationResult("queued", null);
    }

    public MutationResult cancel(DiscoveredTuner tuner, String settingId)
    {
        ensureOpen();
        ensurePresent(tuner);
        mPending.computeIfPresent(tuner, (ignored, existing) ->
        {
            Map<String,Object> updated = new HashMap<>(existing);
            updated.remove(settingId);
            return updated.isEmpty() ? null : Map.copyOf(updated);
        });

        return new MutationResult("cancelled", null);
    }

    /**
     * Enable/disable runs on the hardware worker.  Disabling is intentionally disruptive and does not attempt to
     * restart channels; callers must confirm the action with the administrator before invoking it.
     */
    public MutationResult requestEnabled(DiscoveredTuner tuner, boolean enabled)
    {
        ensureOpen();
        ensurePresent(tuner);
        mPendingEnabled.put(Objects.requireNonNull(tuner), enabled);
        mErrors.remove(tuner);
        schedule(false);
        return new MutationResult("queued", null);
    }

    public String error(DiscoveredTuner tuner)
    {
        return mErrors.get(tuner);
    }

    public boolean hasPending(DiscoveredTuner tuner)
    {
        Map<String,Object> settings = mPending.get(tuner);
        return (settings != null && !settings.isEmpty()) || mPendingEnabled.containsKey(tuner);
    }

    private TunerConfiguration configuration(DiscoveredTuner tuner)
    {
        Objects.requireNonNull(tuner);
        TunerConfiguration configuration = tuner.getTunerConfiguration();

        if(configuration == null)
        {
            throw new IllegalArgumentException("Tuner configuration is unavailable");
        }

        return configuration;
    }

    private void schedule(boolean delayed)
    {
        if(!mClosed && mWorkerScheduled.compareAndSet(false, true))
        {
            try
            {
                if(delayed)
                {
                    mExecutor.schedule(this::drain, 1, TimeUnit.SECONDS);
                }
                else
                {
                    mExecutor.execute(this::drain);
                }
            }
            catch(RejectedExecutionException e)
            {
                mWorkerScheduled.set(false);
            }
        }
    }

    private void drain()
    {
        try
        {
            for(DiscoveredTuner tuner: List.copyOf(mPendingEnabled.keySet()))
            {
                process(tuner);
            }

            for(DiscoveredTuner tuner: List.copyOf(mPending.keySet()))
            {
                process(tuner);
            }
        }
        finally
        {
            mWorkerScheduled.set(false);

            if(!mPending.isEmpty() || !mPendingEnabled.isEmpty())
            {
                schedule(true);
            }
        }
    }

    private void process(DiscoveredTuner tuner)
    {
        if(!mContains.test(tuner) || tuner.getTunerStatus() == TunerStatus.REMOVED)
        {
            mPending.remove(tuner);
            mPendingEnabled.remove(tuner);
            mErrors.remove(tuner);
            return;
        }

        // Allocation callers use this same non-blocking reservation.  A contended tuner is retried later.
        if(!tuner.tryAcquireForAllocation())
        {
            return;
        }

        try
        {
            if(!mContains.test(tuner) || tuner.getTunerStatus() == TunerStatus.REMOVED)
            {
                mPending.remove(tuner);
                mPendingEnabled.remove(tuner);
                mErrors.remove(tuner);
                return;
            }

            Boolean enabled = mPendingEnabled.get(tuner);

            if(Boolean.FALSE.equals(enabled))
            {
                tuner.setEnabled(false);
                mPendingEnabled.remove(tuner, false);
            }

            Map<String,Object> settings = mPending.get(tuner);

            if(settings != null && !settings.isEmpty())
            {
                for(Map.Entry<String,Object> entry: new HashMap<>(settings).entrySet())
                {
                    if(TunerSettingCatalog.requiresIdle(configuration(tuner), entry.getKey()) && !isIdle(tuner))
                    {
                        continue;
                    }

                    if(apply(tuner, entry.getKey(), entry.getValue()))
                    {
                        mPending.computeIfPresent(tuner, (ignored, current) ->
                        {
                            if(!Objects.equals(current.get(entry.getKey()), entry.getValue()))
                            {
                                return current;
                            }

                            Map<String,Object> updated = new HashMap<>(current);
                            updated.remove(entry.getKey());
                            return updated.isEmpty() ? null : Map.copyOf(updated);
                        });
                    }
                }
            }

            if(Boolean.TRUE.equals(mPendingEnabled.get(tuner)) && !hasPendingSettings(tuner))
            {
                if(tuner.getTunerStatus() == TunerStatus.ERROR)
                {
                    tuner.restart();
                }
                else
                {
                    tuner.setEnabled(true);
                }

                mPendingEnabled.remove(tuner, true);
            }
        }
        catch(Exception e)
        {
            mLog.warn("Unable to perform requested tuner maintenance for [{}]", tuner.getId(), e);
            mErrors.put(tuner, "Unable to perform tuner maintenance");
            mPendingEnabled.remove(tuner);
        }
        finally
        {
            tuner.releaseAfterAllocation();
        }
    }

    private boolean hasPendingSettings(DiscoveredTuner tuner)
    {
        Map<String,Object> settings = mPending.get(tuner);
        return settings != null && !settings.isEmpty();
    }

    private static boolean isIdle(DiscoveredTuner tuner)
    {
        if(!tuner.hasTuner())
        {
            return true;
        }

        // A channelizer can still hold the sample-rate lock after its last channel count reaches zero.  Hardware
        // rate setters may update the USB device before FrequencyController silently rejects the locked new rate.
        TunerController controller = tuner.getTuner().getTunerController();
        return tuner.getTuner().getChannelSourceManager() != null &&
            tuner.getTuner().getChannelSourceManager().getTunerChannelCount() == 0 &&
            !controller.isLockedSampleRate();
    }

    private boolean apply(DiscoveredTuner tuner, String settingId, Object value)
    {
        TunerConfiguration configuration = configuration(tuner);
        DiscoveredTuner sibling = "device".equals(TunerSettingCatalog.scope(configuration, settingId)) ?
            rspDuoSibling(tuner) : null;

        if(sibling != null && !sibling.tryAcquireForAllocation())
        {
            return false;
        }

        try
        {
            if(sibling != null && (!mContains.test(sibling) || !isIdle(sibling)))
            {
                return false;
            }

            if(sibling != null && "sample_rate".equals(settingId) && sibling.isEnabled())
            {
                // The RSPduo bridge swallows slave sample-rate errors.  Requiring the slave to be disabled avoids
                // reporting success while its frequency controller or native buffer factory still uses the old rate.
                mErrors.put(tuner, "Disable RSPduo tuner 2 before changing the shared sample rate");
                return false;
            }

            return applyWithGroupReserved(tuner, settingId, value);
        }
        finally
        {
            if(sibling != null)
            {
                sibling.releaseAfterAllocation();
            }
        }
    }

    private DiscoveredTuner rspDuoSibling(DiscoveredTuner tuner)
    {
        if(tuner instanceof DiscoveredRspDuoTuner1 master)
        {
            return mFind.apply(master.getSlaveId());
        }
        if(tuner instanceof DiscoveredRspDuoTuner2 slave)
        {
            return mFind.apply(slave.getMasterId());
        }
        return null;
    }

    private boolean applyWithGroupReserved(DiscoveredTuner tuner, String settingId, Object value)
    {
        TunerConfiguration configuration = configuration(tuner);

        if(!TunerSettingCatalog.isEditableInCurrentMode(configuration, settingId))
        {
            // Another queued setting or the local editor changed the gain mode since submission.
            mErrors.put(tuner, "Setting " + settingId + " is no longer editable in the current tuner mode");
            return true;
        }

        Object previous = TunerSettingCatalog.readRaw(configuration, settingId);
        boolean applied = false;

        try
        {
            TunerSettingCatalog.write(configuration, settingId, value);
            applied = !tuner.hasTuner();

            if(tuner.hasTuner())
            {
                TunerController controller = tuner.getTuner().getTunerController();

                // Do not contend with an in-flight retune or sample-rate update.  This worker will retry later.
                if(!controller.getLock().tryLock())
                {
                    TunerSettingCatalog.write(configuration, settingId, previous);
                    return false;
                }

                try
                {
                    if(TunerSettingCatalog.requiresIdle(configuration, settingId) && !isIdle(tuner))
                    {
                        TunerSettingCatalog.write(configuration, settingId, previous);
                        return false;
                    }

                    applyToHardware(controller, configuration, settingId);
                    applied = true;
                }
                finally
                {
                    controller.getLock().unlock();
                }
            }

            syncSharedRspDuoConfiguration(tuner, settingId);
            mPersist.run();
            mErrors.remove(tuner);
            return true;
        }
        catch(Exception e)
        {
            // Do not claim the old value after a successful hardware write.  Persistence/sibling synchronization
            // can still fail, in which case the live value must remain visible and the admin must see the error.
            if(!applied)
            {
                TunerSettingCatalog.write(configuration, settingId, previous);
            }
            mErrors.put(tuner, applied ?
                "Applied " + settingId + " but could not save or synchronize it" :
                "Unable to apply " + settingId);
            mLog.warn("Unable to apply tuner setting [{}] for [{}]", settingId, tuner.getId(), e);
            return true;
        }
    }

    private void syncSharedRspDuoConfiguration(DiscoveredTuner tuner, String settingId)
    {
        if(!(tuner instanceof DiscoveredRspDuoTuner1) ||
            !"device".equals(TunerSettingCatalog.scope(configuration(tuner), settingId)))
        {
            return;
        }

        DiscoveredTuner sibling = rspDuoSibling(tuner);

        if(sibling != null && TunerSettingCatalog.isRspDuoSlave(sibling) &&
            sibling.getTunerConfiguration() != null)
        {
            Object value = TunerSettingCatalog.readRaw(configuration(tuner), settingId);
            TunerSettingCatalog.write(sibling.getTunerConfiguration(), settingId, value);
        }
    }

    /**
     * RSP controller.apply() deliberately logs and swallows several SDRplay API failures.  Use its field setters for
     * a web mutation so that a failed hardware call cannot be reported and persisted as an applied setting.
     */
    private static void applyToHardware(TunerController controller, TunerConfiguration configuration,
                                        String settingId) throws Exception
    {
        if("frequency_mhz".equals(settingId))
        {
            controller.setFrequency(configuration.getFrequency());
            return;
        }

        if(!TunerSettingCatalog.requiresIdle(configuration, settingId))
        {
            if(controller instanceof AirspyTunerController airspy &&
                configuration instanceof AirspyTunerConfiguration config)
            {
                switch(settingId)
                {
                    case "gain" -> airspy.setGain(config.getGain());
                    case "if_gain" -> airspy.setIFGain(config.getIFGain());
                    case "mixer_gain" -> airspy.setMixerGain(config.getMixerGain());
                    case "lna_gain" -> airspy.setLNAGain(config.getLNAGain());
                    case "mixer_agc" -> airspy.setMixerAGC(config.isMixerAGC());
                    case "lna_agc" -> airspy.setLNAAGC(config.isLNAAGC());
                    default -> throw new IllegalArgumentException("Unsupported live Airspy setting");
                }
                return;
            }
            if(controller instanceof HydraSdrTunerController hydra &&
                configuration instanceof HydraSdrTunerConfiguration config)
            {
                switch(settingId)
                {
                    case "gain" -> hydra.setGain(config.getGain());
                    case "if_gain" -> hydra.setIFGain(config.getIFGain());
                    case "mixer_gain" -> hydra.setMixerGain(config.getMixerGain());
                    case "lna_gain" -> hydra.setLNAGain(config.getLNAGain());
                    case "mixer_agc" -> hydra.setMixerAGC(config.isMixerAGC());
                    case "lna_agc" -> hydra.setLNAAGC(config.isLNAAGC());
                    default -> throw new IllegalArgumentException("Unsupported live HydraSDR setting");
                }
                return;
            }
            if(controller instanceof AirspyHfTunerController hf &&
                configuration instanceof AirspyHfTunerConfiguration config)
            {
                switch(settingId)
                {
                    case "agc" -> hf.setAgc(config.isAgc());
                    case "lna" -> hf.setLna(config.isLna());
                    case "attenuation" -> hf.setAttenuation(config.getAttenuation());
                    default -> throw new IllegalArgumentException("Unsupported live Airspy HF setting");
                }
                return;
            }
            if(controller instanceof HackRFTunerController hackrf &&
                configuration instanceof HackRFTunerConfiguration config)
            {
                switch(settingId)
                {
                    case "lna_gain" -> hackrf.setLNAGain(config.getLNAGain());
                    case "vga_gain" -> hackrf.setVGAGain(config.getVGAGain());
                    case "amplifier" -> hackrf.setAmplifierEnabled(config.getAmplifierEnabled());
                    default -> throw new IllegalArgumentException("Unsupported live HackRF setting");
                }
                return;
            }
            if(controller instanceof RTL2832TunerController rtl &&
                configuration instanceof RTL2832TunerConfiguration)
            {
                if(configuration instanceof E4KTunerConfiguration e4k &&
                    rtl.getEmbeddedTuner() instanceof E4KEmbeddedTuner embedded)
                {
                    switch(settingId)
                    {
                        case "master_gain" -> embedded.setGain(e4k.getMasterGain(), true);
                        case "mixer_gain" -> embedded.setMixerGain(e4k.getMixerGain(), true);
                        case "lna_gain" -> embedded.setLNAGain(e4k.getLNAGain(), true);
                        case "if_gain" -> embedded.setIFGain(e4k.getIFGain(), true);
                        default -> throw new IllegalArgumentException("Unsupported live E4000 setting");
                    }
                    return;
                }
                if(configuration instanceof FC0013TunerConfiguration fc0013 &&
                    rtl.getEmbeddedTuner() instanceof FC0013EmbeddedTuner embedded)
                {
                    embedded.setGain(fc0013.getAGC(), fc0013.getLnaGain());
                    return;
                }
                if(configuration instanceof R8xTunerConfiguration r8x &&
                    rtl.getEmbeddedTuner() instanceof R8xEmbeddedTuner embedded)
                {
                    switch(settingId)
                    {
                        case "master_gain" -> embedded.setGain(r8x.getMasterGain(), true);
                        case "mixer_gain" -> embedded.setMixerGain(r8x.getMixerGain(), true);
                        case "lna_gain" -> embedded.setLNAGain(r8x.getLNAGain(), true);
                        case "vga_gain" -> embedded.setVGAGain(r8x.getVGAGain(), true);
                        default -> throw new IllegalArgumentException("Unsupported live R8x setting");
                    }
                    return;
                }
            }
        }

        if(!(controller instanceof RspTunerController<?> rsp) ||
            !(configuration instanceof RspTunerConfiguration rspConfiguration))
        {
            controller.apply(configuration);
            return;
        }

        Object control = rsp.getControlRsp();

        switch(settingId)
        {
            case "frequency_correction_ppm" ->
                controller.setFrequencyCorrection(configuration.getFrequencyCorrection());
            case "automatic_ppm" -> controller.getTunerFrequencyErrorManager()
                .setEnabled(configuration.getAutoPPMCorrectionEnabled());
            case "center_frequency_locked" ->
                controller.setCenterFrequencyLocked(configuration.isCenterFrequencyLocked());
            case "sample_rate" -> rsp.setSampleRate(rspConfiguration.getSampleRate());
            case "lna", "baseband_gain_reduction" -> rsp.getControlRsp().setGain(rspConfiguration.getLNA(),
                rspConfiguration.getBasebandGainReduction());
            case "agc_mode" -> rsp.getControlRsp().setAgcMode(rspConfiguration.getAgcMode());
            default ->
            {
                String method = switch(settingId)
                {
                    case "rf_notch" -> "setRfNotch";
                    case "dab_notch" -> "setRfDabNotch";
                    case "bias_t" -> "setBiasT";
                    case "antenna" -> configuration.getTunerType() ==
                        io.github.dsheirer.source.tuner.TunerType.RSP_2 ? "setAntennaSelection" : "setAntenna";
                    case "external_reference" -> "setExternalReferenceOutput";
                    case "am_port" -> "setAmPort";
                    case "am_notch" -> "setAmNotch";
                    case "hdr_mode" -> "setHighDynamicRange";
                    case "hdr_bandwidth" -> "setHdrModeBandwidth";
                    default -> throw new IllegalArgumentException("Unsupported live RSP setting");
                };

                Object value = TunerSettingCatalog.readRaw(configuration, settingId);
                Method setter = control.getClass().getMethod(method, value instanceof Boolean ? boolean.class :
                    value.getClass());

                try
                {
                    setter.invoke(control, value);
                }
                catch(InvocationTargetException e)
                {
                    throw e.getCause() instanceof Exception exception ? exception : e;
                }
            }
        }
    }

    private void ensureOpen()
    {
        if(mClosed)
        {
            throw new IllegalStateException("Tuner settings service is stopping");
        }
    }

    private void ensurePresent(DiscoveredTuner tuner)
    {
        if(tuner == null || !mContains.test(tuner))
        {
            throw new IllegalArgumentException("Tuner is no longer available");
        }
    }

    @Override
    public void close()
    {
        mClosed = true;
        mPending.clear();
        mPendingEnabled.clear();
        mExecutor.shutdownNow();
    }

    public record MutationResult(String status, String message)
    {
    }
}
