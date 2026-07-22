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
import java.util.Set;

/**
 * Checks the small update manifest published in the current build track.
 */
public class UpdateCheckService
{
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(8);
    private static final Set<String> TRACKS = Set.of("main", "webfirst");
    private static final String MANIFEST_URL =
        "https://raw.githubusercontent.com/tylerwatt12/sdrtrunk-vce/%s/.github/update.properties";
    private final HttpClient mHttpClient;
    private final String mTrack;
    private final int mCurrentBuild;

    public UpdateCheckService()
    {
        this(HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).followRedirects(HttpClient.Redirect.NORMAL).build(),
            ApplicationInfo.getUpdateTrack(), parseBuild(ApplicationInfo.getUpdateBuild()));
    }

    UpdateCheckService(HttpClient httpClient, String track, int currentBuild)
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
        if(!TRACKS.contains(mTrack) || mCurrentBuild < 0)
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

    static UpdateCheckResult evaluate(String track, int currentBuild, UpdateManifest manifest)
    {
        return manifest.build() > currentBuild ?
            UpdateCheckResult.available(track, manifest.version(), manifest.releaseUri()) :
            UpdateCheckResult.current(track, manifest.version());
    }

    static URI manifestUri(String track)
    {
        if(!TRACKS.contains(track))
        {
            throw new IllegalArgumentException("Unsupported update track: " + track);
        }

        return URI.create(MANIFEST_URL.formatted(track));
    }

    private static int parseBuild(String build)
    {
        try
        {
            return build != null ? Integer.parseInt(build) : -1;
        }
        catch(NumberFormatException e)
        {
            return -1;
        }
    }
}
