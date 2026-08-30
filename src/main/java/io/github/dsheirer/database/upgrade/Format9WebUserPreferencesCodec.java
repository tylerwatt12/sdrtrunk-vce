/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.database.upgrade;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Strict frozen codec for the version-4 preference document stored by database format 9. */
final class Format9WebUserPreferencesCodec
{
    private static final int VERSION = 4;
    private static final int MAXIMUM_JSON_BYTES = 131_072;
    private static final List<String> PRESENTATION_FIELDS = List.of(
        "show_only_active_trunked_channels", "retain_last_call_on_idle_rows", "clear_voice_quality_when_idle");
    private static final ObjectMapper MAPPER = new ObjectMapper(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private Format9WebUserPreferencesCodec()
    {
    }

    static void validate(String json) throws IOException
    {
        ObjectNode target = readObject(json);
        JsonNode version = target.get("version");
        if(version == null || !version.isIntegralNumber() || !version.canConvertToInt() ||
            version.intValue() != VERSION)
        {
            throw new IOException("Unsupported version-4 web user preference version");
        }

        JsonNode presentation = target.get("presentation");
        if(!(presentation instanceof ObjectNode presentationObject))
        {
            throw new IOException("Version-4 presentation preferences are missing or invalid");
        }

        for(String field: PRESENTATION_FIELDS)
        {
            JsonNode value = presentationObject.get(field);
            if(value == null || !value.isBoolean())
            {
                throw new IOException("Version-4 presentation preference is missing or invalid: " + field);
            }
        }

        ObjectNode prior = target.deepCopy();
        prior.put("version", 3);
        ObjectNode priorPresentation = (ObjectNode)prior.get("presentation");
        priorPresentation.remove(PRESENTATION_FIELDS);
        Format8WebUserPreferencesCodec.validate(MAPPER.writeValueAsString(prior));
    }

    /** Converts one exact format-8 document into one exact format-9 document. */
    static String migrateFromFormat8(String json, boolean retainLastCallOnIdleRows,
                                     boolean clearVoiceQualityWhenIdle) throws IOException
    {
        Format8WebUserPreferencesCodec.validate(json);
        ObjectNode target = readObject(json);
        target.put("version", VERSION);
        ObjectNode presentation = (ObjectNode)target.get("presentation");
        presentation.put("show_only_active_trunked_channels", false);
        presentation.put("retain_last_call_on_idle_rows", retainLastCallOnIdleRows);
        presentation.put("clear_voice_quality_when_idle", clearVoiceQualityWhenIdle);

        String migrated = MAPPER.writeValueAsString(target);
        validate(migrated);
        return migrated;
    }

    private static ObjectNode readObject(String json) throws IOException
    {
        if(json == null || json.getBytes(StandardCharsets.UTF_8).length > MAXIMUM_JSON_BYTES)
        {
            throw new IOException("Version-4 web user preferences are missing or exceed the storage bound");
        }

        JsonNode parsed = MAPPER.readTree(json);
        if(!(parsed instanceof ObjectNode object))
        {
            throw new IOException("Version-4 web user preferences are not a complete object");
        }
        return object;
    }
}
