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
    private enum StartedState
    {
        UNKNOWN,
        STOPPED,
        STARTED
    }

    private final Logger mLog;
    private final String mLogPrefix;
    private final Callback mCallback;
    private StartedState mStartedState = StartedState.UNKNOWN;

    SDRconnectPropertyUpdateHandler(Logger log, String logPrefix, Callback callback)
    {
        mLog = log;
        mLogPrefix = logPrefix;
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
                case SDRconnectProtocol.PROPERTY_LNA_STATE:
                    handleLnaStateUpdate(value);
                    break;
                case SDRconnectProtocol.PROPERTY_LNA_STATE_MIN:
                    handleLnaStateMinimumUpdate(value);
                    break;
                case SDRconnectProtocol.PROPERTY_LNA_STATE_MAX:
                    handleLnaStateMaximumUpdate(value);
                    break;
                case SDRconnectProtocol.PROPERTY_STARTED:
                    handleStartedStateUpdate(value);
                    break;
                case SDRconnectProtocol.PROPERTY_SIGNAL_POWER:
                    mCallback.onSignalPowerChanged(Double.parseDouble(value));
                    break;
                case SDRconnectProtocol.PROPERTY_SIGNAL_SNR:
                    mCallback.onSignalSnrChanged(Double.parseDouble(value));
                    break;
                case SDRconnectProtocol.PROPERTY_VALID_ANTENNAS:
                    handleValidAntennasUpdate(value);
                    break;
                case SDRconnectProtocol.PROPERTY_ACTIVE_ANTENNA:
                    handleActiveAntennaUpdate(value);
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
            mLog.warn("{} Error parsing property {} = {}", mLogPrefix, property, value);
        }
    }

    private void handleCenterFrequencyUpdate(String value)
    {
        long newFrequency = Long.parseLong(value);

        if(newFrequency < SDRconnectTunerController.MINIMUM_FREQUENCY)
        {
            mLog.debug("{} Ignoring transient center frequency: {} Hz", mLogPrefix, newFrequency);
            return;
        }

        mCallback.markFrequencyReceived();

        if(newFrequency != mCallback.getCenterFrequency())
        {
            mLog.info("{} Frequency changed: {} MHz", mLogPrefix, String.format("%.3f", newFrequency / 1e6));
            mCallback.onCenterFrequencyChanged(newFrequency);
        }
    }

    private void handleSampleRateUpdate(String value)
    {
        int newSampleRate = Integer.parseInt(value);
        mCallback.markSampleRateReceived();

        if(newSampleRate != mCallback.getSampleRate())
        {
            mLog.info("{} Sample rate changed: {} MHz", mLogPrefix, String.format("%.1f", newSampleRate / 1e6));
            mCallback.onSampleRateChanged(newSampleRate);
        }
    }

    private void handleStartedStateUpdate(String value)
    {
        StartedState startedState = "true".equalsIgnoreCase(value) ? StartedState.STARTED : StartedState.STOPPED;

        if(mStartedState == StartedState.UNKNOWN)
        {
            mStartedState = startedState;
            return;
        }

        if(startedState == StartedState.STARTED && mStartedState == StartedState.STOPPED &&
            mCallback.shouldScheduleRecoveryReinitialization())
        {
            mCallback.scheduleRecoveryReinitialization();
        }

        mStartedState = startedState;
    }

    private void handleLnaStateUpdate(String value)
    {
        int lnaState = Integer.parseInt(value);

        if(lnaState != mCallback.getLnaState())
        {
            mLog.info("{} LNA state changed: {}", mLogPrefix, lnaState);
            mCallback.onLnaStateChanged(lnaState);
        }
    }

    private void handleLnaStateMinimumUpdate(String value)
    {
        mCallback.onLnaStateMinimumChanged(Integer.parseInt(value));
    }

    private void handleLnaStateMaximumUpdate(String value)
    {
        mCallback.onLnaStateMaximumChanged(Integer.parseInt(value));
    }

    private void handleValidAntennasUpdate(String value)
    {
        if(!value.equals(mCallback.getValidAntennas()))
        {
            mCallback.onValidAntennasChanged(value);
            mLog.info("{} Valid antennas: {}", mLogPrefix, value);
        }
    }

    private void handleActiveAntennaUpdate(String value)
    {
        if(!value.equals(mCallback.getActiveAntenna()))
        {
            mCallback.onActiveAntennaChanged(value);
            mLog.info("{} Active antenna: {}", mLogPrefix, value);
        }
    }

    interface Callback
    {
        long getCenterFrequency();
        void onCenterFrequencyChanged(long frequency);
        int getSampleRate();
        void onSampleRateChanged(int sampleRate);
        int getLnaState();
        void onLnaStateChanged(int lnaState);
        void onLnaStateMinimumChanged(int lnaStateMinimum);
        void onLnaStateMaximumChanged(int lnaStateMaximum);
        void onSignalPowerChanged(double signalPower);
        void onSignalSnrChanged(double signalSnr);
        String getValidAntennas();
        String getActiveAntenna();
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
