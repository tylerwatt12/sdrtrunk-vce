/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */

package io.github.dsheirer.module.decode.p25;

import io.github.dsheirer.channel.IChannelDescriptor;
import io.github.dsheirer.message.AbstractMessage;
import io.github.dsheirer.module.decode.p25.identifier.channel.APCO25Channel;
import io.github.dsheirer.module.decode.p25.identifier.channel.P25Channel;
import io.github.dsheirer.module.decode.p25.phase1.message.IFrequencyBand;
import java.util.Map;

/**
 * Shared P25 bandplan and logical-channel sanity rules.
 */
public final class P25FrequencyBandValidator
{
    public static final long MINIMUM_BASE_FREQUENCY_HZ = 100_000_000L;
    public static final long MAXIMUM_BASE_FREQUENCY_HZ = 1_000_000_000L;
    public static final int MINIMUM_BAND_IDENTIFIER = 0;
    public static final int MAXIMUM_BAND_IDENTIFIER = 15;
    public static final int MINIMUM_CHANNEL_NUMBER = 0;
    public static final int MAXIMUM_CHANNEL_NUMBER = 4095;
    public static final int NO_CHANNEL_BAND_IDENTIFIER = 15;
    public static final int NO_CHANNEL_NUMBER = 4095;
    private static final long CHANNEL_SPACING_STEP_HZ = 125L;
    private static final long MAXIMUM_CHANNEL_SPACING_HZ = 1023L * CHANNEL_SPACING_STEP_HZ;

    private P25FrequencyBandValidator()
    {
    }

    /**
     * Registers a frequency band when it passes shared plausibility checks.
     */
    public static RegistrationResult register(Map<Integer,IFrequencyBand> frequencyBands, IFrequencyBand candidate)
    {
        if(frequencyBands == null || candidate == null)
        {
            return RegistrationResult.rejected(candidate, null, RejectReason.NULL_VALUE);
        }

        RejectReason rejectReason = validate(candidate);

        if(rejectReason != null)
        {
            return RegistrationResult.rejected(candidate, null, rejectReason);
        }

        IFrequencyBand existing = frequencyBands.get(candidate.getIdentifier());

        if(existing != null && !matches(existing, candidate))
        {
            if(candidate.isPreferredOver(existing))
            {
                frequencyBands.put(candidate.getIdentifier(), candidate);
                return RegistrationResult.replaced(candidate, existing);
            }

            return RegistrationResult.rejected(candidate, existing, RejectReason.CONFLICTS_WITH_EXISTING);
        }

        frequencyBands.put(candidate.getIdentifier(), candidate);
        return RegistrationResult.accepted(candidate, existing);
    }

    public static RejectReason validate(IFrequencyBand band)
    {
        if(band == null)
        {
            return RejectReason.NULL_VALUE;
        }

        if(!isValidBandIdentifier(band.getIdentifier()))
        {
            return RejectReason.INVALID_BAND_IDENTIFIER;
        }

        if(band.getBaseFrequency() < MINIMUM_BASE_FREQUENCY_HZ ||
            band.getBaseFrequency() > MAXIMUM_BASE_FREQUENCY_HZ)
        {
            return RejectReason.BASE_OUTSIDE_RF_RANGE;
        }

        long channelSpacing = band.getChannelSpacing();

        if(channelSpacing <= 0 || channelSpacing > MAXIMUM_CHANNEL_SPACING_HZ ||
            channelSpacing % CHANNEL_SPACING_STEP_HZ != 0)
        {
            return RejectReason.INVALID_CHANNEL_SPACING;
        }

        if(band.getTimeslotCount() < 1)
        {
            return RejectReason.INVALID_TIMESLOT_COUNT;
        }

        return null;
    }

    public static boolean matches(IFrequencyBand existing, IFrequencyBand candidate)
    {
        return existing != null && candidate != null &&
            existing.getBaseFrequency() == candidate.getBaseFrequency() &&
            existing.getChannelSpacing() == candidate.getChannelSpacing() &&
            existing.getBandwidth() == candidate.getBandwidth() &&
            existing.getTimeslotCount() == candidate.getTimeslotCount();
    }

