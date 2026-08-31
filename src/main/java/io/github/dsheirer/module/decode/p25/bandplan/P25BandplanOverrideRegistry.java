/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */

package io.github.dsheirer.module.decode.p25.bandplan;

import io.github.dsheirer.database.settings.ApplicationSettingsStore;
import io.github.dsheirer.module.decode.p25.P25SiteIdentity;
import io.github.dsheirer.module.decode.p25.phase1.message.IFrequencyBand;
import java.io.IOException;
import java.nio.file.Files;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Receiver-wide P25 bandplan override settings and immutable runtime lookup.
 */
public class P25BandplanOverrideRegistry
{
    private static final int MAXIMUM_PROFILE_COUNT = 256;
    private static final Logger mLog = LoggerFactory.getLogger(P25BandplanOverrideRegistry.class);
    private final ApplicationSettingsStore mSettingsStore;
    private final CopyOnWriteArrayList<Runnable> mChangeListeners = new CopyOnWriteArrayList<>();
    private volatile Snapshot mSnapshot = Snapshot.empty();

    public P25BandplanOverrideRegistry(ApplicationSettingsStore settingsStore)
    {
        mSettingsStore = settingsStore;

        if(settingsStore != null && Files.isRegularFile(settingsStore.getDatabasePath()))
        {
            try
            {
                P25BandplanOverrideProfile[] profiles = settingsStore.load(
                    ApplicationSettingsStore.P25_BANDPLAN_OVERRIDES, P25BandplanOverrideProfile[].class)
                    .orElseGet(() -> new P25BandplanOverrideProfile[0]);
                mSnapshot = Snapshot.create(List.of(profiles));
            }
            catch(IOException | SQLException | IllegalArgumentException e)
            {
                mLog.error("Unable to load P25 bandplan overrides", e);
            }
        }
    }

    private P25BandplanOverrideRegistry(List<P25BandplanOverrideProfile> profiles)
    {
        mSettingsStore = null;
        mSnapshot = Snapshot.create(profiles);
    }

    public static P25BandplanOverrideRegistry empty()
    {
        return new P25BandplanOverrideRegistry(List.of());
    }

    /**
     * Creates an in-memory registry for tests and tools that do not own application settings.
     */
    public static P25BandplanOverrideRegistry of(List<P25BandplanOverrideProfile> profiles)
    {
        return new P25BandplanOverrideRegistry(profiles);
    }

    public List<P25BandplanOverrideProfile> getProfiles()
    {
        return mSnapshot.profiles();
    }

    /**
     * Validates and persists one complete replacement document before publishing it to running decoders.
     */
    public synchronized void setProfiles(List<P25BandplanOverrideProfile> profiles) throws IOException, SQLException
    {
        Snapshot replacement = Snapshot.create(profiles);

        if(mSettingsStore == null)
        {
            throw new IllegalStateException("This P25 bandplan override registry has no application settings store");
        }

        mSettingsStore.save(ApplicationSettingsStore.P25_BANDPLAN_OVERRIDES, replacement.profiles());
        mSnapshot = replacement;
        notifyChangeListeners();
    }

    /** Adds a lightweight observer for successful administrator changes. */
    public void addChangeListener(Runnable listener)
    {
        if(listener != null)
        {
            mChangeListeners.addIfAbsent(listener);
        }
    }

    public void removeChangeListener(Runnable listener)
    {
        mChangeListeners.remove(listener);
    }

    private void notifyChangeListeners()
    {
        for(Runnable listener: mChangeListeners)
        {
            try
            {
                listener.run();
            }
            catch(RuntimeException exception)
            {
                mLog.warn("P25 bandplan override change listener failed", exception);
            }
        }
    }

    public Optional<P25BandplanOverrideProfile> find(P25SiteIdentity identity)
    {
        if(identity == null)
        {
            return Optional.empty();
        }

        return find(identity.wacn(), identity.system(), identity.rfss(), identity.site());
    }

