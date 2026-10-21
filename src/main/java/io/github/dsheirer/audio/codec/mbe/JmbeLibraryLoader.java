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

import io.github.dsheirer.jmbe.JmbeLibraryMetadata;
import io.github.dsheirer.jmbe.github.Version;
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
    private static final String ENABLE_VOICE_QUALITY_METADATA_METHOD = "setVoiceQualityMetadataEnabled";
    private static final String SET_TONE_AUDIO_ENABLED_METHOD = "setToneAudioEnabled";
    private static final JmbeLibraryLoader INSTANCE = new JmbeLibraryLoader(JmbeLibraryLoader.class.getClassLoader());

    private final ClassLoader mParentClassLoader;
    private final List<URLClassLoader> mClassLoaders = new ArrayList<>();
    private LibraryFingerprint mFingerprint;
    private IAudioCodecLibrary mLibrary;
    private JmbeAudioOutputProcessor mAudioOutput;
    private boolean mAudioOutputChecked;
    private String mLastError;

    JmbeLibraryLoader(ClassLoader parentClassLoader)
    {
        mParentClassLoader = parentClassLoader;
    }

    public static JmbeLibraryLoader getInstance()
    {
        return INSTANCE;
    }

    /** Worker-only compatibility check that leaves the active library and its codecs unchanged. */
    public static void validateLibrary(Path libraryPath) throws IOException
    {
        try(var loader = new JmbeLibraryLoader(JmbeLibraryLoader.class.getClassLoader()))
        {
            for(String name: List.of("IMBE", "AMBE 3600 x 2450"))
            {
                if(loader.getAudioCodec(libraryPath, name) == null)
                {
                    throw new IOException("JMBE library cannot provide the required " + name + " codec");
                }
            }
        }
    }

    /**
     * Creates a codec from the currently selected library. A changed path, size, or modification time atomically loads
     * a new library while existing codec instances remain valid for channels still using them.
     */
    public IAudioCodec getAudioCodec(Path libraryPath, String codecName)
    {
        return getAudioCodec(libraryPath, codecName, true);
    }

    /**
     * Creates a codec and applies optional tone-audio behavior when the selected JMBE library supports it. Libraries
     * without the optional capability retain their default tone behavior.
     */
    public synchronized IAudioCodec getAudioCodec(Path libraryPath, String codecName, boolean toneAudioEnabled)
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

            IAudioCodec codec = mLibrary.getAudioConverter(codecName);
            codec.getClass().getMethod(ENABLE_VOICE_QUALITY_METADATA_METHOD, boolean.class).invoke(codec, true);

            try
            {
                codec.getClass().getMethod(SET_TONE_AUDIO_ENABLED_METHOD, boolean.class)
                    .invoke(codec, toneAudioEnabled);
            }
            catch(NoSuchMethodException _)
            {
                //JMBE 1.0.14 does not provide the optional tone-audio control and retains its default behavior.
            }

            return codec;
        }
        catch(IOException | ReflectiveOperationException | LinkageError | RuntimeException e)
        {
            logErrorOnce("Unable to load JMBE library from " + libraryPath, e);
            return null;
        }
    }

    /** Worker-only optional output capability; decoder construction never loads or warms output processing. */
    public synchronized JmbeAudioOutputProcessor getAudioOutputProcessor(Path libraryPath)
    {
        if(libraryPath == null) return null;
        try
        {
            LibraryFingerprint requested = LibraryFingerprint.from(libraryPath);
            if(!requested.equals(mFingerprint)) load(requested);
            if(!requested.equals(mFingerprint) || mLibrary == null) return null;
            if(!mAudioOutputChecked)
            {
                Object provider;
                try { provider = mLibrary.getClass().getMethod("getAudioOutputProcessor").invoke(mLibrary); }
                catch(NoSuchMethodException unavailable) { mAudioOutputChecked = true; return null; }
                mAudioOutput = provider != null ? new JmbeAudioOutputProcessor(provider) : null;
                mAudioOutputChecked = true;
            }
            return mAudioOutput;
        }
        catch(IOException | ReflectiveOperationException | LinkageError | RuntimeException exception)
        {
            logErrorOnce("JMBE optional audio processing is unavailable", exception);
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

            Version libraryVersion = new Version(library.getMajorVersion(), library.getMinorVersion(),
                library.getBuildVersion(), null);

            if(libraryVersion.compareTo(JmbeLibraryMetadata.MINIMUM_SUPPORTED_VERSION) < 0)
            {
                throw new IllegalArgumentException("JMBE library version " +
                    JmbeLibraryMetadata.MINIMUM_SUPPORTED_VERSION + " or newer is required");
            }

            mClassLoaders.add(classLoader);
            mLibrary = library;
            mAudioOutput = null;
            mAudioOutputChecked = false;
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
        mAudioOutput = null;
        mAudioOutputChecked = false;
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
