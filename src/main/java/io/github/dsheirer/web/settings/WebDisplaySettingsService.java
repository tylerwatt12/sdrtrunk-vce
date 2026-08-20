/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.web.settings;

import io.github.dsheirer.database.settings.ApplicationSettingsStore;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Objects;
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
    private volatile WebDisplayConfiguration mConfiguration;

    public WebDisplaySettingsService(Path databasePath)
    {
        this(new ApplicationSettingsStore(databasePath));
    }

    WebDisplaySettingsService(ApplicationSettingsStore store)
    {
        mStore = Objects.requireNonNull(store, "Application settings store cannot be null");
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

    /**
     * Saves before publishing so a failed write never changes the active configuration.
     */
    public synchronized WebDisplayConfiguration setShowEncryptionDetails(boolean show)
        throws IOException, SQLException
    {
        WebDisplayConfiguration updated = mConfiguration.withShowEncryptionDetails(show);
        mStore.save(KEY, updated);
        mConfiguration = updated;
        return updated;
    }
}
