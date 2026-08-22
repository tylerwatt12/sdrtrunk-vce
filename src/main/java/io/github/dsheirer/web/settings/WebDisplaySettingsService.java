/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.web.settings;

import io.github.dsheirer.database.settings.ApplicationSettingsStore;
import io.github.dsheirer.preference.nowplaying.NowPlayingPreference;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Objects;
import java.util.prefs.BackingStoreException;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns and immediately persists the current receiver-wide browser display configuration.
 */
public final class WebDisplaySettingsService
{
    public static final String KEY = "web.display.v1";
    private static final Logger mLog = LoggerFactory.getLogger(WebDisplaySettingsService.class);
    private final ApplicationSettingsStore mStore;
    private final NowPlayingPreference mNowPlayingPreference;
    private volatile WebDisplayConfiguration mConfiguration;

    public WebDisplaySettingsService(Path databasePath, NowPlayingPreference nowPlayingPreference)
    {
        this(new ApplicationSettingsStore(databasePath), nowPlayingPreference);
    }

    WebDisplaySettingsService(ApplicationSettingsStore store, NowPlayingPreference nowPlayingPreference)
    {
        mStore = Objects.requireNonNull(store, "Application settings store cannot be null");
        mNowPlayingPreference = Objects.requireNonNull(nowPlayingPreference,
            "Now playing preference cannot be null");
        mConfiguration = load();
    }

    private WebDisplayConfiguration load()
    {
        try
        {
            return mStore.load(KEY, WebDisplayConfiguration.class).orElseGet(WebDisplayConfiguration::defaults);
        }
        catch(IOException | SQLException | IllegalArgumentException exception)
        {
            mLog.error("Unable to load web display settings from SQLite; using defaults", exception);
            return WebDisplayConfiguration.defaults();
        }
    }

    public WebDisplayConfiguration configuration()
    {
        return mConfiguration;
    }

    public synchronized WebDisplaySettings settings()
    {
        WebDisplayConfiguration display = mConfiguration;
        NowPlayingPreference.LiveActivitySettings live = mNowPlayingPreference.getLiveActivitySettings();
        return new WebDisplaySettings(WebDisplaySettings.CURRENT_FORMAT_VERSION, display.showEncryptionDetails(),
            live.retainIdleCallDetails(), live.showControlDecodeQuality(), live.showVoiceDecodeQuality(),
            live.clearVoiceDecodeQualityOnCallEnd(),
            live.decodeQualityDisplayMode().name().toLowerCase(Locale.ROOT),
            live.trafficGrantAgeOutMilliseconds(), live.liveDetailMatchingRowLimit());
    }

    /**
     * Applies one serialized batch. The standalone browser document is rolled back if the portable Live preference
     * batch cannot be persisted, so readers only observe the complete previous or complete updated policy.
     */
    public synchronized WebDisplaySettings update(UnaryOperator<WebDisplaySettings> operation)
        throws IOException, SQLException, BackingStoreException
    {
        Objects.requireNonNull(operation, "Web display settings update cannot be null");
        WebDisplaySettings updatedSettings = Objects.requireNonNull(operation.apply(settings()),
            "Web display settings cannot be null");
        WebDisplayConfiguration previousDisplay = mConfiguration;
        WebDisplayConfiguration updatedDisplay = previousDisplay.showEncryptionDetails() ==
            updatedSettings.showEncryptionDetails() ? previousDisplay :
            previousDisplay.withShowEncryptionDetails(updatedSettings.showEncryptionDetails());
        NowPlayingPreference.LiveActivitySettings previousLive = mNowPlayingPreference.getLiveActivitySettings();
        NowPlayingPreference.LiveActivitySettings updatedLive = new NowPlayingPreference.LiveActivitySettings(
            updatedSettings.retainIdleCallDetails(), updatedSettings.trafficGrantAgeOutMilliseconds(),
            updatedSettings.showControlDecodeQuality(), updatedSettings.showVoiceDecodeQuality(),
            updatedSettings.clearVoiceDecodeQualityOnCallEnd(),
            updatedSettings.parsedDecodeQualityDisplayMode(), updatedSettings.liveDetailMatchingRowLimit());
        boolean displaySaved = false;

        try
        {
            if(updatedDisplay != previousDisplay)
            {
                mStore.save(KEY, updatedDisplay);
                displaySaved = true;
            }

            if(!updatedLive.equals(previousLive))
            {
                mNowPlayingPreference.setLiveActivitySettings(updatedLive);
            }
        }
        catch(IOException | SQLException | BackingStoreException exception)
        {
            rollbackDisplay(displaySaved, previousDisplay, exception);
            throw exception;
        }
        catch(RuntimeException exception)
        {
            rollbackDisplay(displaySaved, previousDisplay, exception);
            throw new IOException("Unable to persist web display settings", exception);
        }

        mConfiguration = updatedDisplay;
        return settings();
    }

    private void rollbackDisplay(boolean displaySaved, WebDisplayConfiguration previousDisplay,
                                 Exception exception)
    {
        if(displaySaved)
        {
            try
            {
                mStore.save(KEY, previousDisplay);
            }
            catch(IOException | SQLException | RuntimeException rollbackException)
            {
                exception.addSuppressed(rollbackException);
            }
        }
    }
}
