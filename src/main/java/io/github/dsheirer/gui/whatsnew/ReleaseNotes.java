/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.gui.whatsnew;

import io.github.dsheirer.application.ApplicationInfo;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads the rich-text release notes bundled with a numbered public build.
 */
public record ReleaseNotes(String version, String title, String html, boolean approved)
{
    private static final Logger mLog = LoggerFactory.getLogger(ReleaseNotes.class);
    private static final Pattern SAFE_VERSION = Pattern.compile("[A-Za-z0-9._-]+");
    private static final String DIRECTORY = "/release-notes/";

    /**
     * Loads the notes for the current packaged version when their human-approval marker is present.
     */
    public static Optional<ReleaseNotes> currentApproved()
    {
        return load(ApplicationInfo.getVersion()).filter(ReleaseNotes::approved);
    }

    /**
     * Loads release notes for the supplied version. Draft notes are returned so validation tests and release tooling
     * can inspect them, but callers that display notes to users should require {@link #approved()}.
     */
    static Optional<ReleaseNotes> load(String version)
    {
        if(!isPublicVersion(version))
        {
            return Optional.empty();
        }

        String metadataPath = DIRECTORY + version + ".properties";
        String htmlPath = DIRECTORY + version + ".html";

        try(InputStream metadataInput = ReleaseNotes.class.getResourceAsStream(metadataPath);
            InputStream htmlInput = ReleaseNotes.class.getResourceAsStream(htmlPath))
        {
            if(metadataInput == null || htmlInput == null)
            {
                return Optional.empty();
            }

            Properties metadata = new Properties();
            metadata.load(new InputStreamReader(metadataInput, StandardCharsets.UTF_8));
            String declaredVersion = metadata.getProperty("version", "").trim();
            String title = metadata.getProperty("title", "").trim();
            String status = metadata.getProperty("status", "").trim();

            if(!version.equals(declaredVersion) || title.isBlank())
            {
                mLog.error("Ignoring invalid release-note metadata [{}] for version [{}]", metadataPath, version);
                return Optional.empty();
            }

            String html = new String(htmlInput.readAllBytes(), StandardCharsets.UTF_8).trim();

            if(html.isBlank())
            {
                mLog.error("Ignoring empty release notes [{}]", htmlPath);
                return Optional.empty();
            }

            return Optional.of(new ReleaseNotes(version, title, html, "approved".equalsIgnoreCase(status)));
        }
        catch(IOException e)
        {
            mLog.error("Unable to load release notes for version [{}]", version, e);
            return Optional.empty();
        }
    }

    static boolean isPublicVersion(String version)
    {
        if(version == null || version.isBlank() || !SAFE_VERSION.matcher(version).matches())
        {
            return false;
        }

        String normalized = version.toLowerCase(Locale.US);
        return !normalized.contains("nightly") && !normalized.contains("snapshot") && !normalized.contains("dev");
    }

    static boolean shouldShow(String version, String lastShownVersion)
    {
        return version != null && !version.equals(lastShownVersion);
    }
}
