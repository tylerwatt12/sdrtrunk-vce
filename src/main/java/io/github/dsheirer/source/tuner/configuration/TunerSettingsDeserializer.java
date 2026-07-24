/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.source.tuner.configuration;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.ObjectCodec;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads tuner settings one entry at a time so that a configuration for an unavailable tuner implementation cannot
 * prevent supported tuner settings from loading.
 */
public class TunerSettingsDeserializer extends StdDeserializer<TunerSettings>
{
    private static final Logger mLog = LoggerFactory.getLogger(TunerSettingsDeserializer.class);

    public TunerSettingsDeserializer()
    {
        super(TunerSettings.class);
    }

    @Override
    public TunerSettings deserialize(JsonParser parser, DeserializationContext context) throws IOException
    {
        ObjectCodec codec = parser.getCodec();
        JsonNode root = codec.readTree(parser);

        if(root == null || root.isNull())
        {
            return new TunerSettings();
        }

        if(!root.isObject())
        {
            throw JsonMappingException.from(parser, "Tuner settings must be a JSON object");
        }

        EntryList<DisabledTuner> disabledTuners = readEntries(root.get("disabledTuners"), DisabledTuner.class,
            "disabled tuner", codec);
        EntryList<TunerConfiguration> tunerConfigurations = readEntries(root.get("tunerConfigurations"),
            TunerConfiguration.class, "tuner configuration", codec);
        TunerSettings settings = new TunerSettings();
        settings.setDisabledTuners(disabledTuners.entries());
        settings.setTunerConfigurations(tunerConfigurations.entries());
        settings.setIgnoredEntryCount(disabledTuners.ignoredCount() + tunerConfigurations.ignoredCount());
        return settings;
    }

    private static <T> EntryList<T> readEntries(JsonNode entriesNode, Class<T> entryType, String label,
                                                 ObjectCodec codec)
    {
        List<T> entries = new ArrayList<>();
        int ignoredCount = 0;

        if(entriesNode == null || entriesNode.isNull())
        {
            return new EntryList<>(entries, ignoredCount);
        }

        if(!entriesNode.isArray())
        {
            mLog.warn("Ignoring malformed {} list in tuner settings", label);
            return new EntryList<>(entries, 1);
        }

        for(JsonNode entryNode: entriesNode)
        {
            try
            {
                T entry = codec.treeToValue(entryNode, entryType);

                if(isUsable(entry))
                {
                    entries.add(entry);
                }
                else
                {
                    ignoredCount++;
                    mLog.warn("Ignoring incomplete {} entry [{}]", label, describe(entryNode));
                }
            }
            catch(JsonProcessingException | RuntimeException e)
            {
                ignoredCount++;
                mLog.warn("Ignoring unsupported {} entry [{}]: {}", label, describe(entryNode),
                    message(e));
            }
        }

        return new EntryList<>(entries, ignoredCount);
    }

    private static boolean isUsable(Object entry)
    {
        if(entry instanceof DisabledTuner disabledTuner)
        {
            return disabledTuner.tunerClass() != null && disabledTuner.id() != null && !disabledTuner.id().isBlank();
        }

        if(entry instanceof TunerConfiguration tunerConfiguration)
        {
            return tunerConfiguration.getTunerType() != null && tunerConfiguration.getUniqueID() != null &&
                !tunerConfiguration.getUniqueID().isBlank();
        }

        return entry != null;
    }

    private static String describe(JsonNode entryNode)
    {
        if(entryNode != null)
        {
            for(String field: List.of("type", "tunerClass", "id", "uniqueID"))
            {
                JsonNode value = entryNode.get(field);

                if(value != null && value.isValueNode())
                {
                    return value.asText();
                }
            }
        }

        return "unknown";
    }

    private static String message(Exception exception)
    {
        if(exception instanceof JsonProcessingException processingException)
        {
            return processingException.getOriginalMessage();
        }

        return exception.getMessage();
    }

    private record EntryList<T>(List<T> entries, int ignoredCount)
    {
    }
}
