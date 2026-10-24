/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */

package io.github.dsheirer.source.tuner.manager;

import io.github.dsheirer.source.tuner.TunerType;
import io.github.dsheirer.source.tuner.configuration.TunerConfiguration;
import io.github.dsheirer.source.tuner.configuration.TunerConfigurationManager;
import io.github.dsheirer.source.tuner.recording.RecordingTunerConfiguration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class TunerManagerStartupTest
{
    @Test
    void nativeLoadFailureAndSubsequentErroneousClassBothPreserveOtherTuners() throws Exception
    {
        List<LinkageError> failures = new ArrayList<>();
        Runnable usbStartup = () ->
        {
            try
            {
                MissingNativeLibrary.load();
            }
            catch(LinkageError failure)
            {
                failures.add(failure);
                throw failure;
            }
        };

        //The first attempt models native loading. The next models startup after setup already failed to load it.
        assertOtherTunersStart(usbStartup);
        assertOtherTunersStart(usbStartup);

        assertEquals(2, failures.size());
        assertInstanceOf(UnsatisfiedLinkError.class, failures.get(0));
        assertInstanceOf(NoClassDefFoundError.class, failures.get(1));
    }

    @Test
    void usbInitializationExceptionPreservesOtherTuners() throws Exception
    {
        assertOtherTunersStart(() ->
        {
            throw new IllegalStateException("USB backend initialization failed");
        });
    }

    private static void assertOtherTunersStart(Runnable usbStartup) throws Exception
    {
        RecordingTunerConfiguration recording = RecordingTunerConfiguration.createWithUniqueId();
        recording.setPath("startup-test-recording.wav");
        StartupTunerManager manager = new StartupTunerManager(usbStartup, recording);

        try
        {
            assertDoesNotThrow(manager::start);
            assertEquals(1, manager.mSdrPlayDiscoveryCount);
            assertNotNull(manager.getDiscoveredTunerRegistry().find(recording.getPath()));
            assertEquals(TunerManager.USB_RESCAN_UNAVAILABLE,
                manager.requestUsbTunerRescan().get(5, TimeUnit.SECONDS));
        }
        finally
        {
            manager.stop();
        }
    }

    private static final class StartupTunerManager extends TunerManager
    {
        private final Runnable mUsbStartup;
        private int mSdrPlayDiscoveryCount;

        private StartupTunerManager(Runnable usbStartup, RecordingTunerConfiguration recording)
        {
            super(null, new TunerConfigurationManager()
            {
                @Override
                public List<TunerConfiguration> getTunerConfigurations(TunerType tunerType)
                {
                    return tunerType == TunerType.RECORDING ? List.of(recording) : List.of();
                }
            });
            mUsbStartup = usbStartup;
        }

        @Override
        void startUsbTuners()
        {
            mUsbStartup.run();
        }

        @Override
        void discoverSdrPlayTuners()
        {
            mSdrPlayDiscoveryCount++;
        }
    }

    private static final class MissingNativeLibrary
    {
        static
        {
            failToLoad();
        }

        private static void failToLoad()
        {
            throw new UnsatisfiedLinkError("Test native dependency is unavailable");
        }

        private static void load()
        {
        }
    }
}
