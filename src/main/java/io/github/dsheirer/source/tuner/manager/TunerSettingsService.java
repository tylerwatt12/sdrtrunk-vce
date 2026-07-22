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

package io.github.dsheirer.source.tuner.manager;

import io.github.dsheirer.source.tuner.Tuner;
import io.github.dsheirer.source.tuner.TunerController;
import io.github.dsheirer.source.tuner.airspy.AirspySampleRate;
import io.github.dsheirer.source.tuner.airspy.AirspyTunerConfiguration;
import io.github.dsheirer.source.tuner.airspy.AirspyTunerController;
import io.github.dsheirer.source.tuner.airspy.AirspyTunerController.Gain;
import io.github.dsheirer.source.tuner.airspy.AirspyTunerController.GainMode;
import io.github.dsheirer.source.tuner.configuration.TunerConfiguration;
import io.github.dsheirer.source.tuner.rtl.RTL2832TunerConfiguration;
import io.github.dsheirer.source.tuner.rtl.RTL2832TunerController;
import io.github.dsheirer.source.tuner.rtl.r8x.R8xEmbeddedTuner;
import io.github.dsheirer.source.tuner.rtl.r8x.R8xTunerConfiguration;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bounded, serialized command owner for administrator tuner settings.
 *
 * <p>Every detailed read and mutation runs on one low-priority worker.  Browser activity therefore never performs a
 * USB operation on a Jetty, sample, decoder, recording, or audio thread.  The service retains no history and creates
 * no database state; successful changes use the existing coalesced tuner-configuration save.</p>
 */
public final class TunerSettingsService implements TunerSettingsOperations, AutoCloseable
{
    private static final Logger mLog = LoggerFactory.getLogger(TunerSettingsService.class);
    private static final int MAXIMUM_QUEUED_COMMANDS = 8;
    private static final long CONTROLLER_LOCK_TIMEOUT_MILLISECONDS = 150;
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 30;
    private static final double MINIMUM_PPM = -1_000.0;
    private static final double MAXIMUM_PPM = 1_000.0;
    private static final long MAXIMUM_BROWSER_SAFE_REVISION = (1L << 53) - 1;
    private static final String DEVICE_AIRSPY = "AIRSPY";
    private static final String DEVICE_R8X = "RTL_R8X";
    private static final String DEVICE_UNSUPPORTED = "UNSUPPORTED";
    private static final List<AirspyRate> AIRSPY_FALLBACK_SAMPLE_RATES = List.of(
        new AirspyRate(10_000_000, "10.00 MHz"),
        new AirspyRate(6_000_000, "6.00 MHz"),
        new AirspyRate(3_000_000, "3.00 MHz"),
        new AirspyRate(2_500_000, "2.50 MHz"));

    private final Runnable mSaveConfigurations;
    private final TunerRegistry mTunerRegistry;
    private final ThreadPoolExecutor mExecutor;
    private final long mShutdownTimeoutNanos;
    private final Set<CommandTask<?>> mCommands = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean mAccepting = new AtomicBoolean(true);
    private final Map<Object,Long> mRuntimeGenerations = new WeakHashMap<>();
    private final Map<DiscoveredTuner,List<AirspyRate>> mAirspySampleRates = new WeakHashMap<>();
    private final Map<DiscoveredTuner,Integer> mAirspyPresetGains = new WeakHashMap<>();
    private long mNextRuntimeGeneration = ThreadLocalRandom.current().nextLong(1, MAXIMUM_BROWSER_SAFE_REVISION);

    public TunerSettingsService(TunerManager tunerManager, TunerRegistry tunerRegistry)
    {
        this(tunerRegistry, Objects.requireNonNull(tunerManager, "Tuner manager cannot be null")::saveConfigurations);
    }

    /**
     * Deterministic test seam that avoids constructing application persistence.
     */
    TunerSettingsService(TunerRegistry tunerRegistry, Runnable saveConfigurations)
    {
        this(tunerRegistry, saveConfigurations, Duration.ofSeconds(SHUTDOWN_TIMEOUT_SECONDS));
    }

