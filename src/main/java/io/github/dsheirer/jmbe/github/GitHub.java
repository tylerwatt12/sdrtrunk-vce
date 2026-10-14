/*
 * ******************************************************************************
 * Copyright (C) 2015-2020 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 * *****************************************************************************
 */

package io.github.dsheirer.jmbe.github;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;

/**
 * Utilities for accessing GitHub repositories and releases
 */
public class GitHub
{
    private static final Logger mLog = LoggerFactory.getLogger(GitHub.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration RELEASE_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration RELEASE_REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private GitHub()
    {
        //There's nothing to construct in this class
    }

    /**
     * Download an artifact to the specified directory
     * @param url of the artifact to download
     * @param directory to store the downloaded file
     * @return path to the downloaded artifact or null
     */
    public static Path downloadArtifact(String url, Path directory)
    {
        HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .connectTimeout(CONNECT_TIMEOUT)
            .build();

        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).timeout(REQUEST_TIMEOUT).build();

        try
        {
            HttpResponse<Path> response = client.send(request, HttpResponse.BodyHandlers.ofFileDownload(directory,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE));

            if(response.statusCode() >= 200 && response.statusCode() < 300)
            {
                return response.body();
            }

            mLog.error("HTTP Download Status Code: {}", response.statusCode());
            Files.deleteIfExists(response.body());

        }
        catch(InterruptedException e)
        {
            Thread.currentThread().interrupt();
            mLog.error("Error downloading source code from GitHub", e);
        }
        catch(Exception e)
        {
            mLog.error("Error downloading source code from GitHub", e);
        }

        return null;
    }

    /**
     * Obtain the latest published, stable release object from the repository.
     * @param repositoryURL for the GitHub api
     * @return the latest release or null
     */
    public static Release getLatestRelease(String repositoryURL)
    {
        if(repositoryURL == null)
        {
            return null;
        }

        try
        {
            HttpClient httpClient = HttpClient.newBuilder().connectTimeout(RELEASE_CONNECT_TIMEOUT).build();
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(repositoryURL))
                .timeout(RELEASE_REQUEST_TIMEOUT).build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if(response.statusCode() == 200)
            {
                return parseResponse(response.body());
            }
            else
            {
                mLog.error("Error while fetching latest releases - HTTP:" + response.statusCode());
            }
        }
        catch(InterruptedException e)
        {
            Thread.currentThread().interrupt();
            mLog.error("Error while detecting the current release version of JMBE library", e);
        }
        catch(IOException | IllegalArgumentException e)
        {
            mLog.error("Error while detecting the current release version of JMBE library", e);
        }

        return null;
    }

    /**
     * Parses a json string containing an array of GitHub release objects and returns the latest stable release.
     * @param json string returned from URL call
     * @return latest stable release, or null when no usable release was returned
     */
    static Release parseResponse(String json)
    {
        Release release = null;

        if(json != null)
        {
            try
            {
                JsonElement element = JsonParser.parseString(json);

                if(element.isJsonArray())
                {
                    JsonArray array = element.getAsJsonArray();

                    for(JsonElement child: array)
                    {
                        if(child.isJsonObject())
                        {
                            JsonObject releaseObject = child.getAsJsonObject();

                            if(isFalseOrMissing(releaseObject, "draft") &&
                                isFalseOrMissing(releaseObject, "prerelease"))
                            {
                                Version version = getVersion(releaseObject);

                                if(version != null && (release == null || release.getVersion().compareTo(version) < 0))
                                {
                                    release = new Release(version, releaseObject);
                                }
                            }
                        }
                    }
                }
            }
            catch(JsonParseException e)
            {
                mLog.error("Invalid response while detecting the current release version of JMBE library", e);
            }
        }

        return release;
    }

    /**
     * Missing flags are accepted for older release metadata; malformed flags are not considered stable.
     */
    private static boolean isFalseOrMissing(JsonObject release, String name)
    {
        JsonElement value = release.get(name);
        return value == null || (value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() &&
            !value.getAsBoolean());
    }

    /**
     * Extracts a version object from the tag_name.
     * @param jsonObject containing a tag_name formatted as: vAAAAA.BBBBB.CCCCx where:
     *  A = major, 0-99999
     *  B = minor, 0-99999
     *  C = release, 0-99999
     *  x = (optional)patch, a-z
     *
     * @return version instance or null
     */
    private static Version getVersion(JsonObject jsonObject)
    {
        JsonElement tagName = jsonObject.get("tag_name");

        if(tagName != null && tagName.isJsonPrimitive() && tagName.getAsJsonPrimitive().isString())
        {
            return Version.fromString(tagName.getAsString());
        }

        return null;
    }
}
