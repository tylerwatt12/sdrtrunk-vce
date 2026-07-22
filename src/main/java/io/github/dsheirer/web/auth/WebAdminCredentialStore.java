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
 * Persists the one current browser-administrator credential in the existing application settings table.
 */
public final class WebAdminCredentialStore
{
    public static final String SETTING_KEY = "web.auth.v1";
    private final ApplicationSettingsStore mSettingsStore;

    public WebAdminCredentialStore(Path databasePath)
    {
        this(new ApplicationSettingsStore(databasePath));
    }

    WebAdminCredentialStore(ApplicationSettingsStore settingsStore)
    {
        mSettingsStore = Objects.requireNonNull(settingsStore, "Application settings store cannot be null");
    }

    public Optional<WebAdminCredential> load() throws IOException, SQLException
    {
        try
        {
            return mSettingsStore.load(SETTING_KEY, WebAdminCredential.class);
        }
        catch(JsonProcessingException | IllegalArgumentException | NullPointerException exception)
        {
            throw new UnreadableWebAdminCredentialException(exception);
        }
    }

    /**
     * Security-sensitive writes are immediate.  Credentials must never enter the delayed settings-write queue.
     */
    public synchronized void save(WebAdminCredential credential) throws IOException, SQLException
    {
        mSettingsStore.save(SETTING_KEY, Objects.requireNonNull(credential,
            "Web administrator credential cannot be null"));
    }
}
