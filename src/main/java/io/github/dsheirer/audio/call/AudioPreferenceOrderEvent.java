/* Copyright (C) 2026 Dennis Sheirer */
package io.github.dsheirer.audio.call;

import io.github.dsheirer.controller.channel.Channel;

/** Refreshes future calls from the live configuration after a channel's startup order changes. */
public record AudioPreferenceOrderEvent(Channel channel)
{
    /** Read inside the source-template CAS update so an older notification cannot restore a stale rank. */
    public Integer order()
    {
        Integer order = channel != null && channel.getUseAutoStartOrderForAudio() && channel.isAutoStart() ?
            channel.getAutoStartOrder() : null;
        return order != null && order > 0 ? order : null;
    }

    /** Retains only the volatile configuration source; audio call snapshots receive an immutable rank value. */
    public static AudioPreferenceOrderEvent capture(Channel channel)
    {
        return new AudioPreferenceOrderEvent(channel);
    }
}
