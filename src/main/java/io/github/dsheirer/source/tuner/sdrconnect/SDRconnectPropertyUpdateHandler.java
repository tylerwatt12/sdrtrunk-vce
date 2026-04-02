/*
 * *****************************************************************************
 * Copyright (C) 2014-2025 Dennis Sheirer
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

package io.github.dsheirer.source.tuner.sdrconnect;

import org.slf4j.Logger;

/**
 * Handles SDRconnect property updates and delegates controller-specific side effects through callbacks.
 */
class SDRconnectPropertyUpdateHandler
{
    private final Logger mLog;
    private final Callback mCallback;
    private boolean mLastStartedState;

    SDRconnectPropertyUpdateHandler(Logger log, Callback callback)
    {
        mLog = log;
        mCallback = callback;
    }

    void handle(String property, String value)
    {
        try
        {
            switch(property)
            {
                case SDRconnectProtocol.PROPERTY_DEVICE_CENTER_FREQUENCY:
                    handleCenterFrequencyUpdate(value);
                    break;
                case SDRconnectProtocol.PROPERTY_DEVICE_SAMPLE_RATE:
                    handleSampleRateUpdate(value);
                    break;
                case SDRconnectProtocol.PROPERTY_STARTED:
                    handleStartedStateUpdate(value);
                    break;
                case SDRconnectProtocol.PROPERTY_VALID_ANTENNAS:
                    mCallback.onValidAntennasChanged(value);
                    mLog.info("SDRconnect valid antennas: {}", value);
                    break;
                case SDRconnectProtocol.PROPERTY_ACTIVE_ANTENNA:
                    mCallback.onActiveAntennaChanged(value);
                    mLog.info("SDRconnect active antenna: {}", value);
                    break;
                case SDRconnectProtocol.PROPERTY_VALID_DEVICES:
                    mCallback.onValidDevicesChanged(value);
                    break;
                case SDRconnectProtocol.PROPERTY_ACTIVE_DEVICE:
                    mCallback.onActiveDeviceChanged(value);
                    break;
                default:
                    break;
            }
        }
        catch(NumberFormatException e)
        {
            mLog.warn("Error parsing property {} = {}", property, value);
        }
    }

    void seedStartedState(boolean started)
    {
        mLastStartedState = started;
    }

    private void handleCenterFrequencyUpdate(String value)
    {
        long newFrequency = Long.parseLong(value);

        if(newFrequency < SDRconnectTunerController.MINIMUM_FREQUENCY)
        {
            mLog.debug("Ignoring transient SDRconnect center frequency: {} Hz", newFrequency);
            return;
        }

        mCallback.markFrequencyReceived();

        if(newFrequency != mCallback.getCenterFrequency())
        {
            mLog.info("SDRconnect frequency changed: {} MHz", String.format("%.3f", newFrequency / 1e6));
            mCallback.onCenterFrequencyChanged(newFrequency);
        }
    }

    private void handleSampleRateUpdate(String value)
    {
        int newSampleRate = Integer.parseInt(value);
        mCallback.markSampleRateReceived();

        if(newSampleRate != mCallback.getSampleRate())
        {
            mLog.info("SDRconnect sample rate changed: {} MHz", String.format("%.1f", newSampleRate / 1e6));
            mCallback.onSampleRateChanged(newSampleRate);
        }
    }

    private void handleStartedStateUpdate(String value)
    {
        boolean started = "true".equalsIgnoreCase(value);

        if(started && !mLastStartedState && mCallback.shouldScheduleRecoveryReinitialization())
        {
            mCallback.scheduleRecoveryReinitialization();
        }

        mLastStartedState = started;
    }

    interface Callback
    {
        long getCenterFrequency();
        void onCenterFrequencyChanged(long frequency);
        int getSampleRate();
        void onSampleRateChanged(int sampleRate);
        void onValidDevicesChanged(String validDevices);
        void onActiveDeviceChanged(String activeDevice);
        void onValidAntennasChanged(String validAntennas);
        void onActiveAntennaChanged(String activeAntenna);
        void markFrequencyReceived();
        void markSampleRateReceived();
        boolean shouldScheduleRecoveryReinitialization();
        void scheduleRecoveryReinitialization();
    }
}
