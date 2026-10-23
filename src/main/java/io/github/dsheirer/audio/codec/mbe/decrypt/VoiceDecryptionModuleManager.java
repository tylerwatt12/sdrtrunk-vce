/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */

package io.github.dsheirer.audio.codec.mbe.decrypt;

import io.github.dsheirer.preference.encryption.VoiceEncryptionAlgorithm;
import io.github.dsheirer.preference.encryption.VoiceEncryptionProtocol;
import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads and owns the optional voice decryption module.
 */
public class VoiceDecryptionModuleManager implements AutoCloseable
{
    private static final Logger mLog = LoggerFactory.getLogger(VoiceDecryptionModuleManager.class);
    private volatile Status mStatus = new Status(false, "No module selected");
    private final CopyOnWriteArrayList<BiConsumer<Status,Status>> mStatusListeners = new CopyOnWriteArrayList<>();
    private volatile boolean mRuntimeLoaded;
    private volatile List<VoiceFrameDecryptorProvider> mProviders = List.of();
    private volatile EnumSet<VoiceEncryptionAlgorithm> mSupportedAlgorithms =
        EnumSet.noneOf(VoiceEncryptionAlgorithm.class);
    private URLClassLoader mClassLoader;
    private Path mPath;
    private String mModuleName;
    private String mModuleVersion;

    /**
     * Checks the module file and API without making it active.
     */
    public synchronized boolean isCompatible(Path path)
    {
        ModuleCandidate candidate = null;

        try
        {
            candidate = open(path);
            publishStatus("Module file accepted");
            return true;
        }
        catch(Exception | LinkageError | java.util.ServiceConfigurationError e)
        {
            setLoadError(message(e));
            mLog.warn("Unable to inspect optional module from {}: {}", path, mStatus.message());
            return false;
        }
        finally
        {
            close(candidate);
        }
    }

    public synchronized boolean load(Path path)
    {
        ModuleCandidate candidate = null;

        try
        {
            candidate = open(path);
            VoiceDecryptionModule module = candidate.module();

            List<VoiceFrameDecryptorProvider> providers = List.copyOf(module.getProviders());

            if(providers.isEmpty() || providers.stream().anyMatch(java.util.Objects::isNull))
            {
                throw new IllegalArgumentException("Module contains no usable providers");
            }

            EnumSet<VoiceEncryptionAlgorithm> algorithms = EnumSet.noneOf(VoiceEncryptionAlgorithm.class);
            Collection<VoiceEncryptionAlgorithm> suppliedAlgorithms = module.getSupportedAlgorithms();

            if(suppliedAlgorithms != null)
            {
                algorithms.addAll(suppliedAlgorithms);
            }

            unloadCurrent();
            mClassLoader = candidate.classLoader();
            mPath = candidate.path();
            mModuleName = module.getName();
            mModuleVersion = module.getVersion();
            mProviders = providers;
            mSupportedAlgorithms = algorithms;
            mRuntimeLoaded = true;
            publishStatus(displayName() + " loaded");
            mLog.info("Loaded optional module {} from {}", displayName(), mPath);
            candidate = null;
            return true;
        }
        catch(Exception | LinkageError | java.util.ServiceConfigurationError e)
        {
            setLoadError(message(e));
            mLog.warn("Unable to load optional module from {}: {}", path, mStatus.message());
            return false;
        }
        finally
        {
            close(candidate);
        }
    }

    public synchronized void unload()
    {
        unloadCurrent();
        publishStatus("No module selected");
    }

    public boolean isLoaded()
    {
        return mRuntimeLoaded;
    }

    public String getStatus() { return mStatus.message(); }

    public Status statusSnapshot() { return mStatus; }

    /** Control-plane notification. GUI listeners marshal their own presentation updates. */
    public void addStatusListener(BiConsumer<Status,Status> listener)
    {
        mStatusListeners.addIfAbsent(Objects.requireNonNull(listener));
    }

    public void removeStatusListener(BiConsumer<Status,Status> listener) { mStatusListeners.remove(listener); }