    public Optional<P25BandplanOverrideProfile> find(int wacn, int system, Integer rfss, Integer site)
    {
        return Optional.ofNullable(mSnapshot.find(wacn, system, rfss, site)).map(ResolvedProfile::profile);
    }

    public boolean hasMatch(P25SiteIdentity identity)
    {
        return find(identity).isPresent();
    }

    public boolean hasMatch(int wacn, int system, Integer rfss, Integer site)
    {
        return find(wacn, system, rfss, site).isPresent();
    }

    public Map<Integer,IFrequencyBand> getFrequencyBands(P25SiteIdentity identity)
    {
        if(identity == null)
        {
            return Map.of();
        }

        return getFrequencyBands(identity.wacn(), identity.system(), identity.rfss(), identity.site());
    }

    public Map<Integer,IFrequencyBand> getFrequencyBands(int wacn, int system, Integer rfss, Integer site)
    {
        ResolvedProfile profile = mSnapshot.find(wacn, system, rfss, site);
        return profile != null ? profile.frequencyBands() : Map.of();
    }

    private record ProfileKey(int wacn, int system, Integer rfss, Integer site)
    {
        private static ProfileKey system(int wacn, int system)
        {
            return new ProfileKey(wacn, system, null, null);
        }
    }

    private record ResolvedProfile(P25BandplanOverrideProfile profile, Map<Integer,IFrequencyBand> frequencyBands)
    {
        private static ResolvedProfile create(P25BandplanOverrideProfile profile)
        {
            Map<Integer,IFrequencyBand> frequencyBands = new LinkedHashMap<>();

            for(P25BandplanOverrideBand band: profile.bands())
            {
                frequencyBands.put(band.identifier(), band.toFrequencyBand());
            }

            return new ResolvedProfile(profile, Map.copyOf(frequencyBands));
        }
    }

    private record Snapshot(List<P25BandplanOverrideProfile> profiles,
                            Map<ProfileKey,ResolvedProfile> profilesByKey)
    {
        private static Snapshot empty()
        {
            return new Snapshot(List.of(), Map.of());
        }

        private static Snapshot create(List<P25BandplanOverrideProfile> profiles)
        {
            if(profiles == null)
            {
                throw new IllegalArgumentException("P25 bandplan overrides cannot be null");
            }

            if(profiles.size() > MAXIMUM_PROFILE_COUNT)
            {
                throw new IllegalArgumentException("P25 bandplan overrides cannot contain more than " +
                    MAXIMUM_PROFILE_COUNT + " profiles");
            }

            List<P25BandplanOverrideProfile> ordered = List.copyOf(new ArrayList<>(profiles));
            Map<ProfileKey,ResolvedProfile> profilesByKey = new LinkedHashMap<>();

            for(P25BandplanOverrideProfile profile: ordered)
            {
                if(profile == null)
                {
                    throw new IllegalArgumentException("P25 bandplan overrides cannot contain an empty profile");
                }

                ProfileKey key = new ProfileKey(profile.wacn(), profile.system(), profile.rfss(), profile.site());

                if(profilesByKey.putIfAbsent(key, ResolvedProfile.create(profile)) != null)
                {
                    throw new IllegalArgumentException("Duplicate P25 bandplan override profile for WACN " +
                        profile.wacn() + " System " + profile.system());
                }
            }

            return new Snapshot(ordered, Map.copyOf(profilesByKey));
        }

        private ResolvedProfile find(int wacn, int system, Integer rfss, Integer site)
        {
            if(rfss != null && site != null)
            {
                ResolvedProfile siteProfile = profilesByKey.get(new ProfileKey(wacn, system, rfss, site));

                if(siteProfile != null)
                {
                    return siteProfile;
                }
            }

            return profilesByKey.get(ProfileKey.system(wacn, system));
        }
    }
}
