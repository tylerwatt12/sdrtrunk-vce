/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.web.tuner;

import io.github.dsheirer.database.settings.ReceiverNicknameStore;
import io.github.dsheirer.source.tuner.Tuner;
import io.github.dsheirer.source.tuner.TunerClass;
import io.github.dsheirer.source.tuner.TunerType;
import io.github.dsheirer.source.tuner.configuration.TunerConfiguration;
import io.github.dsheirer.source.tuner.manager.DiscoveredRecordingTuner;
import io.github.dsheirer.source.tuner.manager.DiscoveredTuner;
import io.github.dsheirer.source.tuner.manager.TunerManager;
import io.github.dsheirer.source.tuner.manager.TunerSettingCatalog;
import io.github.dsheirer.source.tuner.manager.TunerSettingsService;
import io.github.dsheirer.source.tuner.manager.TunerStatus;
import io.github.dsheirer.source.tuner.recording.RecordingTunerController;
import io.github.dsheirer.source.tuner.sdrplay.rspDuo.DiscoveredRspDuoTuner1;
import io.github.dsheirer.source.tuner.sdrplay.rspDuo.DiscoveredRspDuoTuner2;
import io.github.dsheirer.stats.TunerDiagnosticService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/** Receiver-owned tuner inventory and independently persisted display nicknames. */
public final class TunerAdministrationService
{
    private final Supplier<List<DiscoveredTuner>> mInventory;
    private final Supplier<Function<Tuner,String>> mSpectrumTargets;
    private final TunerSettingsService mSettings;
    private final Function<DiscoveredTuner,Object> mSettingsDescription;
    private final ReceiverNicknameStore mNicknames;
    private volatile ReferenceSnapshot mReceiverReferences;

    public TunerAdministrationService(TunerManager manager, TunerDiagnosticService diagnostics,
                                      TunerSettingsService settings)
    {
        this(manager, diagnostics, settings, null);
    }

    public TunerAdministrationService(TunerManager manager, TunerDiagnosticService diagnostics,
                                      TunerSettingsService settings, ReceiverNicknameStore nicknames)
    {
        this(Objects.requireNonNull(manager).getDiscoveredTunerRegistry()::snapshot,
            () -> Objects.requireNonNull(diagnostics).targetIds()::get, Objects.requireNonNull(settings),
            settings::describe, nicknames);
    }

    TunerAdministrationService(Supplier<List<DiscoveredTuner>> inventory,
                               Function<Tuner,String> spectrumTargetId)
    {
        this(inventory, () -> spectrumTargetId, null, TunerSettingCatalog::describe);
    }

    TunerAdministrationService(Supplier<List<DiscoveredTuner>> inventory,
                               Function<Tuner,String> spectrumTargetId,
                               Function<DiscoveredTuner,Object> settingsDescription)
    {
        this(inventory, () -> spectrumTargetId, null, settingsDescription);
    }

    TunerAdministrationService(Supplier<List<DiscoveredTuner>> inventory,
                                       Supplier<Function<Tuner,String>> spectrumTargets, TunerSettingsService settings,
                                       Function<DiscoveredTuner,Object> settingsDescription)
    {
        this(inventory, spectrumTargets, settings, settingsDescription, null);
    }

    TunerAdministrationService(Supplier<List<DiscoveredTuner>> inventory,
                               Supplier<Function<Tuner,String>> spectrumTargets, TunerSettingsService settings,
                               Function<DiscoveredTuner,Object> settingsDescription, ReceiverNicknameStore nicknames)
    {
        mInventory = Objects.requireNonNull(inventory);
        mSpectrumTargets = Objects.requireNonNull(spectrumTargets);
        mSettings = settings;
        mSettingsDescription = Objects.requireNonNull(settingsDescription);
        mNicknames = nicknames;
    }

    public Snapshot snapshot()
    {
        List<Item> items = new ArrayList<>();
        Function<Tuner,String> spectrumTargets = mSpectrumTargets.get();
        for(DiscoveredTuner discovered: mInventory.get())
        {
            try
            {
                items.add(project(discovered, spectrumTargets));
            }
            catch(RuntimeException ignored)
            {
                // A hot-unplugged tuner may disappear between the registry copy and controller reads.
            }
        }
        return new Snapshot(List.copyOf(items));
    }

    public Item item(DiscoveredTuner tuner) { return project(Objects.requireNonNull(tuner), mSpectrumTargets.get()); }

    /** Only HTTP mutation workers write nicknames; a failed save leaves the published cache untouched. */
    public Nickname setNickname(DiscoveredTuner tuner, String nickname) throws IOException, SQLException
    {
        if(mNicknames == null)
        {
            throw new IllegalStateException("Receiver nicknames are unavailable");
        }
        String receiverId = opaqueId(Objects.requireNonNull(tuner));
        mNicknames.set(receiverId, nickname);
        return new Nickname(mNicknames.get(receiverId));
    }

