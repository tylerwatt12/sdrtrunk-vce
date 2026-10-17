/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.channel;

import io.github.dsheirer.configuration.ConfigurationEditVersion;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;

/** Optimistic edits of one saved channel; unchanged fields retain the receiver's latest saved values. */
final class ChannelEdit
{
    private ChannelEdit() {}

    static String version(ChannelDefinition channel)
    {
        return ConfigurationEditVersion.of(new ChannelDefinition(channel.configurationId(), channel.protocolId(),
            channel.system(), channel.site(), channel.name(), channel.radioResolveId(), channel.aliasListId(),
            channel.source(), channel.settings(), channel.frequencyMap(), channel.eventLogs(), channel.recorders(),
            channel.auxiliaryDecoders(), ChannelDefinition.Observed.EMPTY));
    }

    static ChannelDefinition merge(String id, String expectedVersion, ChannelDefinition original,
                                   ChannelDefinition submitted, ChannelDefinition current)
    {
        if(expectedVersion == null || expectedVersion.isBlank())
            throw new IllegalArgumentException("Channel edit version is required");
        if(original == null)
        {
            if(!expectedVersion.equals(version(current))) throw new ChannelAdministrationService.StaleChannelException();
            return submitted;
        }
        if(!id.equals(original.configurationId()) || !expectedVersion.equals(version(original)))
            throw new IllegalArgumentException("Original channel settings do not match this edit");

        return new ChannelDefinition(id,
            field(original.protocolId(), submitted.protocolId(), current.protocolId()),
            field(original.system(), submitted.system(), current.system()),
            field(original.site(), submitted.site(), current.site()),
            field(original.name(), submitted.name(), current.name()),
            field(original.radioResolveId(), submitted.radioResolveId(), current.radioResolveId()),
            field(original.aliasListId(), submitted.aliasListId(), current.aliasListId()),
            source(original.source(), submitted.source(), current.source()),
            settings(original.settings(), submitted.settings(), current.settings()),
            field(original.frequencyMap(), submitted.frequencyMap(), current.frequencyMap()),
            field(original.eventLogs(), submitted.eventLogs(), current.eventLogs()),
            field(original.recorders(), submitted.recorders(), current.recorders()),
            field(original.auxiliaryDecoders(), submitted.auxiliaryDecoders(), current.auxiliaryDecoders()),
            current.observed());
    }

    private static ChannelDefinition.Source source(ChannelDefinition.Source original,
                                                   ChannelDefinition.Source submitted,
                                                   ChannelDefinition.Source current)
    {
        return new ChannelDefinition.Source(
            field(original.frequenciesHz(), submitted.frequenciesHz(), current.frequenciesHz()),
            field(original.minimumFrequencyHz(), submitted.minimumFrequencyHz(), current.minimumFrequencyHz()),
            field(original.maximumFrequencyHz(), submitted.maximumFrequencyHz(), current.maximumFrequencyHz()),
            field(original.preferredFrequencyHz(), submitted.preferredFrequencyHz(), current.preferredFrequencyHz()),
            field(original.preferredTuner(), submitted.preferredTuner(), current.preferredTuner()),
            field(original.rotationDelayMs(), submitted.rotationDelayMs(), current.rotationDelayMs()),
            field(original.sourceType(), submitted.sourceType(), current.sourceType()),
            field(original.senderId(), submitted.senderId(), current.senderId()),
            field(original.feedId(), submitted.feedId(), current.feedId()));
    }

    private static Map<String,Object> settings(Map<String,Object> original, Map<String,Object> submitted,
                                               Map<String,Object> current)
    {
        var keys = new LinkedHashSet<>(current.keySet());
        keys.addAll(original.keySet());
        keys.addAll(submitted.keySet());
        Map<String,Object> merged = new LinkedHashMap<>();
        for(String key: keys)
        {
            Object value = field(original.get(key), submitted.get(key), current.get(key));
            if(value != null || submitted.containsKey(key) || current.containsKey(key)) merged.put(key, value);
        }
        return merged;
    }

    private static <T> T field(T original, T submitted, T current)
    {
        if(same(submitted, original)) return current;
        if(same(current, original) || same(submitted, current)) return submitted;
        throw new ChannelAdministrationService.StaleChannelException();
    }

    private static boolean same(Object first, Object second)
    {
        if(first instanceof Number left && second instanceof Number right)
            return new BigDecimal(left.toString()).compareTo(new BigDecimal(right.toString())) == 0;
        return Objects.equals(first, second);
    }
}
