/* Copyright (C) 2026. Licensed under the GNU General Public License, version 3 or later. */
package io.github.dsheirer.audio.tone;

import io.github.dsheirer.identifier.tone.AmbeTone;
import java.util.List;
import java.util.Map;

/** Existing JMBE metadata only: no reinterpretation of protocol bits or PCM detection. */
public final class EncodedToneMetadata
{
    private static final String[] TYPES = {"TONE","DTMF","KNOX","CALL PROGRESS"};
    private EncodedToneMetadata() {}

    /** JMBE emits at most one tone. Unexpected conflicting tone entries are unavailable evidence. */
    public static AmbeTone tone(Map<String,String> metadata)
    {
        AmbeTone result = null;
        if(metadata != null)
        {
            for(String type: TYPES)
            {
                AmbeTone tone = AmbeTone.fromValues(type, metadata.get(type));
                if(tone != AmbeTone.INVALID)
                {
                    if(result != null && result != tone) return AmbeTone.INVALID;
                    result = tone;
                }
            }
        }
        return result;
    }

    /** Single-frequency values explicitly represented by AmbeTone. Other symbols retain their exact code. */
    static List<Integer> frequencies(AmbeTone tone)
    {
        if(tone.name().startsWith("HZ_"))
            return List.of((int)Math.round(Double.parseDouble(tone.getValue()) * 1000));
        //Match the released JMBE jmbe.codec.ambe.Tone frequency pairs; retain the stable symbol too.
        if(tone.name().startsWith("DTMF_"))
        {
            int index = tone.ordinal() - AmbeTone.DTMF_0.ordinal();
            int[] high = {1336,1209,1336,1477,1209,1336,1477,1209,1336,1477,1633,1633,1633,1633,1209,1477};
            int[] low = {941,697,697,697,770,770,770,852,852,852,697,770,852,941,941,941};
            return List.of(low[index] * 1000,high[index] * 1000);
        }
        if(tone.name().startsWith("KNOX_"))
        {
            int index = tone.ordinal() - AmbeTone.KNOX_0.ordinal();
            int[] high = {1162,1052,1162,1279,1052,1162,1279,1052,1162,1279,1430,1430,1430,1430,1052,1279};
            int[] low = {820,606,606,606,672,672,672,743,743,743,606,672,743,820,820,820};
            return List.of(low[index] * 1000,high[index] * 1000);
        }
        return switch(tone)
        {
            case DIAL_TONE -> List.of(350000,440000);
            case RINGING_TONE -> List.of(440000,480000);
            case BUSY_TONE -> List.of(480000,620000);
            case CALL_PROGRESS -> List.of(350000,490000);
            default -> List.of();
        };
    }
}
