/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.preference.location;

import io.github.dsheirer.preference.Preference;
import io.github.dsheirer.preference.PreferenceType;
import io.github.dsheirer.sample.Listener;
import java.util.Optional;
import java.util.prefs.Preferences;

/**
 * Portable receiver-location preference.  Both coordinates are stored as one value so a partially updated location
 * is never exposed to future lookup and import services.
 */
public class ReceiverLocationPreference extends Preference
{
    static final String PREFERENCE_KEY_RECEIVER_LOCATION = "receiver.location.coordinates";
    private final Preferences mPreferences;
    private boolean mLoaded;
    private ReceiverLocation mReceiverLocation;

    public ReceiverLocationPreference(Listener<PreferenceType> updateListener)
    {
        this(updateListener, Preferences.userNodeForPackage(ReceiverLocationPreference.class));
    }

    ReceiverLocationPreference(Listener<PreferenceType> updateListener, Preferences preferences)
    {
        super(updateListener);
        mPreferences = preferences;
    }

    @Override
    public PreferenceType getPreferenceType()
    {
        return PreferenceType.RECEIVER_LOCATION;
    }

    /**
     * Current receiver coordinates, or empty when the administrator has not configured them.
     */
    public synchronized Optional<ReceiverLocation> getReceiverLocation()
    {
        if(!mLoaded)
        {
            mReceiverLocation = parse(mPreferences.get(PREFERENCE_KEY_RECEIVER_LOCATION, null));
            mLoaded = true;
        }

        return Optional.ofNullable(mReceiverLocation);
    }

    /**
     * Saves one complete coordinate pair and publishes one preference update.
     */
    public synchronized void setReceiverLocation(ReceiverLocation receiverLocation)
    {
        if(receiverLocation == null)
        {
            clearReceiverLocation();
            return;
        }

        mReceiverLocation = receiverLocation;
        mLoaded = true;
        mPreferences.put(PREFERENCE_KEY_RECEIVER_LOCATION,
            Double.toString(receiverLocation.latitude()) + "," + Double.toString(receiverLocation.longitude()));
        notifyPreferenceUpdated();
    }

    /**
     * Removes the configured location and publishes one preference update.
     */
    public synchronized void clearReceiverLocation()
    {
        mReceiverLocation = null;
        mLoaded = true;
        mPreferences.remove(PREFERENCE_KEY_RECEIVER_LOCATION);
        notifyPreferenceUpdated();
    }

    private static ReceiverLocation parse(String stored)
    {
        if(stored == null || stored.isBlank())
        {
            return null;
        }

        String[] coordinates = stored.split(",", -1);

        if(coordinates.length != 2)
        {
            return null;
        }

        try
        {
            return new ReceiverLocation(Double.parseDouble(coordinates[0]),
                Double.parseDouble(coordinates[1]));
        }
        catch(IllegalArgumentException exception)
        {
            return null;
        }
    }
}
