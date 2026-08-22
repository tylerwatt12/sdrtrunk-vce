/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.web.settings;

import io.github.dsheirer.preference.nowplaying.NowPlayingPreference;
import java.util.Locale;

/**
 * Current receiver-wide browser and Live activity presentation policy.
 */
public record WebDisplaySettings(int formatVersion, boolean showEncryptionDetails,
                                 boolean retainIdleCallDetails, boolean showControlDecodeQuality,
                                 boolean showVoiceDecodeQuality, boolean clearVoiceDecodeQualityOnCallEnd,
                                 String decodeQualityDisplayMode, int trafficGrantAgeOutMilliseconds,
                                 int liveDetailMatchingRowLimit)
{
    public static final int CURRENT_FORMAT_VERSION = 2;

    public WebDisplaySettings
    {
        if(formatVersion != CURRENT_FORMAT_VERSION)
        {
            throw new IllegalArgumentException("Unsupported web display settings format");
        }

        decodeQualityDisplayMode = decodeQualityDisplayMode != null ?
            decodeQualityDisplayMode.toLowerCase(Locale.ROOT) : null;
        parseDecodeQualityDisplayMode(decodeQualityDisplayMode);

        if(trafficGrantAgeOutMilliseconds < NowPlayingPreference.MIN_TRAFFIC_GRANT_AGE_OUT_MILLISECONDS ||
            trafficGrantAgeOutMilliseconds > NowPlayingPreference.MAX_TRAFFIC_GRANT_AGE_OUT_MILLISECONDS)
        {
            throw new IllegalArgumentException("traffic_grant_age_out_milliseconds must be between " +
                NowPlayingPreference.MIN_TRAFFIC_GRANT_AGE_OUT_MILLISECONDS + " and " +
                NowPlayingPreference.MAX_TRAFFIC_GRANT_AGE_OUT_MILLISECONDS);
        }

        if(liveDetailMatchingRowLimit < NowPlayingPreference.MIN_LIVE_DETAIL_MATCHING_ROW_LIMIT ||
            liveDetailMatchingRowLimit > NowPlayingPreference.MAX_LIVE_DETAIL_MATCHING_ROW_LIMIT)
        {
            throw new IllegalArgumentException("live_detail_matching_row_limit must be between " +
                NowPlayingPreference.MIN_LIVE_DETAIL_MATCHING_ROW_LIMIT + " and " +
                NowPlayingPreference.MAX_LIVE_DETAIL_MATCHING_ROW_LIMIT);
        }
    }

    public NowPlayingPreference.DecodeQualityDisplayMode parsedDecodeQualityDisplayMode()
    {
        return parseDecodeQualityDisplayMode(decodeQualityDisplayMode);
    }

    public static NowPlayingPreference.DecodeQualityDisplayMode parseDecodeQualityDisplayMode(String mode)
    {
        if(mode == null)
        {
            throw new IllegalArgumentException("decode_quality_display_mode is required");
        }

        try
        {
            return NowPlayingPreference.DecodeQualityDisplayMode.valueOf(mode.toUpperCase(Locale.ROOT));
        }
        catch(IllegalArgumentException exception)
        {
            throw new IllegalArgumentException("decode_quality_display_mode must be percentage or detailed");
        }
    }
}
