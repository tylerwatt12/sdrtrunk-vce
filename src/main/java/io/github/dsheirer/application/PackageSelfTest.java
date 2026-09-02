/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.application;

import io.github.dsheirer.stats.StatsWebPath;
import java.io.PrintStream;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import javax.swing.LookAndFeel;

/**
 * Fast, read-only startup check for a finished release package.
 *
 * This runs before portable-data discovery and database startup.  It proves that the packaged launcher can start the
 * bundled runtime, read the application identity, find required classes and web assets, and access the Windows
 * look-and-feel class used by JIDE.  It never creates or changes a portable data directory.
 */
public final class PackageSelfTest
{
    public static final String ARGUMENT = "--package-self-test";
    private static final Set<String> TRACKS = Set.of("alpha", "nightly", "none");
    private static final String[] REQUIRED_CLASSES = {
        "com.jidesoft.swing.JideSplitPane",
        "io.github.dsheirer.database.upgrade.ApplicationDatabaseMigrator",
        "io.github.dsheirer.source.tuner.sdrplay.api.SDRPlayLibraryHelper",
        "io.github.dsheirer.source.tuner.sdrplay.api.SDRPlayLibraryPathResolver"
    };

    private PackageSelfTest()
    {
    }

    public static boolean isRequested(String[] arguments)
    {
        return arguments != null && arguments.length == 1 && ARGUMENT.equals(arguments[0]);
    }

    public static void run(PrintStream output)
    {
        verify(ApplicationInfo.getVersion(), ApplicationInfo.getUpdateTrack(), ApplicationInfo.getUpdateBuild(),
            StatsWebPath.getAssetsPath(), PackageSelfTest.class.getClassLoader(), output);
    }

    static void verify(String version, String track, String build, Path webAssets, ClassLoader classLoader,
                       PrintStream output)
    {
        requireText(version, "application version");
        requireText(track, "update track");
        requireText(build, "update build");

        if(!TRACKS.contains(track))
        {
            throw new IllegalStateException("Unsupported packaged update track: " + track);
        }

        long numericBuild;

        try
        {
            numericBuild = Long.parseLong(build);
        }
        catch(NumberFormatException e)
        {
            throw new IllegalStateException("Packaged update build is not a number: " + build, e);
        }

        if(("none".equals(track) && numericBuild != 0L) || (!"none".equals(track) && numericBuild <= 0L))
        {
            throw new IllegalStateException("Packaged update track and build do not agree: " + track + "/" + build);
        }

        verifyWindowsLauncher(System.getProperty("os.name"), System.getProperty("java.library.path"),
            System.mapLibraryName("sdrplay_api"));

        Path index = webAssets.resolve("index.html");

        if(!Files.isRegularFile(index))
        {
            throw new IllegalStateException("Packaged web application is missing: " + index);
        }

        try
        {
            for(String className: REQUIRED_CLASSES)
            {
                Class.forName(className, false, classLoader);
            }

            Class<?> jideSplitPane = Class.forName("com.jidesoft.swing.JideSplitPane", true, classLoader);
            jideSplitPane.getDeclaredConstructor().newInstance();

            Class<?> windowsLookAndFeel = Class.forName(
                "com.sun.java.swing.plaf.windows.WindowsLookAndFeel", true, classLoader);
            Constructor<?> constructor = windowsLookAndFeel.getDeclaredConstructor();
            Object lookAndFeel = constructor.newInstance();

            if(!(lookAndFeel instanceof LookAndFeel))
            {
                throw new IllegalStateException("Packaged Windows look-and-feel compatibility class is invalid");
            }
        }
        catch(ReflectiveOperationException | LinkageError e)
        {
            throw new IllegalStateException("Packaged application classes could not be loaded", e);
        }

        output.printf("PACKAGE SELF-TEST PASSED version=%s track=%s build=%s os=%s arch=%s%n", version, track,
            build, System.getProperty("os.name"), System.getProperty("os.arch"));
    }

    static void verifyWindowsLauncher(String osName, String libraryPath, String mappedLibraryName)
    {
        if(osName == null || !osName.toLowerCase(Locale.US).startsWith("windows"))
        {
            return;
        }

        if(!"sdrplay_api.dll".equalsIgnoreCase(mappedLibraryName))
        {
            throw new IllegalStateException("Packaged Windows runtime maps the SDRplay API to " + mappedLibraryName);
        }

        String expected = "c:\\Program Files\\SDRplay\\API\\x64";
        boolean found = libraryPath != null && java.util.Arrays.stream(libraryPath.split(";"))
            .map(String::trim)
            .map(path -> path.replace('/', '\\'))
            .anyMatch(expected::equalsIgnoreCase);

        if(!found)
        {
            throw new IllegalStateException("Packaged Windows launcher is missing the SDRplay API library path");
        }
    }

    private static void requireText(String value, String label)
    {
        if(value == null || value.isBlank())
        {
            throw new IllegalStateException("Packaged " + label + " is missing");
        }
    }
}
