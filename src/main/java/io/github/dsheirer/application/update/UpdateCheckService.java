/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.application.update;

import io.github.dsheirer.application.ApplicationInfo;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Checks the small update manifest published in the current build track.
 */
public class UpdateCheckService
{
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(8);
    private static final Map<String, URI> MANIFEST_URIS = Map.of(
        "alpha", URI.create("https://raw.githubusercontent.com/tylerwatt12/sdrtrunk-vce/" +
            "release/0.6.2-alpha/.github/update.properties"),
        "nightly", URI.create("https://github.com/tylerwatt12/sdrtrunk-vce/" +
            "releases/download/nightly/update.properties"));
    private final HttpClient mHttpClient;
    private final String mTrack;
    private final long mCurrentBuild;

    public UpdateCheckService()
    {
        this(HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).followRedirects(HttpClient.Redirect.NORMAL).build(),
            ApplicationInfo.getUpdateTrack(), parseBuild(ApplicationInfo.getUpdateBuild()));
    }

    UpdateCheckService(HttpClient httpClient, String track, long currentBuild)
    {
        mHttpClient = httpClient;
        mTrack = track;
        mCurrentBuild = currentBuild;
    }

    public String getTrack()
    {
        return mTrack;
    }

    public UpdateCheckResult check()
    {
        if(!MANIFEST_URIS.containsKey(mTrack) || mCurrentBuild < 0)
        {
            return UpdateCheckResult.unavailable("This build does not contain valid update metadata");
        }

        try
        {
            HttpRequest request = HttpRequest.newBuilder(manifestUri(mTrack))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "text/plain")
                .header("User-Agent", "sdrtrunk-vce-update-check")
                .GET()
                .build();
            HttpResponse<String> response = mHttpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if(response.statusCode() != 200)
            {
                return UpdateCheckResult.unavailable("Update server returned HTTP " + response.statusCode());
            }

            return evaluate(mTrack, mCurrentBuild, UpdateManifest.parse(response.body()));
        }
        catch(InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return UpdateCheckResult.unavailable("Update check was interrupted");
        }
        catch(Exception e)
        {
            return UpdateCheckResult.unavailable(e.getMessage());
        }
    }

    static UpdateCheckResult evaluate(String track, long currentBuild, UpdateManifest manifest)
    {
        if(!track.equals(manifest.track()))
        {
            return UpdateCheckResult.unavailable("Update manifest is for a different release channel");
        }

        return manifest.build() > currentBuild ?
            UpdateCheckResult.available(track, manifest.version(), manifest.releaseUri()) :
            UpdateCheckResult.current(track, manifest.version());
    }

    static URI manifestUri(String track)
    {
        URI manifestUri = MANIFEST_URIS.get(track);

        if(manifestUri == null)
        {
            throw new IllegalArgumentException("Unsupported update track: " + track);
        }

        return manifestUri;
    }

    private static long parseBuild(String build)
    {
        try
        {
            return build != null ? Long.parseLong(build) : -1;
        }
        catch(NumberFormatException e)
        {
            return -1;
        }
    }
}
