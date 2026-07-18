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
    private static final String PRODUCT_NAME = "sdrtrunk-vce";
    private static final String VERSION = "Implementation-Version";
    private static final String BUILD_TIMESTAMP = "Build-Timestamp";
    private static final String BUILD_JDK = "Build-JDK";
    private static final String BUILD_OS = "Build-OS";
    private static final Manifest MANIFEST = manifest();
    private static final String DISPLAY_NAME = createDisplayName();

    private ApplicationInfo()
    {
    }

    public static String getDisplayName()
    {
        return DISPLAY_NAME;
    }

    public static String getProductName()
    {
        return PRODUCT_NAME;
    }

    public static String getVersion()
    {
        return manifestValue(VERSION);
    }

    public static String getBuildTimestamp()
    {
        return manifestValue(BUILD_TIMESTAMP);
    }

    public static String getBuildJdk()
    {
        return manifestValue(BUILD_JDK);
    }

    public static String getBuildOs()
    {
        return manifestValue(BUILD_OS);
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
