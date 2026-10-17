/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.configuration;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Content version for the saved settings owned by one editor, independent of receiver runtime state. */
public final class ConfigurationEditVersion
{
    private static final ObjectMapper JSON = JsonMapper.builder()
        .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .build();
    // Saved documents may contain hidden credentials. A keyed version cannot verify guesses of those values.
    private static final byte[] KEY = new byte[32];
    static { new SecureRandom().nextBytes(KEY); }

    private ConfigurationEditVersion() {}

    public static String of(Object settings)
    {
        try
        {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(KEY, "HmacSHA256"));
            // Parse the serialized numbers first: Float0.1 and browser Double0.1 have the same wire value.
            JsonNode document = JSON.readTree(JSON.writeValueAsBytes(settings));
            return HexFormat.of().formatHex(mac.doFinal(JSON.writeValueAsBytes(canonical(document))));
        }
        catch(IOException | GeneralSecurityException exception)
        {
            throw new IllegalStateException("Unable to version saved configuration", exception);
        }
    }

    private static JsonNode canonical(JsonNode value)
    {
        if(value.isNumber()) return JSON.getNodeFactory().numberNode(value.decimalValue().stripTrailingZeros());
        if(value.isArray())
        {
            var array = JSON.createArrayNode();
            value.forEach(item -> array.add(canonical(item)));
            return array;
        }
        if(value.isObject())
        {
            var object = JSON.createObjectNode();
            value.properties().stream().sorted(Map.Entry.comparingByKey())
                .forEach(field -> object.set(field.getKey(), canonical(field.getValue())));
            return object;
        }
        return value;
    }
}
