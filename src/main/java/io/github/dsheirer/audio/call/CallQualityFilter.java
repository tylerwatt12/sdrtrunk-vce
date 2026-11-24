/* Copyright (C) 2026 Dennis Sheirer */
package io.github.dsheirer.audio.call;

import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.preference.call.CallFilteringSettings;

/** One worker-side admission decision over the selected logical call's measured voice frames. */
final class CallQualityFilter
{
    private CallQualityFilter() {}

    static boolean accepts(AudioCallSnapshot snapshot, CallFilteringSettings settings)
    {
        return accepts(snapshot.callLegSource().decoderType(), snapshot.voiceCallQuality(), settings);
    }

    static boolean accepts(DecoderType decoder, VoiceCallQuality quality, CallFilteringSettings settings)
    {
        if(!settings.enabled()) return true;
        //Unmeasured audio is not measured damage. Analog and metadata-only calls retain their existing behavior.
        return decoder == null || !decoder.providesMBEAudioFrames() || !quality.hasMeasurements() ||
            quality.qualityPercent() >= settings.minimumUsableVoicePercent();
    }
}
