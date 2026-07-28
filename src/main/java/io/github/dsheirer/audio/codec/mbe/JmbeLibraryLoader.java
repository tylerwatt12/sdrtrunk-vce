/*
 * *****************************************************************************
 * Copyright (C) 2026
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */

package io.github.dsheirer.audio.codec.mbe;

import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import jmbe.iface.IAudioCodec;
import jmbe.iface.IAudioCodecLibrary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns JMBE library loading and creates independent codec instances for decoder channels.
 */
public final class JmbeLibraryLoader implements AutoCloseable
{
    private static final Logger mLog = LoggerFactory.getLogger(JmbeLibraryLoader.class);
    private static final String LIBRARY_CLASS = "jmbe.JMBEAudioLibrary";
    private static final JmbeLibraryLoader INSTANCE = new JmbeLibraryLoader(JmbeLibraryLoader.class.getClassLoader(), true);

    private final ClassLoader mParentClassLoader;
    private final List<URLClassLoader> mClassLoaders = new ArrayList<>();
    private LibraryFingerprint mFingerprint;
    private IAudioCodecLibrary mLibrary;
    private String mLastError;

    JmbeLibraryLoader(ClassLoader parentClassLoader, boolean registerShutdownHook)
    {
        mParentClassLoader = parentClassLoader;

        if(registerShutdownHook)
        {
            Runtime.getRuntime().addShutdownHook(new Thread(this::close, "jmbe-library-loader-shutdown"));
        }
    }

    public static JmbeLibraryLoader getInstance()
    {
        return INSTANCE;
    }

    /**
     * Creates a codec from the currently selected library. A changed path, size, or modification time atomically loads
     * a new library while existing codec instances remain valid for channels still using them.
     */
    public synchronized IAudioCodec getAudioCodec(Path libraryPath, String codecName)
    {
        if(libraryPath == null)
        {
            logErrorOnce("JMBE audio library path is not set");
            return null;
        }

        try
        {
            LibraryFingerprint requested = LibraryFingerprint.from(libraryPath);

            if(!requested.equals(mFingerprint))
            {
                load(requested);
            }

            if(!requested.equals(mFingerprint) || mLibrary == null)
            {
                return null;
            }

            if(!mLibrary.supports(codecName))
            {
                logErrorOnce("JMBE library does not support codec " + codecName);
                return null;
            }

            return mLibrary.getAudioConverter(codecName);
        }
        catch(IOException | ReflectiveOperationException | LinkageError | RuntimeException e)
        {
            logErrorOnce("Unable to load JMBE library from " + libraryPath, e);
            return null;
        }
    }

    private void load(LibraryFingerprint fingerprint) throws IOException, ReflectiveOperationException
    {
        URLClassLoader classLoader = new URLClassLoader(new java.net.URL[]{fingerprint.path().toUri().toURL()},
            mParentClassLoader);

        try
        {
            Object instance = Class.forName(LIBRARY_CLASS, true, classLoader).getDeclaredConstructor().newInstance();

            if(!(instance instanceof IAudioCodecLibrary library))
            {
                throw new IllegalArgumentException("JMBE entry point does not implement IAudioCodecLibrary");
            }

            if(library.getMajorVersion() < 1 ||
                library.getMajorVersion() == 1 && library.getMinorVersion() == 0 &&
                    library.getBuildVersion() < 14)
            {
                throw new IllegalArgumentException("JMBE library version 1.0.14 or newer is required");
            }

            mClassLoaders.add(classLoader);
            mLibrary = library;
            mFingerprint = fingerprint;
            mLastError = null;
            mLog.info("JMBE audio conversion library loaded from [{}]: {}", fingerprint.path(), library.getVersion());
        }
        catch(ReflectiveOperationException | LinkageError | RuntimeException e)
        {
            classLoader.close();
            throw e;
        }
    }

    private void logErrorOnce(String message)
    {
        if(!message.equals(mLastError))
        {
            mLastError = message;
            mLog.warn(message);
        }
    }

    private void logErrorOnce(String message, Throwable throwable)
    {
        if(!message.equals(mLastError))
        {
            mLastError = message;
            mLog.error(message, throwable);
        }
    }

    @Override
    public synchronized void close()
    {
        for(URLClassLoader classLoader: mClassLoaders)
        {
            try
            {
                classLoader.close();
            }
            catch(IOException e)
            {
                mLog.debug("Unable to close a JMBE classloader", e);
            }
        }

        mClassLoaders.clear();
        mLibrary = null;
        mFingerprint = null;
    }

    private record LibraryFingerprint(Path path, long size, FileTime modified)
    {
        private static LibraryFingerprint from(Path path) throws IOException
        {
            Path normalized = path.toAbsolutePath().normalize();

            if(!Files.isRegularFile(normalized))
            {
                throw new IOException("JMBE library does not exist: " + normalized);
            }

            return new LibraryFingerprint(normalized, Files.size(normalized), Files.getLastModifiedTime(normalized));
        }
    }
}