    /**
     * Worker-side identity projection for Live. Reads the registry and immutable nickname cache only; it never
     * initializes hardware, reads tuner settings/controllers, or accesses the database. Native keys stay internal.
     */
    public Map<String,ReceiverReference> receiverReferences()
    {
        List<DiscoveredTuner> inventory = mInventory.get();
        Map<String,String> nicknames = mNicknames != null ? mNicknames.snapshot() : Map.of();
        ReferenceSnapshot cached = mReceiverReferences;
        if(cached != null && cached.inventory().equals(inventory) && cached.nicknames() == nicknames)
        {
            return cached.references();
        }
        Map<String,ReceiverReference> references = new LinkedHashMap<>();
        for(DiscoveredTuner discovered: inventory)
        {
            try
            {
                if(discovered instanceof DiscoveredRecordingTuner ||
                    discovered.getTunerClass() == TunerClass.RECORDING_TUNER)
                {
                    continue;
                }
                String nativeId = discovered.getId();
                if(nativeId == null || nativeId.isBlank()) continue;
                String id = opaqueId(discovered);
                references.put(nativeId, new ReceiverReference(id, nicknames.get(id)));
            }
            catch(RuntimeException ignored)
            {
                // A registry entry may retire during projection; a later worker pass repairs the view.
            }
        }
        Map<String,ReceiverReference> snapshot = Map.copyOf(references);
        mReceiverReferences = new ReferenceSnapshot(List.copyOf(inventory), nicknames, snapshot);
        return snapshot;
    }

    private record ReferenceSnapshot(List<DiscoveredTuner> inventory, Map<String,String> nicknames,
                                     Map<String,ReceiverReference> references) { }

    public record ReceiverReference(String id, String nickname) { }
    public record Nickname(String nickname) { }

    /**
     * Discovered physical SDR profiles for an external RF planner.  Disabled devices use their saved sample rates;
     * removed/error devices and recordings are not offered as working RF capacity.
     */
    public List<PlannerTarget> plannerTargets()
    {
        List<PlannerTarget> targets = new ArrayList<>();
        for(DiscoveredTuner discovered: mInventory.get())
        {
            try
            {
                TunerStatus status = discovered.getTunerStatus();
                if((status != TunerStatus.ENABLED && status != TunerStatus.DISABLED) ||
                    discovered.getTunerClass() == TunerClass.RECORDING_TUNER)
                {
                    continue;
                }
                Tuner tuner = discovered.isAvailable() && discovered.hasTuner() ? discovered.getTuner() : null;
                TunerConfiguration configuration = discovered.getTunerConfiguration();
                TunerType type = tuner != null ? tuner.getTunerType() :
                    configuration != null ? configuration.getTunerType() : TunerType.UNKNOWN;
                String model = TunerSettingCatalog.plannerModel(type);
                double sampleRate = tuner != null ? tuner.getTunerController().getSampleRate() : 0;
                long rateHz = Double.isFinite(sampleRate) ? Math.round(sampleRate) : 0;
                if(rateHz <= 0 && configuration != null)
                {
                    rateHz = configuration.getConfiguredSampleRate();
                }
                if(model != null && rateHz > 0)
                {
                    targets.add(new PlannerTarget(opaqueId(discovered), model, rateHz));
                }
            }
            catch(RuntimeException ignored)
            {
                // A hot-unplugged tuner may disappear while the request projects its current profile.
            }
        }
        return List.copyOf(targets);
    }

    /** Resolve a browser identifier only against the receiver's current registry snapshot. */
    public DiscoveredTuner find(String opaqueTunerId)
    {
        if(opaqueTunerId == null)
        {
            return null;
        }
        return mInventory.get().stream().filter(tuner -> opaqueTunerId.equals(opaqueId(tuner)))
            .findFirst().orElse(null);
    }

