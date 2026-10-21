/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.source.tuner.manager;

import io.github.dsheirer.source.tuner.TunerController;
import io.github.dsheirer.source.tuner.channel.TunerChannel;
import io.github.dsheirer.source.tuner.configuration.TunerConfiguration;
import io.github.dsheirer.source.tuner.sdrplay.RspTunerConfiguration;
import io.github.dsheirer.source.tuner.sdrplay.RspTunerController;
import io.github.dsheirer.source.tuner.sdrplay.rspDuo.DiscoveredRspDuoTuner1;
import io.github.dsheirer.source.tuner.sdrplay.rspDuo.DiscoveredRspDuoTuner2;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** One-shot saved-value/hardware transaction. No operator sessions, workers, browser leases or persistence. */
final class TunerSettingsApplication
{
    private static final Logger mLog = LoggerFactory.getLogger(TunerSettingsApplication.class);
    private final Predicate<DiscoveredTuner> mContains;
    private final Function<String,DiscoveredTuner> mFind;

    TunerSettingsApplication(Predicate<DiscoveredTuner> contains, Function<String,DiscoveredTuner> find)
    {
        mContains = Objects.requireNonNull(contains);
        mFind = Objects.requireNonNull(find);
    }

    record Result(ApplyResult status, String error) {}

    private static TunerConfiguration configuration(DiscoveredTuner tuner)
    {
        return Objects.requireNonNull(tuner.getTunerConfiguration(), "Tuner configuration is unavailable");
    }

    private static boolean isIdle(DiscoveredTuner tuner)
    {
        if(!tuner.hasTuner()) return true;
        TunerController controller = tuner.getTuner().getTunerController();
        return tuner.getTuner().getChannelSourceManager() != null &&
            tuner.getTuner().getChannelSourceManager().getTunerChannelCount() == 0 && !controller.isLockedSampleRate();
    }

    private static boolean isFullyDisabled(DiscoveredTuner tuner)
    {
        return tuner.getOperatorState() == DiscoveredTuner.OperatorState.DISABLED && !tuner.isEnabled();
    }

