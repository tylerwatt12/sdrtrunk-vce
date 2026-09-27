/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.web.tuner;

import io.github.dsheirer.source.tuner.Tuner;
import io.github.dsheirer.source.tuner.TunerType;
import io.github.dsheirer.source.tuner.configuration.TunerConfiguration;
import io.github.dsheirer.source.tuner.manager.DiscoveredRecordingTuner;
import io.github.dsheirer.source.tuner.manager.DiscoveredTuner;
import io.github.dsheirer.source.tuner.manager.TunerManager;
import io.github.dsheirer.source.tuner.manager.TunerSettingCatalog;
import io.github.dsheirer.source.tuner.manager.TunerSettingsService;
import io.github.dsheirer.source.tuner.sdrplay.rspDuo.DiscoveredRspDuoTuner1;
import io.github.dsheirer.source.tuner.sdrplay.rspDuo.DiscoveredRspDuoTuner2;
import io.github.dsheirer.stats.TunerDiagnosticService;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/** Read-only projection of the receiver-owned tuner inventory for administrator HTTP requests. */
public final class TunerAdministrationService
{
    private final Supplier<List<DiscoveredTuner>> mInventory;
    private final Function<Tuner,String> mSpectrumTargetId;
    private final TunerSettingsService mSettings;
    private final Function<DiscoveredTuner,Object> mSettingsDescription;

    public TunerAdministrationService(TunerManager manager, TunerDiagnosticService diagnostics,
                                      TunerSettingsService settings)
    {
        this(Objects.requireNonNull(manager).getDiscoveredTunerRegistry()::snapshot,
            Objects.requireNonNull(diagnostics)::targetIdFor, Objects.requireNonNull(settings), settings::describe);
    }

    TunerAdministrationService(Supplier<List<DiscoveredTuner>> inventory,
                               Function<Tuner,String> spectrumTargetId)
    {
        this(inventory, spectrumTargetId, null, TunerSettingCatalog::describe);
    }

    TunerAdministrationService(Supplier<List<DiscoveredTuner>> inventory,
                               Function<Tuner,String> spectrumTargetId,
                               Function<DiscoveredTuner,Object> settingsDescription)
    {
        this(inventory, spectrumTargetId, null, settingsDescription);
    }

    private TunerAdministrationService(Supplier<List<DiscoveredTuner>> inventory,
                                       Function<Tuner,String> spectrumTargetId, TunerSettingsService settings,
                                       Function<DiscoveredTuner,Object> settingsDescription)
    {
        mInventory = Objects.requireNonNull(inventory);
        mSpectrumTargetId = Objects.requireNonNull(spectrumTargetId);
        mSettings = settings;
        mSettingsDescription = Objects.requireNonNull(settingsDescription);
    }

    public Snapshot snapshot()
    {
        List<Item> items = new ArrayList<>();
        for(DiscoveredTuner discovered: mInventory.get())
        {
            try
            {
                items.add(project(discovered));
            }
            catch(RuntimeException ignored)
            {
                // A hot-unplugged tuner may disappear between the registry copy and controller reads.
            }
        }
        return new Snapshot(List.copyOf(items));
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

    private Item project(DiscoveredTuner discovered)
    {
        Tuner tuner = discovered.hasTuner() ? discovered.getTuner() : null;
        TunerConfiguration configuration = discovered.getTunerConfiguration();
        TunerType tunerType = tuner != null ? tuner.getTunerType() :
            configuration != null ? configuration.getTunerType() : TunerType.UNKNOWN;
        boolean available = discovered.isAvailable() && tuner != null;
        int channelCount = available ? Math.max(0, tuner.getChannelSourceManager().getTunerChannelCount()) : 0;
        Long frequency = available ? tuner.getTunerController().getFrequency() : null;
        Long sampleRate = available ? Math.round(tuner.getTunerController().getSampleRate()) : null;
        String spectrumTargetId = available && channelCount > 0 ? mSpectrumTargetId.apply(tuner) : null;
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
        return new Item(opaqueId(discovered), deviceGroup(discovered), displayName(discovered, tuner),
            discovered.getTunerClass().name().toLowerCase(Locale.ROOT), tunerType.name().toLowerCase(Locale.ROOT),
            discovered.getTunerStatus().name().toLowerCase(Locale.ROOT), discovered.isEnabled(), available,
            channelCount, frequency, sampleRate, configuration != null ? configuration.getFrequency() : null,
            spectrumTargetId, spectrumTargetId != null, planner, settings,
            mSettings != null && mSettings.hasPending(discovered),
            maintenanceError);
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
    public record Item(String id, DeviceGroup deviceGroup, String name, String tunerClass, String tunerType,
                       String status, boolean enabled, boolean available, int channelCount, Long frequencyHz,
                       Long sampleRateHz, Long configuredFrequencyHz, String spectrumTargetId,
                       boolean spectrumAvailable, Planner planner, Object settings, boolean pending,
                       String maintenanceError) { }
}
