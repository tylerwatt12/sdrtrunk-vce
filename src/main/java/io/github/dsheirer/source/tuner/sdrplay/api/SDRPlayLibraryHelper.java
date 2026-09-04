/*
 * *****************************************************************************
 * Copyright (C) 2014-2024 Dennis Sheirer
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
 * ****************************************************************************
 */

package io.github.dsheirer.source.tuner.sdrplay.api;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Predicate;
import org.apache.commons.lang3.SystemUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Helper utility to load the SDRPlay API native library into the VM.
 *
 * Note: the jextract auto-generated code attempts to load the native library by simple library name without any path
 * information.  On Windows this can fail because the API library is not installed in the normal Windows library location
 * and for some reason the system library VM option with the specific location that is passed in as an argument on
 * the command line doesn't help the system loader to resolve the library path.  So, we preemptively load the library
 * here.
 */
public class SDRPlayLibraryHelper
{
    private SDRPlayLibraryHelper()
    {
        /* utility class */
    }

    private static final Logger mLog = LoggerFactory.getLogger(SDRPlayLibraryHelper.class);
    private static final String SDRPLAY_API_LIBRARY_NAME = "sdrplay_api";
    private static final String SDRPLAY_API_PATH_LINUX = "/usr/local/lib/libsdrplay_api.so";
    private static final String SDRPLAY_API_PATH_MAC_OS = "/usr/local/lib/libsdrplay_api.dylib";
    private static final String SDRPLAY_API_PATH_MAC_OS_ALTERNATE = "/usr/local/lib/libsdrplay_api.so";

    public static final boolean LOADED;
    public static final boolean LOADED_FROM_PATH;
    public static final Path LIBRARY_PATH;
    public static final LoadState LOAD_STATE;

    /** Missing optional support is different from an installed library that could not be loaded. */
    public enum LoadState { AVAILABLE, NOT_INSTALLED, LOAD_FAILED }

    interface LibraryLoader
    {
        void loadLibrary(String name);
        void load(String path);
    }

    record LoadResult(LoadState state, boolean fromPath) {}

    static
    {
        Path libraryPath = resolveSDRplayLibraryPath();
        LIBRARY_PATH = libraryPath != null ? libraryPath : Path.of("");
        LoadResult result = load(libraryPath, Files::isRegularFile, new LibraryLoader()
        {
            @Override public void loadLibrary(String name) { System.loadLibrary(name); }
            @Override public void load(String path) { System.load(path); }
        });
        LOAD_STATE = result.state();
        LOADED = LOAD_STATE == LoadState.AVAILABLE;
        LOADED_FROM_PATH = result.fromPath();
    }

    /** Testable loading policy; this loads the library only, never opens the API or a radio. */
    static LoadResult load(Path libraryPath, Predicate<Path> isFile, LibraryLoader loader)
    {
        try
        {
            loader.loadLibrary(SDRPLAY_API_LIBRARY_NAME);
            mLog.info("SDRPlay API library loaded by name [" + SDRPLAY_API_LIBRARY_NAME + "]");
            return new LoadResult(LoadState.AVAILABLE, false);
        }
        catch(UnsatisfiedLinkError | SecurityException failure)
        {
            if(libraryPath != null && isFile.test(libraryPath))
            {
                try
                {
                    loader.load(libraryPath.toString());
                    mLog.info("SDRPlay API library loaded by path [" + libraryPath + "]");
                    return new LoadResult(LoadState.AVAILABLE, true);
                }
                catch(UnsatisfiedLinkError | SecurityException pathFailure)
                {
                    mLog.warn("SDRPlay API native library was found but could not be loaded from [" + libraryPath + "]");
                    mLog.debug("SDRPlay API native library load failure", pathFailure);
                    return new LoadResult(LoadState.LOAD_FAILED, false);
                }
            }
            else if(isMissingLibrary(failure))
            {
                mLog.info("SDRplay support is not installed; it is only needed for SDRplay RSP radios.");
                return new LoadResult(LoadState.NOT_INSTALLED, false);
            }
            else
            {
                mLog.warn("SDRplay support could not be loaded. Check the SDRplay API installation.");
                mLog.debug("SDRPlay API native library load failure", failure);
                return new LoadResult(LoadState.LOAD_FAILED, false);
            }
        }
    }

    private static boolean isMissingLibrary(Throwable failure)
    {
        return failure instanceof UnsatisfiedLinkError && failure.getMessage() != null &&
            failure.getMessage().startsWith("no " + SDRPLAY_API_LIBRARY_NAME + " in java.library.path");
    }

    /**
     * Identifies the java library path for the sdrplay api library at runtime.
     */
    public static String getSDRplayLibraryPath()
    {
        return LIBRARY_PATH.toString();
    }

    /**
     * Resolves the platform-specific SDRplay native library path.
     */
    private static Path resolveSDRplayLibraryPath()
    {
        if(SystemUtils.IS_OS_WINDOWS)
        {
            return SDRPlayLibraryPathResolver.resolveWindows(System.getenv("ProgramFiles"), System::mapLibraryName)
                    .orElse(null);
        }
        else if(SystemUtils.IS_OS_LINUX)
        {
            return Path.of(SDRPLAY_API_PATH_LINUX);
        }
        else if(SystemUtils.IS_OS_MAC_OSX)
        {
            //API versions 3.14 and earlier used a (.so) extension and 3.15 and later use the (.dylib) extension
            if(Files.exists(Path.of(SDRPLAY_API_PATH_MAC_OS)))
            {
                return Path.of(SDRPLAY_API_PATH_MAC_OS);
            }
            else
            {
                return Path.of(SDRPLAY_API_PATH_MAC_OS_ALTERNATE);
            }
        }

        mLog.debug("No platform-specific SDRplay API library path is configured for this operating system");
        return null;
    }
}