    public record Status(boolean loaded, String message) {}

    public Path getPath()
    {
        return mPath;
    }

    public String getModuleName()
    {
        return mModuleName;
    }

    public String getModuleVersion()
    {
        return mModuleVersion;
    }

    public List<VoiceFrameDecryptorProvider> getProviders()
    {
        return mProviders;
    }

    public List<VoiceEncryptionAlgorithm> getSupportedAlgorithms(VoiceEncryptionProtocol protocol)
    {
        return mSupportedAlgorithms.stream().filter(algorithm -> algorithm.getProtocol() == protocol).toList();
    }

    public boolean supports(VoiceEncryptionAlgorithm algorithm)
    {
        return algorithm != null && mSupportedAlgorithms.contains(algorithm);
    }

    @Override
    public synchronized void close()
    {
        unloadCurrent();
    }

    private void setLoadError(String message)
    {
        publishStatus(isLoaded() ? displayName() + " remains loaded; rejected selection: " + message :
            "Not loaded: " + message);
    }

    private String displayName()
    {
        String name = mModuleName == null || mModuleName.isBlank() ? "Optional module" : mModuleName;
        return mModuleVersion == null || mModuleVersion.isBlank() ? name : name + " " + mModuleVersion;
    }

    private ModuleCandidate open(Path path) throws Exception
    {
        if(path == null || !Files.isRegularFile(path))
        {
            throw new IllegalArgumentException("Module file does not exist");
        }

        Path realPath = path.toRealPath();
        URLClassLoader loader = new URLClassLoader(new java.net.URL[]{realPath.toUri().toURL()},
            VoiceDecryptionModule.class.getClassLoader());

        try
        {
            List<VoiceDecryptionModule> modules = ServiceLoader.load(VoiceDecryptionModule.class, loader)
                .stream().map(ServiceLoader.Provider::get).toList();

            if(modules.size() != 1)
            {
                throw new IllegalArgumentException("Expected one optional module provider, found " + modules.size());
            }

            VoiceDecryptionModule module = modules.getFirst();

            if(module.getApiVersion() != VoiceDecryptionModule.API_VERSION)
            {
                throw new IllegalArgumentException("Module API " + module.getApiVersion() +
                    " is incompatible with SDRTrunk API " + VoiceDecryptionModule.API_VERSION);
            }

            return new ModuleCandidate(realPath, loader, module);
        }
        catch(Exception | LinkageError | java.util.ServiceConfigurationError e)
        {
            close(loader);
            throw e;
        }
    }

    private static String message(Throwable throwable)
    {
        return throwable.getMessage() == null ? throwable.getClass().getSimpleName() : throwable.getMessage();
    }

    private void unloadCurrent()
    {
        mRuntimeLoaded = false;
        close(mClassLoader);
        mClassLoader = null;
        mPath = null;
        mModuleName = null;
        mModuleVersion = null;
        mProviders = List.of();
        mSupportedAlgorithms = EnumSet.noneOf(VoiceEncryptionAlgorithm.class);
        publishStatus("No module selected");
    }

    private void publishStatus(String message)
    {
        Status previous = mStatus;
        Status next = new Status(mRuntimeLoaded, message);
        mStatus = next;
        if(previous.equals(next)) return;
        for(BiConsumer<Status,Status> listener: mStatusListeners)
        {
            try { listener.accept(previous, next); }
            catch(RuntimeException failure) { mLog.warn("Optional module status observer failed"); }
        }
    }

    private void close(URLClassLoader classLoader)
    {
        if(classLoader != null)
        {
            try
            {
                classLoader.close();
            }
            catch(IOException e)
            {
                mLog.debug("Unable to close optional module class loader", e);
            }
        }
    }

    private void close(ModuleCandidate candidate)
    {
        if(candidate != null)
        {
            close(candidate.classLoader());
        }
    }

    private record ModuleCandidate(Path path, URLClassLoader classLoader, VoiceDecryptionModule module) {}
}