    /**
     * Test seam for exercising shutdown timeout behavior without waiting for the production hardware timeout.
     */
    TunerSettingsService(TunerRegistry tunerRegistry, Runnable saveConfigurations, Duration shutdownTimeout)
    {
        mTunerRegistry = Objects.requireNonNull(tunerRegistry, "Tuner registry cannot be null");
        mSaveConfigurations = Objects.requireNonNull(saveConfigurations,
            "Tuner configuration save action cannot be null");
        Duration timeout = Objects.requireNonNull(shutdownTimeout, "Shutdown timeout cannot be null");

        if(timeout.isNegative() || timeout.isZero())
        {
            throw new IllegalArgumentException("Shutdown timeout must be positive");
        }

        mShutdownTimeoutNanos = timeout.toNanos();
        mExecutor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(MAXIMUM_QUEUED_COMMANDS), runnable ->
            {
                Thread thread = new Thread(runnable, "web tuner control");
                thread.setDaemon(true);
                thread.setPriority(Thread.MIN_PRIORITY);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * Reads one current settings resource without performing device identity or EEPROM queries.
     */
    public CompletableFuture<Map<String,Object>> settings(String tunerId)
    {
        return submit(() -> snapshot(resolve(tunerId), normalizeId(tunerId)));
    }

    /**
     * Applies one complete, validated form submission.
     */
    public CompletableFuture<Map<String,Object>> update(String tunerId, UpdateRequest request,
                                                         BooleanSupplier sessionIsValid)
    {
        Objects.requireNonNull(request, "Tuner settings update cannot be null");
        Objects.requireNonNull(sessionIsValid, "Administrator session validator cannot be null");
        return submit(() -> updateOnWorker(tunerId, request, sessionIsValid));
    }

    /**
     * Enables or disables one receiver independently from its editable configuration.
     */
    public CompletableFuture<Map<String,Object>> setEnabled(String tunerId, EnabledRequest request,
                                                             BooleanSupplier sessionIsValid)
    {
        Objects.requireNonNull(request, "Tuner enabled update cannot be null");
        Objects.requireNonNull(sessionIsValid, "Administrator session validator cannot be null");
        return submit(() -> setEnabledOnWorker(tunerId, request, sessionIsValid));
    }

    private Map<String,Object> updateOnWorker(String tunerId, UpdateRequest request,
                                               BooleanSupplier sessionIsValid)
    {
        ensureAccepting();
        requireSession(sessionIsValid);
        DiscoveredTuner discoveredTuner = resolve(tunerId);
        TunerConfiguration configuration = requireConfiguration(discoveredTuner);
        long currentRevision = revision(discoveredTuner, configuration);
        boolean shutdownIncomplete = discoveredTuner.hasTuner() && discoveredTuner.isLifecycleQuiescing() &&
            (!discoveredTuner.isEnabled() || discoveredTuner.getTunerStatus() == TunerStatus.ERROR);

        if(request.revision() == null)
        {
            throw error(428, "revision_required", "Reload the receiver settings before saving.");
        }

        if(request.revision() != currentRevision)
        {
            throw error(412, "settings_changed", "Receiver settings changed. Reload them and try again.");
        }

        validateCommon(request);
        if(shutdownIncomplete)
        {
            throw error(409, "receiver_shutdown_incomplete",
                "Finish disabling this receiver before changing its settings.");
        }

        if(!discoveredTuner.isEnabled())
        {
            ensureAccepting();
            requireSession(sessionIsValid);
            discoveredTuner = resolve(tunerId);
            configuration = requireConfiguration(discoveredTuner);

            if(discoveredTuner.isEnabled() || revision(discoveredTuner, configuration) != currentRevision)
            {
                throw error(412, "settings_changed", "Receiver settings changed. Reload them and try again.");
            }

            applyDisabledUpdate(discoveredTuner, configuration, request);

            try
            {
                mSaveConfigurations.run();
            }
            catch(RuntimeException exception)
            {
                mLog.warn("Unable to queue persistence for disabled web tuner settings [{}]", tunerId, exception);
                throw error(503, "settings_save_failed",
                    "The receiver settings changed for this run, but they could not be saved.");
            }

            return snapshot(resolve(tunerId), normalizeId(tunerId));
        }

        DiscoveredTuner.LifecycleLease lifecycleLease = discoveredTuner.tryAcquireLifecycleLease();

        if(lifecycleLease == null)
        {
            throw error(409, "receiver_not_running",
                "Disable the receiver to edit its saved settings, or re-enable it to restore hardware control.");
        }

        Tuner tuner = lifecycleLease.getTuner();
        TunerController controller = tuner.getTunerController();

        if(controller == null)
        {
            lifecycleLease.close();
            throw error(409, "receiver_not_running", "The receiver controller is unavailable.");
        }
        ReentrantLock controllerLock = controller.getLock();
        boolean locked;

        try
        {
            locked = controllerLock.tryLock(CONTROLLER_LOCK_TIMEOUT_MILLISECONDS, TimeUnit.MILLISECONDS);
        }
        catch(InterruptedException exception)
        {
            Thread.currentThread().interrupt();
            lifecycleLease.close();
            throw unavailable();
        }

        if(!locked)
        {
            lifecycleLease.close();
            throw error(409, "receiver_busy", "The receiver is busy. Try saving again in a moment.");
        }

        try
        {
            ensureAccepting();
            requireSession(sessionIsValid);
            discoveredTuner = resolve(tunerId);
            configuration = requireConfiguration(discoveredTuner);

            if(discoveredTuner.getTuner() != tuner || revision(discoveredTuner, configuration) != currentRevision)
            {
                throw error(412, "settings_changed", "Receiver settings changed. Reload them and try again.");
            }

            applyUpdate(discoveredTuner, tuner, controller, configuration, request);
        }
        catch(TunerSettingsException exception)
        {
            throw exception;
        }
        catch(Exception exception)
        {
            mLog.warn("Unable to apply web tuner settings for [{}]", tunerId, exception);
            throw error(503, "receiver_command_failed",
                "The receiver could not apply those settings. Its saved settings were not changed.");
        }
        finally
        {
            controllerLock.unlock();
            lifecycleLease.close();
        }

        //Do configuration serialization and response assembly after releasing the radio allocation lock.
        try
        {
            mSaveConfigurations.run();
        }
        catch(RuntimeException exception)
        {
            mLog.warn("Unable to queue persistence for web tuner settings [{}]", tunerId, exception);
            throw error(503, "settings_save_failed",
                "The receiver applied those settings for this run, but they could not be saved.");
        }

        return snapshot(resolve(tunerId), normalizeId(tunerId));
    }

    private Map<String,Object> setEnabledOnWorker(String tunerId, EnabledRequest request,
                                                   BooleanSupplier sessionIsValid)
    {
        ensureAccepting();
        requireSession(sessionIsValid);
        DiscoveredTuner discoveredTuner = resolve(tunerId);
        TunerConfiguration configuration = discoveredTuner.getTunerConfiguration();
        long currentRevision = revision(discoveredTuner, configuration);

        if(request.revision() == null)
        {
            throw error(428, "revision_required", "Reload the receiver settings before changing its state.");
        }

        if(request.enabled() == null)
        {
            throw invalid("An enabled state is required.");
        }

        if(request.revision() != currentRevision)
        {
            throw error(412, "settings_changed", "Receiver settings changed. Reload them and try again.");
        }

        boolean incompleteStop = !request.enabled() && !discoveredTuner.isEnabled() &&
            discoveredTuner.hasTuner() && discoveredTuner.isLifecycleQuiescing();

        if(discoveredTuner.isEnabled() == request.enabled() && !incompleteStop)
        {
            return snapshot(discoveredTuner, normalizeId(tunerId));
        }

        try
        {
            DiscoveredTuner lifecycleTarget = discoveredTuner;
            ensureAccepting();
            requireSession(sessionIsValid);
            discoveredTuner = resolve(tunerId);
            configuration = discoveredTuner.getTunerConfiguration();

            if(discoveredTuner != lifecycleTarget || revision(discoveredTuner, configuration) != currentRevision)
            {
                throw error(412, "settings_changed", "Receiver settings changed. Reload them and try again.");
            }

            Tuner currentTuner = discoveredTuner.getTuner();
            TunerController currentController = currentTuner != null ? currentTuner.getTunerController() : null;
            boolean radioWorkActive = activeChannelCount(currentTuner) > 0 ||
                currentController != null && currentController.isLockedSampleRate();

            if(!request.enabled() && radioWorkActive && !Boolean.TRUE.equals(request.confirmActiveStop()))
            {
                throw error(409, "active_channels",
                    "This receiver is in use. Confirm that its active channels may stop.");
            }

            ensureAccepting();
            discoveredTuner.setEnabled(request.enabled());
        }
        catch(TunerSettingsException exception)
        {
            throw exception;
        }
        catch(RuntimeException exception)
        {
            mLog.warn("Unable to change web tuner enabled state for [{}]", tunerId, exception);
            throw error(503, "receiver_command_failed", "The receiver could not change its enabled state.");
        }
        try
        {
            mSaveConfigurations.run();
        }
        catch(RuntimeException exception)
        {
            mLog.warn("Unable to queue persistence for web tuner enabled state [{}]", tunerId, exception);
            throw error(503, "settings_save_failed",
                "The receiver changed state for this run, but that state could not be saved.");
        }

        DiscoveredTuner current = resolve(tunerId);

        boolean reachedRequestedState = request.enabled() ? current.isEnabled() && current.hasTuner() &&
            current.getTunerStatus().isAvailable() && !current.isLifecycleQuiescing() :
            !current.isEnabled() && !current.hasTuner() && current.getTunerStatus() == TunerStatus.DISABLED;

        if(!reachedRequestedState)
        {
            throw error(503, "receiver_command_failed", "The receiver did not reach the requested state.");
        }

        return snapshot(current, normalizeId(tunerId));
    }

    private void applyUpdate(DiscoveredTuner discoveredTuner, Tuner tuner, TunerController controller,
                             TunerConfiguration configuration, UpdateRequest request) throws Exception
    {
        int activeChannels = activeChannelCount(tuner);
        boolean radioWorkActive = activeChannels > 0 || controller.isLockedSampleRate();
        DeviceChange deviceChange = validateDevice(discoveredTuner, configuration, controller, request);
        boolean ppmChanged = request.frequencyCorrectionPpm() != null &&
            Double.compare(controller.getFrequencyCorrection(), request.frequencyCorrectionPpm()) != 0;
        long currentMinimum = effectiveMinimum(configuration, controller);
        long currentMaximum = effectiveMaximum(configuration, controller);
        long currentCenterFrequency = controller.getFrequency();
        long requestedCenterFrequency = request.centerFrequencyHz() != null ? request.centerFrequencyHz() :
            currentCenterFrequency;
        boolean centerFrequencyChanged = requestedCenterFrequency != currentCenterFrequency;
        boolean extentsChanged = currentMinimum != request.minimumFrequencyHz() ||
            currentMaximum != request.maximumFrequencyHz();
        boolean unsafeChange = ppmChanged || centerFrequencyChanged || extentsChanged || deviceChange.requiresIdle();

        if(radioWorkActive && unsafeChange)
        {
            throw error(409, "active_channels",
                "Stop this receiver’s active channels before changing center frequency, frequency correction, limits, sample rate, or Bias-T.");
        }

        if(deviceChange.sampleRateHz() > request.maximumFrequencyHz() - request.minimumFrequencyHz())
        {
            throw invalid("The minimum and maximum frequencies must be at least one sample-rate apart.");
        }

        long hardwareMinimum = hardwareMinimum(configuration);
        long hardwareMaximum = hardwareMaximum(configuration);

        if(request.minimumFrequencyHz() < hardwareMinimum || request.maximumFrequencyHz() > hardwareMaximum)
        {
            throw invalid("The minimum and maximum frequencies must stay inside this receiver’s supported range.");
        }

        if(requestedCenterFrequency < request.minimumFrequencyHz() ||
            requestedCenterFrequency > request.maximumFrequencyHz())
        {
            throw invalid("Center frequency must remain inside the minimum and maximum range.");
        }

        double originalPpm = controller.getFrequencyCorrection();
        boolean originalAutoPpm = controller.getTunerFrequencyErrorManager().isEnabled();
        boolean originalCenterFrequencyFixed = controller.isCenterFrequencyLocked();
        boolean deviceAttempted = false;
        boolean ppmApplied = false;
        boolean extentsApplied = false;
        boolean centerFrequencyApplied = false;
        boolean autoPpmApplied = false;
        boolean centerFrequencyFixedApplied = false;

        //Potentially blocking hardware work comes first. Configuration changes only after every device call succeeds.
        try
        {
            ensureAccepting();
            deviceAttempted = true;
            deviceChange.applyHardware().run();

            if(ppmChanged)
            {
                ensureAccepting();
                ppmApplied = true;
                controller.setFrequencyCorrection(request.frequencyCorrectionPpm());
            }

            if(extentsChanged)
            {
                ensureAccepting();
                extentsApplied = true;
                controller.setFrequencyExtents(request.minimumFrequencyHz(), request.maximumFrequencyHz());
            }

            if(centerFrequencyChanged)
            {
                ensureAccepting();
                centerFrequencyApplied = true;
                controller.setFrequency(requestedCenterFrequency);
            }

            if(originalAutoPpm != request.autoPpm())
            {
                ensureAccepting();
                autoPpmApplied = true;
                controller.getTunerFrequencyErrorManager().setEnabled(request.autoPpm());
            }

            if(originalCenterFrequencyFixed != request.centerFrequencyFixed())
            {
                ensureAccepting();
                centerFrequencyFixedApplied = true;
                controller.setCenterFrequencyLocked(request.centerFrequencyFixed());
            }
        }
        catch(Exception | LinkageError exception)
        {
            rollbackHardware(controller, deviceChange, originalPpm, currentCenterFrequency, currentMinimum, currentMaximum,
                originalAutoPpm, originalCenterFrequencyFixed, tuner, deviceAttempted, ppmApplied, extentsApplied,
                centerFrequencyApplied, autoPpmApplied, centerFrequencyFixedApplied);
            throw exception;
        }

        if(request.frequencyCorrectionPpm() != null)
        {
            configuration.setFrequencyCorrection(request.frequencyCorrectionPpm());
        }
        configuration.setFrequency(requestedCenterFrequency);
        configuration.setMinimumFrequency(request.minimumFrequencyHz());
        configuration.setMaximumFrequency(request.maximumFrequencyHz());
        configuration.setAutoPPMCorrectionEnabled(request.autoPpm());
        configuration.setCenterFrequencyLocked(request.centerFrequencyFixed());
        deviceChange.updateConfiguration().run();
    }

    /**
     * Validates and saves configuration while a receiver is deliberately disabled.  No tuner, controller, USB, or
     * channel-allocation operation is performed; the normal receiver startup applies these values later.
     */
    private void applyDisabledUpdate(DiscoveredTuner discoveredTuner, TunerConfiguration configuration,
                                     UpdateRequest request)
    {
        SavedDeviceChange deviceChange = validateDisabledDevice(discoveredTuner, configuration, request);

        if(deviceChange.sampleRateHz() > request.maximumFrequencyHz() - request.minimumFrequencyHz())
        {
            throw invalid("The minimum and maximum frequencies must be at least one sample-rate apart.");
        }

        long hardwareMinimum = hardwareMinimum(configuration);
        long hardwareMaximum = hardwareMaximum(configuration);

        if(request.minimumFrequencyHz() < hardwareMinimum || request.maximumFrequencyHz() > hardwareMaximum)
        {
            throw invalid("The minimum and maximum frequencies must stay inside this receiver’s supported range.");
        }

        long savedCenterFrequency = request.centerFrequencyHz() != null ? request.centerFrequencyHz() :
            configuration.getFrequency();

        if(savedCenterFrequency < request.minimumFrequencyHz() || savedCenterFrequency > request.maximumFrequencyHz())
        {
            throw invalid("Center frequency must remain inside the minimum and maximum range.");
        }

        if(request.frequencyCorrectionPpm() != null)
        {
            configuration.setFrequencyCorrection(request.frequencyCorrectionPpm());
        }
        configuration.setFrequency(savedCenterFrequency);
        configuration.setMinimumFrequency(request.minimumFrequencyHz());
        configuration.setMaximumFrequency(request.maximumFrequencyHz());
        configuration.setAutoPPMCorrectionEnabled(request.autoPpm());
        configuration.setCenterFrequencyLocked(request.centerFrequencyFixed());
        deviceChange.updateConfiguration().run();
    }

    /**
     * Best-effort restoration after a device write fails partway through. Saved configuration remains untouched, and
     * the receiver is only rolled back while the same runtime tuner is still attached.
     */
    private void rollbackHardware(TunerController controller, DeviceChange deviceChange, double frequencyCorrection,
                                  long centerFrequency, long minimumFrequency, long maximumFrequency, boolean autoPpm,
                                  boolean centerFrequencyFixed, Tuner expectedTuner, boolean deviceAttempted,
                                  boolean ppmApplied, boolean extentsApplied, boolean centerFrequencyApplied,
                                  boolean autoPpmApplied, boolean centerFrequencyFixedApplied)
    {
        if(expectedTuner.getTunerController() != controller)
        {
            return;
        }

        if(centerFrequencyFixedApplied)
        {
            rollbackStep("fixed center-frequency state",
                () -> controller.setCenterFrequencyLocked(centerFrequencyFixed));
        }
        if(autoPpmApplied)
        {
            rollbackStep("automatic frequency correction",
                () -> controller.getTunerFrequencyErrorManager().setEnabled(autoPpm));
        }
        if(extentsApplied)
        {
            //Restore the old legal range before the old center. The requested range may exclude that center, which
            //would otherwise make rollback fail and leave live hardware inconsistent with the saved configuration.
            rollbackStep("frequency limits", () -> controller.setFrequencyExtents(minimumFrequency, maximumFrequency));
        }
        if(centerFrequencyApplied)
        {
            rollbackStep("center frequency", () -> controller.setFrequency(centerFrequency));
        }
        if(ppmApplied)
        {
            rollbackStep("frequency correction", () -> controller.setFrequencyCorrection(frequencyCorrection));
        }
        if(deviceAttempted)
        {
            rollbackStep("device settings", deviceChange.restoreHardware());
        }
    }

    private static void rollbackStep(String setting, CheckedOperation operation)
    {
        try
        {
            operation.run();
        }
        catch(Exception | LinkageError rollbackFailure)
        {
            mLog.warn("Unable to restore receiver [{}] after a failed web settings command", setting, rollbackFailure);
        }
    }

    private DeviceChange validateDevice(DiscoveredTuner discoveredTuner, TunerConfiguration configuration,
                                        TunerController controller,
                                        UpdateRequest request)
    {
        if(configuration instanceof AirspyTunerConfiguration airspy &&
            controller instanceof AirspyTunerController airspyController)
        {
            return validateAirspy(discoveredTuner, airspy, airspyController, request);
        }

        if(configuration instanceof R8xTunerConfiguration rtl &&
            controller instanceof RTL2832TunerController rtlController &&
            rtlController.getEmbeddedTuner() instanceof R8xEmbeddedTuner embeddedTuner)
        {
            return validateR8x(rtl, rtlController, embeddedTuner, request);
        }

        throw error(409, "settings_not_supported",
            "Detailed settings are not available for this receiver type yet.");
    }

    private SavedDeviceChange validateDisabledDevice(DiscoveredTuner discoveredTuner,
                                                       TunerConfiguration configuration, UpdateRequest request)
    {
        if(configuration instanceof AirspyTunerConfiguration airspy)
        {
            requireDeviceType(request, DEVICE_AIRSPY);
            int sampleRateHz = requirePositive(request.sampleRateHz(), "A sample rate is required.");
            boolean supportedRate = airspyRates(discoveredTuner, airspy, null).stream()
                .anyMatch(rate -> rate.value() == sampleRateHz);

            if(!supportedRate)
            {
                throw invalid("Choose an Airspy sample rate discovered while this receiver was running.");
            }

            GainMode gainMode = enumValue(GainMode.class, request.airspyGainMode(),
                "Choose an Airspy gain mode.");
            int gainValue = requireRange(request.airspyGain(), AirspyTunerController.GAIN_MIN,
                AirspyTunerController.GAIN_MAX, "Airspy gain");
            int ifGain = requireRange(request.airspyIfGain(), AirspyTunerController.IF_GAIN_MIN,
                AirspyTunerController.IF_GAIN_MAX, "Airspy IF gain");
            int mixerGain = requireRange(request.airspyMixerGain(), AirspyTunerController.MIXER_GAIN_MIN,
                AirspyTunerController.MIXER_GAIN_MAX, "Airspy mixer gain");
            int lnaGain = requireRange(request.airspyLnaGain(), AirspyTunerController.LNA_GAIN_MIN,
                AirspyTunerController.LNA_GAIN_MAX, "Airspy LNA gain");
            boolean mixerAgc = requireBoolean(request.airspyMixerAgc(), "Airspy mixer AGC is required.");
            boolean lnaAgc = requireBoolean(request.airspyLnaAgc(), "Airspy LNA AGC is required.");
            Gain gain = Gain.getGain(gainMode, gainValue);

            return new SavedDeviceChange(sampleRateHz, () ->
            {
                airspy.setSampleRate(sampleRateHz);
                rememberAirspyPresetGain(discoveredTuner, airspy.getGain(), gainMode, gainValue);
                airspy.setGain(gain);
                airspy.setIFGain(ifGain);
                airspy.setMixerGain(mixerGain);
                airspy.setLNAGain(lnaGain);
                airspy.setMixerAGC(mixerAgc);
                airspy.setLNAAGC(lnaAgc);
            });
        }

        if(configuration instanceof R8xTunerConfiguration rtl)
        {
            requireDeviceType(request, DEVICE_R8X);
            int sampleRateHz = requirePositive(request.sampleRateHz(), "A sample rate is required.");
            RTL2832TunerController.SampleRate sampleRate = enumForRate(sampleRateHz);
            boolean biasT = requireBoolean(request.rtlBiasT(), "Bias-T state is required.");
            R8xEmbeddedTuner.MasterGain masterGain = enumValue(R8xEmbeddedTuner.MasterGain.class,
                request.rtlMasterGain(), "Choose an RTL-SDR master gain.");
            R8xEmbeddedTuner.MixerGain mixerGain = enumValue(R8xEmbeddedTuner.MixerGain.class,
                request.rtlMixerGain(), "Choose an RTL-SDR mixer gain.");
            R8xEmbeddedTuner.LNAGain lnaGain = enumValue(R8xEmbeddedTuner.LNAGain.class,
                request.rtlLnaGain(), "Choose an RTL-SDR LNA gain.");
            R8xEmbeddedTuner.VGAGain vgaGain = enumValue(R8xEmbeddedTuner.VGAGain.class,
                request.rtlVgaGain(), "Choose an RTL-SDR VGA gain.");

            return new SavedDeviceChange(sampleRateHz, () ->
            {
                rtl.setSampleRate(sampleRate);
                rtl.setBiasT(biasT);
                rtl.setMasterGain(masterGain);
                rtl.setMixerGain(mixerGain);
                rtl.setLNAGain(lnaGain);
                rtl.setVGAGain(vgaGain);
            });
        }

        throw error(409, "settings_not_supported",
            "Detailed settings are not available for this receiver type yet.");
    }

    private DeviceChange validateAirspy(DiscoveredTuner discoveredTuner, AirspyTunerConfiguration configuration,
                                        AirspyTunerController controller, UpdateRequest request)
    {
        requireDeviceType(request, DEVICE_AIRSPY);
        int sampleRateHz = requirePositive(request.sampleRateHz(), "A sample rate is required.");
        AirspySampleRate sampleRate = controller.getSampleRates().stream()
            .filter(rate -> rate.getRate() == sampleRateHz).findFirst()
            .orElseThrow(() -> invalid("Choose a sample rate supported by this Airspy."));
        GainMode gainMode = enumValue(GainMode.class, request.airspyGainMode(), "Choose an Airspy gain mode.");
        int gainValue = requireRange(request.airspyGain(), AirspyTunerController.GAIN_MIN,
            AirspyTunerController.GAIN_MAX, "Airspy gain");
        int ifGain = requireRange(request.airspyIfGain(), AirspyTunerController.IF_GAIN_MIN,
            AirspyTunerController.IF_GAIN_MAX, "Airspy IF gain");
        int mixerGain = requireRange(request.airspyMixerGain(), AirspyTunerController.MIXER_GAIN_MIN,
            AirspyTunerController.MIXER_GAIN_MAX, "Airspy mixer gain");
        int lnaGain = requireRange(request.airspyLnaGain(), AirspyTunerController.LNA_GAIN_MIN,
            AirspyTunerController.LNA_GAIN_MAX, "Airspy LNA gain");
        boolean mixerAgc = requireBoolean(request.airspyMixerAgc(), "Airspy mixer AGC is required.");
        boolean lnaAgc = requireBoolean(request.airspyLnaAgc(), "Airspy LNA AGC is required.");
        Gain gain = Gain.getGain(gainMode, gainValue);
        AirspySampleRate originalSampleRate = controller.getSampleRates().stream()
            .filter(rate -> rate.getRate() == (int)Math.round(controller.getCurrentSampleRate())).findFirst()
            .orElseThrow(() -> error(409, "settings_unavailable",
                "The receiver’s current sample rate is unavailable. Re-enable it and try again."));
        boolean sampleRateChanged = originalSampleRate.getRate() != sampleRateHz;
        boolean gainChanged = configuration.getGain() != gain || configuration.getIFGain() != ifGain ||
            configuration.getMixerGain() != mixerGain || configuration.getLNAGain() != lnaGain ||
            configuration.isMixerAGC() != mixerAgc || configuration.isLNAAGC() != lnaAgc;
        Gain originalGain = configuration.getGain();
        int originalIfGain = configuration.getIFGain();
        int originalMixerGain = configuration.getMixerGain();
        int originalLnaGain = configuration.getLNAGain();
        boolean originalMixerAgc = configuration.isMixerAGC();
        boolean originalLnaAgc = configuration.isLNAAGC();

        return new DeviceChange(sampleRateHz, sampleRateChanged, () ->
        {
            if(sampleRateChanged)
            {
                controller.setSampleRate(sampleRate);
            }

            if(gainChanged)
            {
                applyAirspyGain(controller, gain, ifGain, mixerGain, lnaGain, mixerAgc, lnaAgc);
            }
        }, () ->
        {
            if(sampleRateChanged)
            {
                controller.setSampleRate(originalSampleRate);
            }

            if(gainChanged)
            {
                applyAirspyGain(controller, originalGain, originalIfGain, originalMixerGain, originalLnaGain,
                    originalMixerAgc, originalLnaAgc);
            }
        }, () ->
        {
            configuration.setSampleRate(sampleRateHz);
            rememberAirspyPresetGain(discoveredTuner, configuration.getGain(), gainMode, gainValue);
            configuration.setGain(gain);
            configuration.setIFGain(ifGain);
            configuration.setMixerGain(mixerGain);
            configuration.setLNAGain(lnaGain);
            configuration.setMixerAGC(mixerAgc);
            configuration.setLNAAGC(lnaAgc);
        });
    }

    private void rememberAirspyPresetGain(DiscoveredTuner discoveredTuner, Gain currentGain, GainMode requestedMode,
                                           int requestedValue)
    {
        if(currentGain != null && currentGain.getGainMode() != GainMode.CUSTOM)
        {
            mAirspyPresetGains.put(discoveredTuner, currentGain.getValue());
        }

        if(requestedMode != GainMode.CUSTOM)
        {
            mAirspyPresetGains.put(discoveredTuner, requestedValue);
        }
    }

    private int airspyPresetGain(DiscoveredTuner discoveredTuner, AirspyTunerConfiguration configuration)
    {
        Gain gain = configuration.getGain();

        if(gain != null && gain.getGainMode() != GainMode.CUSTOM)
        {
            mAirspyPresetGains.put(discoveredTuner, gain.getValue());
            return gain.getValue();
        }

        return mAirspyPresetGains.getOrDefault(discoveredTuner,
            AirspyTunerController.LINEARITY_GAIN_DEFAULT.getValue());
    }

    private static void applyAirspyGain(AirspyTunerController controller, Gain gain, int ifGain, int mixerGain,
                                        int lnaGain, boolean mixerAgc, boolean lnaAgc) throws Exception
    {
        if(gain.getGainMode() == GainMode.CUSTOM)
        {
            controller.setIFGain(ifGain);
            controller.setMixerGain(mixerGain);
            controller.setLNAGain(lnaGain);
            controller.setMixerAGC(mixerAgc);
            controller.setLNAAGC(lnaAgc);
        }
        else
        {
            controller.setGain(gain);
        }
    }

    private DeviceChange validateR8x(R8xTunerConfiguration configuration,
                                     RTL2832TunerController controller, R8xEmbeddedTuner embeddedTuner,
                                     UpdateRequest request)
    {
        requireDeviceType(request, DEVICE_R8X);
        int sampleRateHz = requirePositive(request.sampleRateHz(), "A sample rate is required.");
        RTL2832TunerController.SampleRate sampleRate = enumForRate(sampleRateHz);
        boolean biasT = requireBoolean(request.rtlBiasT(), "Bias-T state is required.");
        R8xEmbeddedTuner.MasterGain masterGain = enumValue(R8xEmbeddedTuner.MasterGain.class,
            request.rtlMasterGain(), "Choose an RTL-SDR master gain.");
        R8xEmbeddedTuner.MixerGain mixerGain = enumValue(R8xEmbeddedTuner.MixerGain.class,
            request.rtlMixerGain(), "Choose an RTL-SDR mixer gain.");
        R8xEmbeddedTuner.LNAGain lnaGain = enumValue(R8xEmbeddedTuner.LNAGain.class,
            request.rtlLnaGain(), "Choose an RTL-SDR LNA gain.");
        R8xEmbeddedTuner.VGAGain vgaGain = enumValue(R8xEmbeddedTuner.VGAGain.class,
            request.rtlVgaGain(), "Choose an RTL-SDR VGA gain.");
        boolean sampleRateChanged = configuration.getSampleRate() != sampleRate;
        boolean biasTChanged = configuration.isBiasT() != biasT;
        boolean gainChanged = configuration.getMasterGain() != masterGain ||
            configuration.getMixerGain() != mixerGain || configuration.getLNAGain() != lnaGain ||
            configuration.getVGAGain() != vgaGain;
        RTL2832TunerController.SampleRate originalSampleRate = configuration.getSampleRate();
        boolean originalBiasT = configuration.isBiasT();
        R8xEmbeddedTuner.MasterGain originalMasterGain = configuration.getMasterGain();
        R8xEmbeddedTuner.MixerGain originalMixerGain = configuration.getMixerGain();
        R8xEmbeddedTuner.LNAGain originalLnaGain = configuration.getLNAGain();
        R8xEmbeddedTuner.VGAGain originalVgaGain = configuration.getVGAGain();

        return new DeviceChange(sampleRateHz, sampleRateChanged || biasTChanged, () ->
        {
            if(sampleRateChanged)
            {
                controller.setSampleRate(sampleRate);
            }

            if(biasTChanged)
            {
                controller.setBiasT(biasT);
            }

            if(gainChanged)
            {
                applyR8xGain(embeddedTuner, masterGain, lnaGain, mixerGain, vgaGain);
            }
        }, () ->
        {
            if(sampleRateChanged)
            {
                controller.setSampleRate(originalSampleRate);
            }

            if(biasTChanged)
            {
                controller.setBiasT(originalBiasT);
            }

            if(gainChanged)
            {
                applyR8xGain(embeddedTuner, originalMasterGain, originalLnaGain, originalMixerGain, originalVgaGain);
            }
        }, () ->
        {
            configuration.setSampleRate(sampleRate);
            configuration.setBiasT(biasT);
            configuration.setMasterGain(masterGain);
            configuration.setMixerGain(mixerGain);
            configuration.setLNAGain(lnaGain);
            configuration.setVGAGain(vgaGain);
        });
    }

    private static void applyR8xGain(R8xEmbeddedTuner embeddedTuner, R8xEmbeddedTuner.MasterGain masterGain,
                                     R8xEmbeddedTuner.LNAGain lnaGain, R8xEmbeddedTuner.MixerGain mixerGain,
                                     R8xEmbeddedTuner.VGAGain vgaGain) throws Exception
    {
        if(masterGain == R8xEmbeddedTuner.MasterGain.MANUAL)
        {
            embeddedTuner.setGain(R8xEmbeddedTuner.MasterGain.MANUAL, true);
            embeddedTuner.setLNAGain(lnaGain, true);
            embeddedTuner.setMixerGain(mixerGain, true);
            embeddedTuner.setVGAGain(vgaGain, true);
        }
        else
        {
            embeddedTuner.setGain(masterGain, true);
        }
    }

    private Map<String,Object> snapshot(DiscoveredTuner discoveredTuner, String tunerId)
    {
        TunerConfiguration configuration = discoveredTuner.getTunerConfiguration();
        Tuner tuner = discoveredTuner.getTuner();
        TunerController controller = tuner != null ? tuner.getTunerController() : null;
        int activeChannels = activeChannelCount(tuner);
        boolean shutdownIncomplete = discoveredTuner.hasTuner() && discoveredTuner.isLifecycleQuiescing() &&
            (!discoveredTuner.isEnabled() || discoveredTuner.getTunerStatus() == TunerStatus.ERROR);
        boolean available = discoveredTuner.isEnabled() && !discoveredTuner.isLifecycleQuiescing() &&
            discoveredTuner.getTunerStatus().isAvailable() && controller != null;
        Map<String,Object> response = new LinkedHashMap<>();
        response.put("id", tunerId);
        response.put("revision", revision(discoveredTuner, configuration));
        response.put("enabled", discoveredTuner.isEnabled());
        response.put("available", available);
        response.put("lifecycleQuiescing", discoveredTuner.isLifecycleQuiescing());
        response.put("shutdownIncomplete", shutdownIncomplete);
        response.put("editable", configuration != null && supportedConfiguration(configuration) &&
            !shutdownIncomplete && (!discoveredTuner.isEnabled() ||
                available && supportedRuntime(configuration, controller)));
        response.put("activeChannelCount", activeChannels);
        response.put("radioWorkActive", activeChannels > 0 || controller != null && controller.isLockedSampleRate());

        if(configuration == null)
        {
            response.put("frequencyCorrectionPpm", null);
            response.put("centerFrequencyHz", null);
            response.put("autoPpm", null);
            response.put("minimumFrequencyHz", null);
            response.put("maximumFrequencyHz", null);
            response.put("centerFrequencyFixed", null);
            response.put("hardwareMinimumFrequencyHz", null);
            response.put("hardwareMaximumFrequencyHz", null);
            response.put("device", Map.of("type", DEVICE_UNSUPPORTED,
                "message", "This receiver does not have saved hardware settings yet."));
            return response;
        }

        response.put("frequencyCorrectionPpm", configuration.getFrequencyCorrection());
        response.put("centerFrequencyHz", available ? controller.getFrequency() : configuration.getFrequency());
        response.put("autoPpm", configuration.getAutoPPMCorrectionEnabled());
        response.put("minimumFrequencyHz", effectiveMinimum(configuration, controller));
        response.put("maximumFrequencyHz", effectiveMaximum(configuration, controller));
        response.put("centerFrequencyFixed", configuration.isCenterFrequencyLocked());
        response.put("hardwareMinimumFrequencyHz", hardwareMinimum(configuration));
        response.put("hardwareMaximumFrequencyHz", hardwareMaximum(configuration));
        response.put("device", deviceSnapshot(discoveredTuner, configuration, controller));
        return response;
    }

    private Map<String,Object> deviceSnapshot(DiscoveredTuner discoveredTuner, TunerConfiguration configuration,
                                               TunerController controller)
    {
        if(configuration instanceof AirspyTunerConfiguration airspy)
        {
            List<Map<String,Object>> sampleRates = new ArrayList<>();
            AirspyTunerController airspyController = controller instanceof AirspyTunerController candidate ?
                candidate : null;
            airspyRates(discoveredTuner, airspy, airspyController)
                .forEach(rate -> sampleRates.add(option(rate.value(), rate.label())));

            Map<String,Object> device = new LinkedHashMap<>();
            device.put("type", DEVICE_AIRSPY);
            device.put("sampleRateHz", airspy.getSampleRate());
            device.put("sampleRates", sampleRates);
            device.put("gainMode", airspy.getGain().getGainMode().name());
            device.put("gain", airspyPresetGain(discoveredTuner, airspy));
            device.put("ifGain", airspy.getIFGain());
            device.put("mixerGain", airspy.getMixerGain());
            device.put("lnaGain", airspy.getLNAGain());
            device.put("mixerAgc", airspy.isMixerAGC());
            device.put("lnaAgc", airspy.isLNAAGC());
            device.put("gainMinimum", AirspyTunerController.GAIN_MIN);
            device.put("gainMaximum", AirspyTunerController.GAIN_MAX);
            device.put("ifGainMinimum", AirspyTunerController.IF_GAIN_MIN);
            device.put("ifGainMaximum", AirspyTunerController.IF_GAIN_MAX);
            device.put("mixerGainMinimum", AirspyTunerController.MIXER_GAIN_MIN);
            device.put("mixerGainMaximum", AirspyTunerController.MIXER_GAIN_MAX);
            device.put("lnaGainMinimum", AirspyTunerController.LNA_GAIN_MIN);
            device.put("lnaGainMaximum", AirspyTunerController.LNA_GAIN_MAX);
            return device;
        }

        if(configuration instanceof R8xTunerConfiguration rtl)
        {
            Map<String,Object> device = new LinkedHashMap<>();
            device.put("type", DEVICE_R8X);
            device.put("sampleRateHz", rtl.getSampleRate().getRate());
            device.put("sampleRates", options(List.of(RTL2832TunerController.SampleRate.values()),
                rate -> rate.getRate(), RTL2832TunerController.SampleRate::getLabel));
            device.put("biasT", rtl.isBiasT());
            device.put("masterGain", rtl.getMasterGain().name());
            device.put("masterGains", enumOptions(R8xEmbeddedTuner.MasterGain.values()));
            device.put("mixerGain", rtl.getMixerGain().name());
            device.put("mixerGains", enumOptions(R8xEmbeddedTuner.MixerGain.values()));
            device.put("lnaGain", rtl.getLNAGain().name());
            device.put("lnaGains", enumOptions(R8xEmbeddedTuner.LNAGain.values()));
            device.put("vgaGain", rtl.getVGAGain().name());
            device.put("vgaGains", enumOptions(R8xEmbeddedTuner.VGAGain.values()));
            return device;
        }

        return Map.of("type", DEVICE_UNSUPPORTED,
            "message", "Detailed settings are not available for this receiver type yet.");
    }

    private List<AirspyRate> airspyRates(DiscoveredTuner discoveredTuner, AirspyTunerConfiguration configuration,
                                          AirspyTunerController controller)
    {
        LinkedHashMap<Integer,AirspyRate> rates = new LinkedHashMap<>();

        if(controller != null)
        {
            controller.getSampleRates().forEach(rate ->
                rates.put(rate.getRate(), new AirspyRate(rate.getRate(), rate.toString())));
        }
        else
        {
            configuration.getAvailableSampleRates().forEach(rate ->
                rates.put(rate, new AirspyRate(rate, formatRate(rate))));
            mAirspySampleRates.getOrDefault(discoveredTuner, List.of()).forEach(rate ->
                rates.put(rate.value(), rate));

            if(rates.isEmpty())
            {
                AIRSPY_FALLBACK_SAMPLE_RATES.forEach(rate -> rates.put(rate.value(), rate));
            }
        }

        rates.putIfAbsent(configuration.getSampleRate(),
            new AirspyRate(configuration.getSampleRate(), formatRate(configuration.getSampleRate())));
        List<AirspyRate> result = List.copyOf(rates.values());

        if(controller != null)
        {
            mAirspySampleRates.put(discoveredTuner, result);
        }

        return result;
    }

    private static boolean supportedConfiguration(TunerConfiguration configuration)
    {
        return configuration instanceof AirspyTunerConfiguration || configuration instanceof R8xTunerConfiguration;
    }

    private static boolean supportedRuntime(TunerConfiguration configuration, TunerController controller)
    {
        return configuration instanceof AirspyTunerConfiguration && controller instanceof AirspyTunerController ||
            configuration instanceof R8xTunerConfiguration && controller instanceof RTL2832TunerController;
    }

    private static long hardwareMinimum(TunerConfiguration configuration)
    {
        if(configuration instanceof AirspyTunerConfiguration)
        {
            return AirspyTunerController.MINIMUM_TUNABLE_FREQUENCY_HZ;
        }

        if(configuration instanceof R8xTunerConfiguration)
        {
            return R8xEmbeddedTuner.MINIMUM_TUNABLE_FREQUENCY_HZ;
        }

        return Math.max(0, configuration.getMinimumFrequency());
    }

    private static long hardwareMaximum(TunerConfiguration configuration)
    {
        if(configuration instanceof AirspyTunerConfiguration)
        {
            return AirspyTunerController.MAXIMUM_TUNABLE_FREQUENCY_HZ;
        }

        if(configuration instanceof R8xTunerConfiguration)
        {
            return R8xEmbeddedTuner.MAXIMUM_TUNABLE_FREQUENCY_HZ;
        }

        return Math.max(0, configuration.getMaximumFrequency());
    }

    private static long effectiveMinimum(TunerConfiguration configuration, TunerController controller)
    {
        return configuration.getMinimumFrequency() > 0 ? configuration.getMinimumFrequency() :
            controller != null ? controller.getMinimumFrequency() : hardwareMinimum(configuration);
    }

    private static long effectiveMaximum(TunerConfiguration configuration, TunerController controller)
    {
        return configuration.getMaximumFrequency() > 0 ? configuration.getMaximumFrequency() :
            controller != null ? controller.getMaximumFrequency() : hardwareMaximum(configuration);
    }

    private static int activeChannelCount(Tuner tuner)
    {
        if(tuner == null || tuner.getChannelSourceManager() == null)
        {
            return 0;
        }

        return Math.max(0, tuner.getChannelSourceManager().getTunerChannelCount());
    }

    private void validateCommon(UpdateRequest request)
    {
        if(request.frequencyCorrectionPpm() != null && (!Double.isFinite(request.frequencyCorrectionPpm()) ||
            request.frequencyCorrectionPpm() < MINIMUM_PPM || request.frequencyCorrectionPpm() > MAXIMUM_PPM ||
            request.frequencyCorrectionPpm() != Math.rint(request.frequencyCorrectionPpm())))
        {
            throw invalid("Manual frequency correction must be a whole number from -1000 to 1000 PPM.");
        }

        requireBoolean(request.autoPpm(), "Automatic PPM state is required.");
        requireBoolean(request.centerFrequencyFixed(), "Fixed center-frequency state is required.");

        if(request.minimumFrequencyHz() == null || request.maximumFrequencyHz() == null)
        {
            throw invalid("Minimum and maximum frequencies are required.");
        }

        if(request.minimumFrequencyHz() >= request.maximumFrequencyHz())
        {
            throw invalid("Minimum frequency must be lower than maximum frequency.");
        }
    }

    private static void requireDeviceType(UpdateRequest request, String expected)
    {
        if(!expected.equals(request.deviceType()))
        {
            throw invalid("The receiver settings form no longer matches this receiver.");
        }
    }

    private static RTL2832TunerController.SampleRate enumForRate(int rate)
    {
        for(RTL2832TunerController.SampleRate candidate: RTL2832TunerController.SampleRate.values())
        {
            if(candidate.getRate() == rate)
            {
                return candidate;
            }
        }

        throw invalid("Choose a supported RTL-SDR sample rate.");
    }

    private static int requirePositive(Integer value, String message)
    {
        if(value == null || value <= 0)
        {
            throw invalid(message);
        }

        return value;
    }

    private static int requireRange(Integer value, int minimum, int maximum, String label)
    {
        if(value == null || value < minimum || value > maximum)
        {
            throw invalid(label + " must be between " + minimum + " and " + maximum + ".");
        }

        return value;
    }

    private static boolean requireBoolean(Boolean value, String message)
    {
        if(value == null)
        {
            throw invalid(message);
        }

        return value;
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value, String message)
    {
        if(value == null || value.isBlank())
        {
            throw invalid(message);
        }

        try
        {
            return Enum.valueOf(type, value);
        }
        catch(IllegalArgumentException exception)
        {
            throw invalid(message);
        }
    }

    private DiscoveredTuner resolve(String tunerId)
    {
        if(tunerId == null || !tunerId.matches("TNR_[0-9A-Fa-f]{28}"))
        {
            throw error(404, "receiver_not_found", "That receiver is no longer available.");
        }

        return mTunerRegistry.findManagedTuner(tunerId)
            .orElseThrow(() -> error(404, "receiver_not_found", "That receiver is no longer available."));
    }

    private static String normalizeId(String tunerId)
    {
        return tunerId.strip().toUpperCase(Locale.ROOT);
    }

    private static TunerConfiguration requireConfiguration(DiscoveredTuner discoveredTuner)
    {
        TunerConfiguration configuration = discoveredTuner.getTunerConfiguration();

        if(configuration == null)
        {
            throw error(409, "settings_unavailable", "Settings are unavailable for this receiver.");
        }

        return configuration;
    }

    private long revision(DiscoveredTuner discoveredTuner, TunerConfiguration configuration)
    {
        StringBuilder value = new StringBuilder(256);
        value.append(runtimeGeneration(discoveredTuner)).append('|')
            .append(runtimeGeneration(discoveredTuner.getTuner())).append('|')
            .append(runtimeGeneration(configuration)).append('|')
            .append(discoveredTuner.isEnabled()).append('|').append(discoveredTuner.getTunerStatus());

        if(configuration == null)
        {
            value.append("|NO_CONFIGURATION");
        }
        else
        {
            value.append('|').append(configuration.getClass().getName()).append('|')
            .append(configuration.getAutoPPMCorrectionEnabled()).append('|')
            .append(configuration.getMinimumFrequency()).append('|').append(configuration.getMaximumFrequency())
            .append('|').append(configuration.isCenterFrequencyLocked());

            if(configuration instanceof AirspyTunerConfiguration airspy)
            {
                value.append('|').append(airspy.getSampleRate()).append('|').append(airspy.getGain()).append('|')
                    .append(airspy.getIFGain()).append('|').append(airspy.getMixerGain()).append('|')
                    .append(airspy.getLNAGain()).append('|').append(airspy.isMixerAGC()).append('|')
                    .append(airspy.isLNAAGC());
            }
            else if(configuration instanceof R8xTunerConfiguration rtl)
            {
                value.append('|').append(rtl.getSampleRate()).append('|').append(rtl.isBiasT()).append('|')
                    .append(rtl.getMasterGain()).append('|').append(rtl.getMixerGain()).append('|')
                    .append(rtl.getLNAGain()).append('|').append(rtl.getVGAGain());
            }
        }

        try
        {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.toString().getBytes(StandardCharsets.UTF_8));
            long revision = 0;

            for(int index = 0; index < Long.BYTES; index++)
            {
                revision = (revision << 8) | (digest[index] & 0xFFL);
            }

            //JSON browsers represent numbers as IEEE-754 doubles.  Keep the transient fingerprint exact in JavaScript.
            return revision & MAXIMUM_BROWSER_SAFE_REVISION;
        }
        catch(NoSuchAlgorithmException exception)
        {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private long runtimeGeneration(Object runtimeObject)
    {
        if(runtimeObject == null)
        {
            return 0;
        }

        Long existing = mRuntimeGenerations.get(runtimeObject);

        if(existing != null)
        {
            return existing;
        }

        long assigned = mNextRuntimeGeneration++ & MAXIMUM_BROWSER_SAFE_REVISION;

        if(assigned == 0)
        {
            assigned = 1;
            mNextRuntimeGeneration = 2;
        }

        mRuntimeGenerations.put(runtimeObject, assigned);
        return assigned;
    }

    private static Map<String,Object> option(Object value, String label)
    {
        return Map.of("value", value, "label", label);
    }

    private static List<Map<String,Object>> enumOptions(Enum<?>[] values)
    {
        List<Map<String,Object>> options = new ArrayList<>(values.length);

        for(Enum<?> value: values)
        {
            options.add(option(value.name(), value.toString()));
        }

        return options;
    }

    private static <T> List<Map<String,Object>> options(Collection<T> values,
                                                        java.util.function.Function<T,Object> value,
                                                        java.util.function.Function<T,String> label)
    {
        List<Map<String,Object>> options = new ArrayList<>(values.size());

        for(T item: values)
        {
            options.add(option(value.apply(item), label.apply(item)));
        }

        return options;
    }

    private static String formatRate(int rate)
    {
        return String.format(Locale.ROOT, "%.3f MHz", rate / 1_000_000.0);
    }

    private static void requireSession(BooleanSupplier sessionIsValid)
    {
        if(!sessionIsValid.getAsBoolean())
        {
            throw error(401, "authentication_required", "Administrator sign-in is required.");
        }
    }

    private void ensureAccepting()
    {
        if(!mAccepting.get())
        {
            throw unavailable();
        }
    }

    private <T> CompletableFuture<T> submit(Supplier<T> supplier)
    {
        if(!mAccepting.get())
        {
            return CompletableFuture.failedFuture(unavailable());
        }

        CommandTask<T> task = new CommandTask<>(supplier);
        mCommands.add(task);

        try
        {
            mExecutor.execute(task);
        }
        catch(RejectedExecutionException exception)
        {
            mCommands.remove(task);
            task.future().completeExceptionally(unavailable());
        }

        return task.future();
    }

    @Override
    public void close()
    {
        if(mAccepting.compareAndSet(true, false))
        {
            mExecutor.shutdownNow();
            TunerSettingsException unavailable = unavailable();
            mCommands.forEach(command -> command.future().completeExceptionally(unavailable));
            mCommands.clear();
        }

        try
        {
            if(!mExecutor.awaitTermination(mShutdownTimeoutNanos, TimeUnit.NANOSECONDS))
            {
                throw new IllegalStateException("Web tuner control worker did not stop within its timeout; " +
                    "refusing an overlapping restart");
            }
        }
        catch(InterruptedException exception)
        {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while stopping the web tuner control worker", exception);
        }
    }

    private final class CommandTask<T> implements Runnable
    {
        private final Supplier<T> mSupplier;
        private final CompletableFuture<T> mFuture = new CompletableFuture<>();

        private CommandTask(Supplier<T> supplier)
        {
            mSupplier = Objects.requireNonNull(supplier, "Tuner command cannot be null");
        }

        @Override
        public void run()
        {
            try
            {
                if(!mFuture.isDone())
                {
                    mFuture.complete(mSupplier.get());
                }
            }
            catch(Throwable throwable)
            {
                mFuture.completeExceptionally(throwable);
            }
            finally
            {
                mCommands.remove(this);
            }
        }

        private CompletableFuture<T> future()
        {
            return mFuture;
        }
    }

    @FunctionalInterface
    private interface CheckedOperation
    {
        void run() throws Exception;
    }

    private record DeviceChange(int sampleRateHz, boolean requiresIdle, CheckedOperation applyHardware,
                                CheckedOperation restoreHardware, CheckedOperation updateConfiguration)
    {
    }

    private record SavedDeviceChange(int sampleRateHz, Runnable updateConfiguration)
    {
    }

    private record AirspyRate(int value, String label)
    {
    }

    public record UpdateRequest(Long revision, Double frequencyCorrectionPpm, Boolean autoPpm,
                                Long centerFrequencyHz, Long minimumFrequencyHz, Long maximumFrequencyHz,
                                Boolean centerFrequencyFixed,
                                String deviceType, Integer sampleRateHz,
                                String airspyGainMode, Integer airspyGain, Integer airspyIfGain,
                                Integer airspyMixerGain, Integer airspyLnaGain, Boolean airspyMixerAgc,
                                Boolean airspyLnaAgc, Boolean rtlBiasT, String rtlMasterGain,
                                String rtlMixerGain, String rtlLnaGain, String rtlVgaGain)
    {
    }

    public record EnabledRequest(Long revision, Boolean enabled, Boolean confirmActiveStop)
    {
    }

    public static final class TunerSettingsException extends RuntimeException
    {
        private final int mStatus;
        private final String mCode;

        private TunerSettingsException(int status, String code, String message)
        {
            super(message);
            mStatus = status;
            mCode = Objects.requireNonNull(code, "Tuner settings error code cannot be null");
        }

        public int status()
        {
            return mStatus;
        }

        public String code()
        {
            return mCode;
        }
    }

    private static TunerSettingsException invalid(String message)
    {
        return error(400, "invalid_settings", message);
    }

    private static TunerSettingsException unavailable()
    {
        return error(503, "settings_busy", "Receiver settings are busy. Try again in a moment.");
    }

    private static TunerSettingsException error(int status, String code, String message)
    {
        return new TunerSettingsException(status, code, message);
    }
}
