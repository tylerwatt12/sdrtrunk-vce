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
package io.github.dsheirer.dsp.filter.channelizer;

import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Restart-only receiver queue policy selected with the {@value #PROPERTY_NAME} JVM system property.
 *
 * <p>The protected profile is the default and bounds retained receiver work.  The retain-all profile is a diagnostic
 * comparison that removes the Java queue limits, similar to mainline SDRTrunk's retention policy.  It can exhaust the
 * Java heap when a consumer cannot keep pace.</p>
 */
public enum ReceiverQueueProfile
{
    PROTECTED("protected", "VCE Protected", 100, 8, 8),
    RETAIN_ALL("retain-all", "Mainline-like Retention", 0, 0, 0);

    public static final String PROPERTY_NAME = "sdrtrunk.receiver.queueProfile";
    private static final Logger mLog = LoggerFactory.getLogger(ReceiverQueueProfile.class);
    private static volatile ReceiverQueueProfile sActive;

    private final String mPropertyValue;
    private final String mDisplayName;
    private final long mNativeBufferMaximumQueueDurationMillis;
    private final int mIfftQueueCapacity;
    private final int mChannelOutputQueueCapacity;

    ReceiverQueueProfile(String propertyValue, String displayName, long nativeBufferMaximumQueueDurationMillis,
                         int ifftQueueCapacity, int channelOutputQueueCapacity)
    {
        mPropertyValue = propertyValue;
        mDisplayName = displayName;
        mNativeBufferMaximumQueueDurationMillis = nativeBufferMaximumQueueDurationMillis;
        mIfftQueueCapacity = ifftQueueCapacity;
        mChannelOutputQueueCapacity = channelOutputQueueCapacity;
    }

    /**
     * Active process-wide profile.  The property is read once when this class is initialized, so changing it requires
     * an application restart.
     */
    public static ReceiverQueueProfile getActive()
    {
        ReceiverQueueProfile active = sActive;

        if(active == null)
        {
            synchronized(ReceiverQueueProfile.class)
            {
                active = sActive;

                if(active == null)
                {
                    active = loadActive();
                    sActive = active;
                }
            }
        }

        return active;
    }

    /**
     * Parses an explicit profile value.
     *
     * @throws IllegalArgumentException when the supplied value does not name a supported profile
     */
    public static ReceiverQueueProfile parse(String value)
    {
        if(value != null)
        {
            String normalized = value.trim().toLowerCase(Locale.ROOT);

            for(ReceiverQueueProfile profile: values())
            {
                if(profile.mPropertyValue.equals(normalized))
                {
                    return profile;
                }
            }
        }

        throw new IllegalArgumentException("Unsupported receiver queue profile [" + value + "]. Set -D" +
            PROPERTY_NAME + "=protected or -D" + PROPERTY_NAME + "=retain-all");
    }

    private static ReceiverQueueProfile loadActive()
    {
        String configured = System.getProperty(PROPERTY_NAME);
        ReceiverQueueProfile profile = configured == null ? PROTECTED : parse(configured);
        mLog.info("Receiver queue profile: {} (-D{}={})", profile.getDisplayName(), PROPERTY_NAME,
            profile.getPropertyValue());

        if(profile.isRetainAll())
        {
            mLog.warn("Receiver retain-all queues are unbounded and can exhaust the Java heap when processing falls " +
                "behind");
        }

        return profile;
    }

    public String getPropertyValue()
    {
        return mPropertyValue;
    }

    public String getDisplayName()
    {
        return mDisplayName;
    }

    /**
     * Maximum native-buffer duration in milliseconds, or zero for unbounded.
     */
    public long getNativeBufferMaximumQueueDurationMillis()
    {
        return mNativeBufferMaximumQueueDurationMillis;
    }

    /**
     * Maximum IFFT batches waiting in the dispatcher, or zero for unbounded.
     */
    public int getIfftQueueCapacity()
    {
        return mIfftQueueCapacity;
    }

    /**
     * Maximum batches waiting in each channel-output dispatcher, or zero for unbounded.
     */
    public int getChannelOutputQueueCapacity()
    {
        return mChannelOutputQueueCapacity;
    }

    public boolean isRetainAll()
    {
        return this == RETAIN_ALL;
    }
}
