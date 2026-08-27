/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.web.auth;

import io.github.dsheirer.web.settings.WebUserPreferences;
import io.github.dsheirer.web.settings.WebUserPreferencesCodec;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Objects;

/** Signed-in-user preference service with optimistic revision updates. */
public final class WebUserPreferencesService
{
    private final WebUserRepository mUsers;

    public WebUserPreferencesService(Path databasePath)
    {
        mUsers = new WebUserRepository(databasePath);
    }

    WebUserPreferencesService(WebUserRepository users)
    {
        mUsers = Objects.requireNonNull(users, "Web user repository cannot be null");
    }

    public Snapshot get(WebAccessAccount account) throws IOException, SQLException
    {
        Objects.requireNonNull(account, "Authenticated web account cannot be null");
        WebUserRepository.PreferenceRow stored = mUsers.loadPreferences(account.id());
        if(stored.revision() < 1)
        {
            throw new IOException("Persisted web preference revision is invalid");
        }
        return new Snapshot(stored.revision(), WebUserPreferencesCodec.decode(stored.json()));
    }

    public Snapshot update(WebAccessAccount account, long expectedRevision, WebUserPreferences preferences)
        throws IOException, SQLException, RevisionConflictException
    {
        Objects.requireNonNull(account, "Authenticated web account cannot be null");
        Objects.requireNonNull(preferences, "Web user preferences cannot be null");
        if(expectedRevision < 1)
        {
            throw new IllegalArgumentException("Expected web preference revision must be positive");
        }

        String json = WebUserPreferencesCodec.encode(preferences);
        WebUserRepository.PreferenceUpdate result = mUsers.updatePreferences(account.id(), expectedRevision, json);
        if(!result.updated())
        {
            throw new RevisionConflictException(result.revision());
        }
        return new Snapshot(result.revision(), preferences);
    }

    public record Snapshot(long revision, WebUserPreferences preferences)
    {
        public Snapshot
        {
            if(revision < 1)
            {
                throw new IllegalArgumentException("Web preference revision must be positive");
            }
            Objects.requireNonNull(preferences, "Web user preferences cannot be null");
        }
    }

    public static final class RevisionConflictException extends Exception
    {
        private final long mCurrentRevision;

        private RevisionConflictException(long currentRevision)
        {
            super("Web preferences changed since they were loaded");
            mCurrentRevision = currentRevision;
        }

        public long currentRevision()
        {
            return mCurrentRevision;
        }
    }
}