    Result apply(DiscoveredTuner tuner, String settingId, Object value)
    {
        String error = null;
        TunerConfiguration configuration = configuration(tuner);
        DiscoveredTuner sibling = "device".equals(TunerSettingCatalog.scope(configuration, settingId)) ?
            sibling(tuner) : null;
        boolean tunerReserved = false;

        if("frequency_mhz".equals(settingId))
        {
            if(!tuner.tryAcquireForAllocation())
            {
                return new Result(ApplyResult.RETRY, null);
            }
            tunerReserved = true;
        }

        try
        {
            if(sibling != null && !sibling.tryAcquireForAllocation())
            {
                return new Result(ApplyResult.RETRY, null);
            }
            try
            {
                if(sibling != null && (!mContains.test(sibling) || !isIdle(sibling)))
                {
                    return new Result(ApplyResult.RETRY, null);
                }

                if(sibling != null && "sample_rate".equals(settingId) && !isFullyDisabled(sibling))
                {
                    error = "Disable tuner 2";
                    return new Result(ApplyResult.CONSUMED, error);
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
        finally
        {
            if(tunerReserved)
            {
                tuner.releaseAfterAllocation();
            }
        }
    }

    /**
     * Apply frequency limits together on the settings worker.  With active channels the limits govern subsequent
     * admission only: never move the current center or invalidate an already allocated channel.  When idle, retain
     * the established behavior of clamping and retuning the center to a newly selected range.
     */
    Result applyFrequencyExtents(DiscoveredTuner tuner, Map<String,Object> requested)
    {
        String error = null;
        TunerConfiguration configuration = configuration(tuner);
        TunerController controller = tuner.hasTuner() ? tuner.getTuner().getTunerController() : null;
        if(tuner.isEnabled() && (tuner.getTunerStatus() != TunerStatus.ENABLED || controller == null))
        {
            return new Result(ApplyResult.RETRY, null);
        }
        // An idle retune must reserve the lifecycle against a new allocation.  An active limit change only adjusts
        // admission bounds, so it does not reserve a busy tuner and make decoder-originated allocations fail fast.
        boolean reserved = false;
        if(isIdle(tuner))
        {
            if(!tuner.tryAcquireForAllocation())
            {
                return new Result(ApplyResult.RETRY, null);
            }
            reserved = true;
        }
        boolean locked = false;
        long previousMinimum = 0;
        long previousMaximum = 0;
        long previousFrequency = 0;
        boolean hardwareChanged = false;

        try
        {
            if(controller != null)
            {
                locked = controller.getLock().tryLock();
                if(!locked)
                {
                    return new Result(ApplyResult.RETRY, null);
                }
                if(!reserved && isIdle(tuner))
                {
                    // It became idle after the initial observation.  Retry with the lifecycle reservation rather
                    // than racing a new channel allocation if the new range requires a center retune.
                    return new Result(ApplyResult.RETRY, null);
                }
            }

            long liveSampleRate = controller != null ? Math.round(controller.getSampleRate()) : 0;
            long sampleRate = liveSampleRate > 0 ? liveSampleRate : configuration.getConfiguredSampleRate();
            TunerSettingCatalog.FrequencyExtents extents = TunerSettingCatalog.resolveFrequencyExtents(
                configuration, requested, sampleRate);
            long frequency = controller != null ? controller.getFrequency() : configuration.getFrequency();
            long clampedFrequency = Math.max(extents.minimumHz(), Math.min(extents.maximumHz(), frequency));

            if(controller != null)
            {
                if(!isIdle(tuner))
                {
                    if(clampedFrequency != frequency)
                    {
                        throw new IllegalArgumentException("Center is outside limits");
                    }
                    ChannelSourceManager channels = tuner.getTuner().getChannelSourceManager();
                    if(channels == null)
                    {
                        throw new IllegalArgumentException("Channels unavailable");
                    }
                    var allocated = channels.getTunerChannels();
                    if(channels.getTunerChannelCount() > 0 && allocated.isEmpty())
                    {
                        throw new IllegalArgumentException("Channels unavailable");
                    }
                    for(TunerChannel channel: allocated)
                    {
                        if(channel.getMinFrequency() < extents.minimumHz() ||
                            channel.getMaxFrequency() > extents.maximumHz())
                        {
                            throw new IllegalArgumentException("Channel is outside limits");
                        }
                    }
                }
                previousMinimum = controller.getMinimumFrequency();
                previousMaximum = controller.getMaximumFrequency();
                previousFrequency = frequency;
                if(clampedFrequency != frequency)
                {
                    // Only an idle tuner can reach this branch.  First widen, retune, then narrow, keeping both
                    // centers valid throughout even when the old and new ranges do not overlap.
                    controller.setFrequencyExtents(Math.min(previousMinimum, extents.minimumHz()),
                        Math.max(previousMaximum, extents.maximumHz()));
                    hardwareChanged = true;
                    controller.setFrequency(clampedFrequency);
                }
                else
                {
                    hardwareChanged = true;
                }
                controller.setFrequencyExtents(extents.minimumHz(), extents.maximumHz());
            }

            configuration.setMinimumFrequency(extents.minimumHz());
            configuration.setMaximumFrequency(extents.maximumHz());
            configuration.setFrequency(clampedFrequency);
            error = null;
            return new Result(ApplyResult.APPLIED, error);
        }
        catch(Exception e)
        {
            if(controller != null && hardwareChanged)
            {
                try
                {
                    if(controller.getFrequency() != previousFrequency)
                    {
                        controller.setFrequencyExtents(Math.min(previousMinimum, controller.getMinimumFrequency()),
                            Math.max(previousMaximum, controller.getMaximumFrequency()));
                        controller.setFrequency(previousFrequency);
                    }
                    controller.setFrequencyExtents(previousMinimum, previousMaximum);
                }
                catch(Exception rollback)
                {
                    e.addSuppressed(rollback);
                }
            }
            if(e instanceof IllegalArgumentException && !hardwareChanged)
            {
                error = e.getMessage();
            }
            else
            {
                error = "Frequency limit change failed";
                mLog.warn("Unable to apply tuner frequency limits for [{}]", tuner.getId(), e);
            }
            return new Result(ApplyResult.CONSUMED, error);
        }
        finally
        {
            if(locked)
            {
                controller.getLock().unlock();
            }
            if(reserved)
            {
                tuner.releaseAfterAllocation();
            }
        }
    }

    DiscoveredTuner sibling(DiscoveredTuner tuner)
    {
        if(tuner instanceof DiscoveredRspDuoTuner1 master &&
            master.getDeviceInfo().getDeviceSelectionMode().isMasterMode())
        {
            DiscoveredTuner sibling = mFind.apply(master.getSlaveId());
            return sibling instanceof DiscoveredRspDuoTuner2 slave &&
                slave.getDeviceInfo().getDeviceSelectionMode().isSlaveMode() ? sibling : null;
        }
        if(tuner instanceof DiscoveredRspDuoTuner2 slave &&
            slave.getDeviceInfo().getDeviceSelectionMode().isSlaveMode())
        {
            DiscoveredTuner sibling = mFind.apply(slave.getMasterId());
            return sibling instanceof DiscoveredRspDuoTuner1 master &&
                master.getDeviceInfo().getDeviceSelectionMode().isMasterMode() ? sibling : null;
        }
        return null;
    }

    private Result applyWithGroupReserved(DiscoveredTuner tuner, String settingId, Object value)
    {
        String error = null;
        TunerConfiguration configuration = configuration(tuner);

        if(!TunerSettingCatalog.isEditableInCurrentMode(configuration, settingId))
        {
            error = "Setting unavailable";
            return new Result(ApplyResult.CONSUMED, error);
        }

        if("sample_rate".equals(settingId))
        {
            try
            {
                TunerSettingCatalog.validateSampleRateSpan(configuration, value);
            }
            catch(IllegalArgumentException e)
            {
                error = e.getMessage();
                return new Result(ApplyResult.CONSUMED, error);
            }
        }

        Object previous = TunerSettingCatalog.readRaw(configuration, settingId);
        boolean written = false;
        boolean applied = false;

        try
        {
            if(tuner.hasTuner())
            {
                TunerController controller = tuner.getTuner().getTunerController();

                // A one-shot request never waits behind an in-flight hardware change.
                if(!controller.getLock().tryLock())
                {
                    return new Result(ApplyResult.RETRY, null);
                }

                try
                {
                    if((TunerSettingCatalog.requiresSetup(configuration, settingId) ||
                        "frequency_mhz".equals(settingId)) && !isIdle(tuner))
                    {
                        return new Result(ApplyResult.RETRY, null);
                    }

                    if("lna".equals(settingId) && configuration instanceof RspTunerConfiguration &&
                        controller instanceof RspTunerController<?> rsp)
                    {
                        // The valid RSP LNA range depends on the current tuned frequency (and for RSPduo, AM port).
                        // It may have narrowed since validation. Check again while holding the controller lock.
                        int requested = ((Number)value).intValue();
                        int maximum = rsp.getControlRsp().getMaximumLNASetting();

                        if(requested < 0 || requested > maximum)
                        {
                            error = "LNA range is 0-" + maximum;
                            return new Result(ApplyResult.CONSUMED, error);
                        }
                    }

                    if("frequency_mhz".equals(settingId))
                    {
                        validateCenterFrequency(tuner, controller, ((Number)value).longValue());
                    }

                    TunerSettingCatalog.write(configuration, settingId, value);
                    written = true;
                    TunerSettingCatalog.apply(controller, configuration, settingId);
                    applied = true;
                }
                finally
                {
                    controller.getLock().unlock();
                }
            }
            else
            {
                TunerSettingCatalog.write(configuration, settingId, value);
                written = true;
                applied = true;
            }

            syncSharedRspDuoConfiguration(tuner, settingId);
            error = null;
            return new Result(ApplyResult.APPLIED, error);
        }
        catch(Exception e)
        {
            if(written && !applied && hardwareAccepted(tuner, configuration, settingId))
            {
                // The hardware write completed and only its observer notification failed. Keep the actual live value
                // and persist it so a restart cannot silently restore the stale value.
                error = "Applied; notification failed";
                mLog.warn("Tuner setting [{}] applied for [{}], but notification failed", settingId, tuner.getId(), e);
                return new Result(ApplyResult.APPLIED, error);
            }
            // Do not claim the old value after a successful hardware write.  Persistence/sibling synchronization
            // can still fail, in which case the live value must remain visible and the admin must see the error.
            if(written && !applied)
            {
                TunerSettingCatalog.write(configuration, settingId, previous);
            }
            String reason = e instanceof IllegalArgumentException && e.getMessage() != null ? e.getMessage() : null;
            error = applied ? "Applied; sync failed" :
                Objects.requireNonNullElse(reason, "Change failed");
            mLog.warn("Unable to apply tuner setting [{}] for [{}]", settingId, tuner.getId(), e);
            return new Result(applied ? ApplyResult.APPLIED : ApplyResult.CONSUMED, error);
        }
    }

    private static boolean hardwareAccepted(DiscoveredTuner tuner, TunerConfiguration configuration,
                                             String settingId)
    {
        if(!tuner.hasTuner())
        {
            return false;
        }
        TunerController controller = tuner.getTuner().getTunerController();
        return switch(settingId)
        {
            case "frequency_correction_ppm" ->
                Double.compare(controller.getFrequencyCorrection(), configuration.getFrequencyCorrection()) == 0;
            case "frequency_mhz" -> controller.getFrequency() == configuration.getFrequency();
            default -> false;
        };
    }

    enum ApplyResult
    {
        RETRY,
        CONSUMED,
        APPLIED
    }

    private static void validateCenterFrequency(DiscoveredTuner tuner, TunerController controller, long center)
    {
        if(tuner.getTuner() == null || tuner.getTuner().getChannelSourceManager() == null)
        {
            return;
        }
        ChannelSourceManager manager = tuner.getTuner().getChannelSourceManager();
        int count = manager.getTunerChannelCount();
        if(count <= 0)
        {
            return;
        }
        var channels = manager.getTunerChannels();
        if(channels.isEmpty())
        {
            throw new IllegalArgumentException("Channels unavailable");
        }
        long minimum = center - controller.getUsableHalfBandwidth();
        long maximum = center + controller.getUsableHalfBandwidth();
        long avoidMinimum = center - controller.getMiddleUnusableHalfBandwidth();
        long avoidMaximum = center + controller.getMiddleUnusableHalfBandwidth();
        for(TunerChannel channel: channels)
        {
            if(channel.getMinFrequency() < minimum || channel.getMaxFrequency() > maximum ||
                (controller.hasMiddleUnusableBandwidth() && channel.overlaps(avoidMinimum, avoidMaximum)))
            {
                throw new IllegalArgumentException("Channels won't fit");
            }
        }
    }

    private void syncSharedRspDuoConfiguration(DiscoveredTuner tuner, String settingId)
    {
        if(!(tuner instanceof DiscoveredRspDuoTuner1) ||
            !"device".equals(TunerSettingCatalog.scope(configuration(tuner), settingId)))
        {
            return;
        }

        DiscoveredTuner sibling = sibling(tuner);

        if(sibling != null && TunerSettingCatalog.isRspDuoSlave(sibling) &&
            sibling.getTunerConfiguration() != null)
        {
            Object value = TunerSettingCatalog.readRaw(configuration(tuner), settingId);
            TunerSettingCatalog.write(sibling.getTunerConfiguration(), settingId, value);
        }
    }

}
