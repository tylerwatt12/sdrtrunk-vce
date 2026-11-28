/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.application;

import io.github.dsheirer.gui.SDRTrunk;
import java.io.InputStream;
import java.net.URI;
import java.util.jar.Manifest;

/**
 * Read-only application identity derived from the packaged manifest.
 */
public final class ApplicationInfo
{
    private static final String PRODUCT_NAME = "VCE";
    private static final String VERSION = "Implementation-Version";
    private static final String BUILD_TIMESTAMP = "Build-Timestamp";
    private static final String UPDATE_TRACK = "Update-Track";
    private static final String UPDATE_BUILD = "Update-Build";
    private static final String BUILD_COMMIT = "Build-Commit";
    private static final Manifest MANIFEST = manifest();
    private static final String DISPLAY_NAME = createDisplayName();

    private ApplicationInfo()
    {
    }

    public static String getDisplayName()
    {
        return DISPLAY_NAME;
    }

    public static String getVersion()
    {
        return manifestValue(VERSION);
    }

    public static String getBuildTimestamp()
    {
        return manifestValue(BUILD_TIMESTAMP);
    }

    public static String getUpdateTrack()
    {
        return manifestValue(UPDATE_TRACK);
    }

    public static String getUpdateBuild()
    {
        return manifestValue(UPDATE_BUILD);
    }

    /**
     * The commit this build was made from (a nightly's), or null when not known.
     */
    public static String getBuildCommit()
    {
        return manifestValue(BUILD_COMMIT);
    }

    /**
     * The version as reports to services give it: a release's number, or for a nightly, which build:
     * nightly-20261128-4a00249 (its build date and commit), so a service can tell one nightly from another.
     */
    public static String getReportVersion()
    {
        return reportVersion(getVersion(), getBuildTimestamp(), getBuildCommit());
    }

    /**
     * @param version from the manifest (nightly for a nightly)
     * @param buildTimestamp from the manifest, starting yyyy-MM-dd, or null
     * @param commit the build's commit, or null or blank
     */
    static String reportVersion(String version, String buildTimestamp, String commit)
    {
        if(version == null || !version.contains("nightly"))
        {
            return version;
        }

        StringBuilder which = new StringBuilder(version);

        if(buildTimestamp != null && buildTimestamp.matches("\\d{4}-\\d{2}-\\d{2}.*"))
        {
            which.append('-').append(buildTimestamp.substring(0, 10).replace("-", ""));
        }

        if(commit != null && commit.matches("[0-9a-f]{7,40}"))
        {
            which.append('-').append(commit, 0, 7);
        }

        return which.toString();
    }

    private static String createDisplayName()
    {
        if(MANIFEST == null)
        {
            return PRODUCT_NAME;
        }

        String version = manifestValue(VERSION);

        if(version == null || version.isBlank())
        {
            return PRODUCT_NAME;
        }

        String timestamp = manifestValue(BUILD_TIMESTAMP);
        return version.contains("nightly") && timestamp != null ? PRODUCT_NAME + " nightly - " + timestamp :
            PRODUCT_NAME + " v" + version;
    }

    private static String manifestValue(String name)
    {
        return MANIFEST != null ? MANIFEST.getMainAttributes().getValue(name) : null;
    }

    private static Manifest manifest()
    {
        try
        {
            String resource = "/" + SDRTrunk.class.getName().replace(".", "/") + ".class";
            String fullPath = SDRTrunk.class.getResource(resource).toString();
            String archivePath = fullPath.substring(0, fullPath.length() - resource.length());

            if(archivePath.endsWith("/WEB-INF/classes") || archivePath.endsWith("\\WEB-INF\\classes"))
            {
                archivePath = archivePath.substring(0, archivePath.length() - "/WEB-INF/classes".length());
            }

            try(InputStream input = URI.create(archivePath + "/META-INF/MANIFEST.MF").toURL().openStream())
            {
                return new Manifest(input);
            }
        }
        catch(Exception e)
        {
            return null;
        }
    }
}