    private Item project(DiscoveredTuner discovered, Function<Tuner,String> spectrumTargets)
    {
        Tuner tuner = discovered.hasTuner() ? discovered.getTuner() : null;
        TunerConfiguration configuration = discovered.getTunerConfiguration();
        TunerType tunerType = tuner != null ? tuner.getTunerType() :
            configuration != null ? configuration.getTunerType() : TunerType.UNKNOWN;
        boolean available = discovered.isAvailable() && tuner != null;
        int channelCount = available ? Math.max(0, tuner.getChannelSourceManager().getTunerChannelCount()) : 0;
        Long frequency = available ? tuner.getTunerController().getFrequency() : null;
        Long sampleRate = available ? Math.round(tuner.getTunerController().getSampleRate()) : null;
        int measuredHz = available ? tuner.getTunerController().getMeasuredFrequencyError() : 0;
        MeasuredError measuredError = measuredHz != 0 && frequency != null && frequency > 0 ?
            new MeasuredError(measuredHz, measuredHz / (frequency / 1_000_000.0)) : null;
        boolean diagnosticAvailable = discovered.isAvailableForDiagnostics();
        RecordingTunerController.PlaybackStatus recordingPlayback = tuner != null &&
            tuner.getTunerController() instanceof RecordingTunerController recording ?
            recording.getPlaybackStatus() : tunerType == TunerType.RECORDING ?
            new RecordingTunerController.PlaybackStatus("stopped", 0, 0, 1_500) : null;
        String spectrumTargetId = diagnosticAvailable ? spectrumTargets.apply(tuner) : null;
        String plannerModel = TunerSettingCatalog.plannerModel(tunerType);
        Planner planner = plannerModel != null && sampleRate != null && sampleRate > 0 ?
            new Planner(plannerModel, sampleRate) : null;
        Object settings;
        String maintenanceError = mSettings != null ? mSettings.error(discovered) : null;
        try
        {
            settings = mSettingsDescription.apply(discovered);
        }
        catch(RuntimeException ignored)
        {
            // A device-specific settings provider must not hide the tuner or expose exception details to the browser.
            settings = List.of();
            maintenanceError = "Tuner settings are temporarily unavailable";
        }
        String receiverId = opaqueId(discovered);
        return new Item(receiverId, deviceGroup(discovered), displayName(discovered, tuner),
            discovered.getTunerClass().name().toLowerCase(Locale.ROOT), tunerType.name().toLowerCase(Locale.ROOT),
            discovered.getTunerStatus().name().toLowerCase(Locale.ROOT), available, channelCount, frequency,
            sampleRate, available ? (long)tuner.getTunerController().getUsableBandwidth() : null,
            available ? tuner.getTunerController().getMinimumFrequency() : null,
            available ? tuner.getTunerController().getMaximumFrequency() : null,
            configuration != null ? configuration.getFrequency() : null,
            configuration != null && configuration.getConfiguredSampleRate() > 0 ?
                (long)configuration.getConfiguredSampleRate() : null,
            measuredError,
            spectrumTargetId, spectrumTargetId != null, planner, settings,
            maintenanceError, discovered.getOperatorState().name().toLowerCase(Locale.ROOT),
            mSettings != null ? mSettings.transition(discovered) : null,
            mSettings != null ? mSettings.stoppedChannels(discovered) : List.of(),
            mSettings != null ? mSettings.restoreResult(discovered) : null,
            discovered.hasErrorMessage() ? "Tuner error" : null, recordingPlayback,
            mSettings != null ? mSettings.operation(discovered) : null,
            mNicknames != null ? mNicknames.get(receiverId) : "");
    }

    private static String displayName(DiscoveredTuner discovered, Tuner tuner)
    {
        if(discovered instanceof DiscoveredRecordingTuner recording)
        {
            String path = recording.getRecordingTunerConfiguration().getPath();
            try
            {
                Path fileName = path != null ? Path.of(path).getFileName() : null;
                return fileName != null ? fileName.toString() : "Recording";
            }
            catch(InvalidPathException ignored)
            {
                return "Recording";
            }
        }
        if(tuner != null && tuner.getPreferredName() != null && !tuner.getPreferredName().isBlank())
        {
            return tuner.getPreferredName();
        }
        return discovered.getId();
    }

    private static DeviceGroup deviceGroup(DiscoveredTuner discovered)
    {
        if(discovered instanceof DiscoveredRspDuoTuner1 first)
        {
            return new DeviceGroup(opaque("rsp-duo", first.getDeviceInfo().getSerialNumber()), "rsp_duo", "tuner_1");
        }
        if(discovered instanceof DiscoveredRspDuoTuner2 second)
        {
            return new DeviceGroup(opaque("rsp-duo", second.getDeviceInfo().getSerialNumber()), "rsp_duo", "tuner_2");
        }
        return null;
    }

    public static String opaqueId(DiscoveredTuner discovered)
    {
        String identity = discovered instanceof DiscoveredRecordingTuner recording &&
            recording.getRecordingTunerConfiguration().getUniqueID() != null ?
            recording.getRecordingTunerConfiguration().getUniqueID() : discovered.getId();
        return opaque(discovered.getTunerClass().name(), identity);
    }

    private static String opaque(String kind, String identity)
    {
        try
        {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest((kind + '\0' + identity).getBytes(StandardCharsets.UTF_8));
            return "tuner-" + HexFormat.of().formatHex(digest, 0, 16);
        }
        catch(NoSuchAlgorithmException exception)
        {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record Snapshot(List<Item> tuners) { }
    public record DeviceGroup(String id, String kind, String role) { }
    public record Planner(String model, long rateHz) { }
    public record PlannerTarget(String id, String model, long rateHz) { }
    public record MeasuredError(int hertz, double ppm) { }
    public record Item(String id, DeviceGroup deviceGroup, String name, String tunerClass, String tunerType,
                       String status, boolean available, int channelCount, Long frequencyHz, Long sampleRateHz,
                       Long usableBandwidthHz, Long minimumFrequencyHz, Long maximumFrequencyHz,
                       Long configuredFrequencyHz, Long configuredSampleRateHz,
                       MeasuredError measuredError,
                       String spectrumTargetId, boolean spectrumAvailable, Planner planner, Object settings,
                       String maintenanceError, String operatorState, String transition,
                       List<TunerSettingsService.ChannelInfo> stoppedChannels,
                       TunerSettingsService.RestoreResult restoreResult, String errorMessage,
                       RecordingTunerController.PlaybackStatus recordingPlayback,
                       TunerSettingsService.OperationStatus operation, String nickname) { }
}
