/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.web.auth;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.github.dsheirer.database.settings.ApplicationSettingsStore;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;

/**
 * Persists the bounded current web identity and policy document in the existing application settings table.
 */
final class WebAccessStore
{
    private final ApplicationSettingsStore mSettingsStore;

    WebAccessStore(Path databasePath)
    {
        this(new ApplicationSettingsStore(databasePath));
    }

    WebAccessStore(ApplicationSettingsStore settingsStore)
    {
        mSettingsStore = Objects.requireNonNull(settingsStore, "Application settings store cannot be null");
    }

    Optional<WebAccessConfiguration> load() throws IOException, SQLException
    {
        try
        {
            return mSettingsStore.load(WebAccessService.KEY, WebAccessConfiguration.class);
        }
        catch(JsonProcessingException | IllegalArgumentException | NullPointerException exception)
        {
            throw new UnreadableWebAccessConfigurationException(exception);
        }
    }

    /**
     * Security-sensitive writes are immediate and never enter the delayed settings-write queue.
     */
    synchronized void save(WebAccessConfiguration configuration) throws IOException, SQLException
    {
        mSettingsStore.save(WebAccessService.KEY,
            Objects.requireNonNull(configuration, "Web access configuration cannot be null"));
    }
}