    public static boolean isValidBandIdentifier(int bandIdentifier)
    {
        return bandIdentifier >= MINIMUM_BAND_IDENTIFIER && bandIdentifier <= MAXIMUM_BAND_IDENTIFIER;
    }

    public static boolean isValidChannelNumber(int channelNumber)
    {
        return channelNumber >= MINIMUM_CHANNEL_NUMBER && channelNumber <= MAXIMUM_CHANNEL_NUMBER;
    }

    public static boolean isNoChannel(int bandIdentifier, int channelNumber)
    {
        return bandIdentifier == NO_CHANNEL_BAND_IDENTIFIER && channelNumber == NO_CHANNEL_NUMBER;
    }

    public static boolean hasChannel(int bandIdentifier, int channelNumber)
    {
        return isValidBandIdentifier(bandIdentifier) && isValidChannelNumber(channelNumber) &&
            !isNoChannel(bandIdentifier, channelNumber);
    }

    public static boolean isResolvedChannel(IChannelDescriptor channel)
    {
        if(channel == null)
        {
            return false;
        }

        if(channel instanceof APCO25Channel apco25Channel)
        {
            P25Channel p25Channel = apco25Channel.getValue();

            if(p25Channel == null ||
                !hasChannel(p25Channel.getDownlinkBandIdentifier(), p25Channel.getDownlinkChannelNumber()))
            {
                return false;
            }
        }

        return channel.getDownlinkFrequency() > 0;
    }

    public static void applyFrequencyBands(IChannelDescriptor channel, Map<Integer,IFrequencyBand> frequencyBands)
    {
        if(channel == null || frequencyBands == null || frequencyBands.isEmpty())
        {
            return;
        }

        for(int id: channel.getFrequencyBandIdentifiers())
        {
            IFrequencyBand frequencyBand = frequencyBands.get(id);

            if(frequencyBand != null)
            {
                channel.setFrequencyBand(frequencyBand);
            }
        }
    }

    public static int getCorrectedBitCount(IFrequencyBand frequencyBand)
    {
        if(frequencyBand instanceof AbstractMessage message)
        {
            return message.getMessage().getCorrectedBitCount();
        }

        return Integer.MIN_VALUE;
    }

    public static String describe(IFrequencyBand band)
    {
        if(band == null)
        {
            return "class:null";
        }

        return "class:" + band.getClass().getSimpleName() + " id:" + band.getIdentifier() +
            " base:" + band.getBaseFrequency() + "Hz spacing:" + band.getChannelSpacing() +
            "Hz bandwidth:" + band.getBandwidth() + "Hz slots:" + band.getTimeslotCount();
    }

    public enum RejectReason
    {
        NULL_VALUE("null value"),
        INVALID_BAND_IDENTIFIER("invalid band identifier"),
        BASE_OUTSIDE_RF_RANGE("outside plausible RF range"),
        INVALID_CHANNEL_SPACING("invalid spacing"),
        INVALID_TIMESLOT_COUNT("invalid timeslot count"),
        CONFLICTS_WITH_EXISTING("conflicts with existing");

        private final String mDescription;

        RejectReason(String description)
        {
            mDescription = description;
        }

        public String getDescription()
        {
            return mDescription;
        }
    }

    public record RegistrationResult(IFrequencyBand candidate, IFrequencyBand existing, boolean accepted,
                                     boolean replaced, RejectReason rejectReason)
    {
        public static RegistrationResult accepted(IFrequencyBand candidate, IFrequencyBand existing)
        {
            return new RegistrationResult(candidate, existing, true, false, null);
        }

        public static RegistrationResult replaced(IFrequencyBand candidate, IFrequencyBand existing)
        {
            return new RegistrationResult(candidate, existing, true, true, null);
        }

        public static RegistrationResult rejected(IFrequencyBand candidate, IFrequencyBand existing,
                                                  RejectReason rejectReason)
        {
            return new RegistrationResult(candidate, existing, false, false, rejectReason);
        }
    }
}
