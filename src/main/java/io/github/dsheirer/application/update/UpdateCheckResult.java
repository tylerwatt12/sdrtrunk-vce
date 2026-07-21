/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.application.update;

import java.net.URI;

/**
 * Result of checking the published update manifest for this build's track.
 */
public record UpdateCheckResult(State state, String track, String version, URI releaseUri, String detail)
{
    public enum State
    {
        NOT_CHECKED,
        CURRENT,
        UPDATE_AVAILABLE,
        UNAVAILABLE
    }

    public static UpdateCheckResult notChecked()
    {
        return new UpdateCheckResult(State.NOT_CHECKED, null, null, null, null);
    }

    public static UpdateCheckResult current(String track, String version)
    {
        return new UpdateCheckResult(State.CURRENT, track, version, null, null);
    }

    public static UpdateCheckResult available(String track, String version, URI releaseUri)
    {
        return new UpdateCheckResult(State.UPDATE_AVAILABLE, track, version, releaseUri, null);
    }

    public static UpdateCheckResult unavailable(String detail)
    {
        return new UpdateCheckResult(State.UNAVAILABLE, null, null, null, detail);
    }

    public boolean isUpdateAvailable()
    {
        return state == State.UPDATE_AVAILABLE && releaseUri != null;
    }
}
