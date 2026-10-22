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

package io.github.dsheirer.channel;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dsheirer.configuration.ChannelConfigurationPolicy;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.module.decode.DecoderType;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Loads the authoritative protocol-driven channel form and validation catalog. */
public final class ChannelProtocolRegistry
{
    private static final String RESOURCE = "/channel-protocols.json";
    private static final ObjectMapper MAPPER = new ObjectMapper(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());
    private final JsonNode mCatalog;
    private final Map<String,Profile> mById;
    private final Map<DecoderType,Profile> mByDecoder;

    public ChannelProtocolRegistry()
    {
        try(InputStream stream = ChannelProtocolRegistry.class.getResourceAsStream(RESOURCE))
        {
            if(stream == null)
            {
                throw new IllegalStateException("Missing channel protocol catalog " + RESOURCE);
            }
            mCatalog = MAPPER.readTree(stream);
            Map<String,Profile> byId = new LinkedHashMap<>();
            Map<DecoderType,Profile> byDecoder = new EnumMap<>(DecoderType.class);
            JsonNode profiles = requiredArray(mCatalog, "profiles");
            for(JsonNode node: profiles)
            {
                Profile profile = parse(node);
                if(byId.put(profile.id(), profile) != null || byDecoder.put(profile.decoderType(), profile) != null)
                {
                    throw new IllegalStateException("Duplicate channel protocol profile " + profile.id());
                }
            }
            Set<DecoderType> missing = new HashSet<>(DecoderType.PRIMARY_DECODERS);
            missing.removeAll(byDecoder.keySet());
            if(!missing.isEmpty() || byDecoder.keySet().stream().anyMatch(type -> !type.isActive()))
            {
                throw new IllegalStateException("Channel protocol catalog does not match active decoders: " + missing);
            }
            mById = Collections.unmodifiableMap(byId);
            mByDecoder = Collections.unmodifiableMap(byDecoder);
        }
        catch(IOException exception)
        {
            throw new IllegalStateException("Unable to load channel protocol catalog", exception);
        }
    }

    /** A defensive copy safe to send to a browser. */
    public JsonNode catalog()
    {
        return mCatalog.deepCopy();
    }

    public Profile require(String id)
    {
        Profile profile = id != null ? mById.get(id) : null;
        if(profile == null)
        {
            throw new IllegalArgumentException("Unsupported channel protocol: " + id);
        }
        return profile;
    }

    public Profile require(DecoderType decoderType)
    {
        Profile profile = decoderType != null ? mByDecoder.get(decoderType) : null;
        if(profile == null)
        {
            throw new IllegalArgumentException("Unsupported channel decoder: " + decoderType);
        }
        return profile;
    }

    /** Applies defaults, removes inapplicable values, and validates the effective protocol settings. */
    public Map<String,Object> validateSettings(Profile profile, Map<String,Object> submitted)
    {
        return validateSettings(profile, submitted, Map.of());
    }

    /** Prior settings preserve valid dormant preferences omitted by an editor when their dependency is disabled. */
    public Map<String,Object> validateSettings(Profile profile, Map<String,Object> submitted,
                                               Map<String,Object> previous)
    {
        Map<String,Object> normalized = new LinkedHashMap<>(profile.defaults());
        if(submitted != null)
        {
            for(Map.Entry<String,Object> entry: submitted.entrySet())
            {
                FieldRule rule = profile.fields().get("settings." + entry.getKey());
                if(rule == null)
                {
                    throw new IllegalArgumentException("Unknown " + profile.label() + " setting: " + entry.getKey());
                }
                normalized.put(entry.getKey(), entry.getValue());
            }
        }
        profile.fields().values().stream().map(FieldRule::visibleWhen).filter(Objects::nonNull)
            .map(VisibleWhen::path).distinct().forEach(path -> validateScalar(profile.fields().get(path),
                normalized.get(path.substring("settings.".length()))));
        for(FieldRule rule: profile.fields().values())
        {
            if(rule.path().startsWith("settings.") && !isFieldApplicable(profile, rule.path(), normalized))
            {
                String key = rule.path().substring("settings.".length());
                //Channel mode changes remove settings from the protocol's effective feature set. Other conditions
                //only hide dormant tuning preferences, which may be restored when their feature is enabled again.
                if(!"settings.channel_mode".equals(rule.visibleWhen().path()))
                {
                    Object dormant = (submitted == null || !submitted.containsKey(key)) && previous != null &&
                        previous.containsKey(key) ? previous.get(key) : normalized.get(key);
                    try
                    {
                        validateScalar(rule, dormant);
                        normalized.put(key, dormant);
                        continue;
                    }
                    catch(IllegalArgumentException invalidDormantValue)
                    {
                        //Malformed inactive inputs must not prevent saving the active settings.
                    }
                }
                resetToDefault(profile, normalized, key);
            }
        }
        for(Map.Entry<String,Object> entry: normalized.entrySet())
        {
            validateScalar(profile.fields().get("settings." + entry.getKey()), entry.getValue());
        }
        profile.fields().values().stream().filter(rule -> rule.path().startsWith("settings.") && rule.required() &&
            isFieldApplicable(profile, rule.path(), normalized))
            .forEach(rule ->
            {
                String key = rule.path().substring("settings.".length());
                if(!normalized.containsKey(key) || normalized.get(key) == null)
                {
                    throw new IllegalArgumentException(rule.label() + " is required");
                }
            });
        return Collections.unmodifiableMap(normalized);
    }

    /** Keeps legacy active values readable while removing settings that cannot apply to the selected channel mode. */
    public Map<String,Object> normalizeInactiveProtocolSettings(Profile profile, Map<String,Object> settings)
    {
        Map<String,Object> normalized = new LinkedHashMap<>(settings);
        for(FieldRule rule: profile.fields().values())
        {
            if(rule.path().startsWith("settings.") && rule.visibleWhen() != null &&
                "settings.channel_mode".equals(rule.visibleWhen().path()) &&
                !isFieldApplicable(profile, rule.path(), normalized))
            {
                resetToDefault(profile, normalized, rule.path().substring("settings.".length()));
            }
        }
        return Collections.unmodifiableMap(normalized);
    }

    private static void resetToDefault(Profile profile, Map<String,Object> settings, String key)
    {
        if(profile.defaults().containsKey(key)) settings.put(key, profile.defaults().get(key));
        else settings.remove(key);
    }

    /** Evaluates catalog applicability against the effective settings, including their controlling defaults. */
    public boolean isFieldApplicable(Profile profile, String fieldPath, Map<String,Object> settings)
    {
        FieldRule rule = profile.fields().get(fieldPath);
        if(rule == null) throw new IllegalArgumentException("Unknown protocol field: " + fieldPath);
        VisibleWhen condition = rule.visibleWhen();
        if(condition == null) return true;
        String key = condition.path().substring("settings.".length());
        Object actual = settings != null && settings.containsKey(key) ? settings.get(key) : profile.defaults().get(key);
        return actual instanceof Number number && condition.equalsValue() instanceof Number expected ?
            Double.compare(number.doubleValue(), expected.doubleValue()) == 0 :
            Objects.equals(actual, condition.equalsValue());
    }

    private static void validateScalar(FieldRule rule, Object value)
    {
        if(rule == null)
        {
            throw new IllegalArgumentException("Setting is not declared by the protocol catalog");
        }
        if(value == null)
        {
            if(!rule.nullable())
            {
                throw new IllegalArgumentException(rule.label() + " cannot be null");
            }
            return;
        }
        switch(rule.type())
        {
            case "boolean" -> {
                if(!(value instanceof Boolean)) throw typeError(rule);
            }
            case "integer" -> {
                if(!(value instanceof Byte || value instanceof Short || value instanceof Integer ||
                    value instanceof Long)) throw typeError(rule);
                validateRange(rule, ((Number)value).doubleValue());
            }
            case "number" -> {
                if(!(value instanceof Number)) throw typeError(rule);
                double number = ((Number)value).doubleValue();
                if(!Double.isFinite(number)) throw typeError(rule);
                validateRange(rule, number);
                if(!rule.options().isEmpty() && rule.options().stream().noneMatch(option ->
                    Double.parseDouble(option) == number))
                {
                    throw new IllegalArgumentException(rule.label() + " is not an allowed value");
                }
            }
            case "enum" -> {
                if(!(value instanceof String text) || !rule.options().contains(text))
                {
                    throw new IllegalArgumentException(rule.label() + " is not an allowed value");
                }
            }
            default -> throw new IllegalArgumentException("Unsupported scalar protocol field: " + rule.type());
        }
    }

    private static void validateRange(FieldRule rule, double value)
    {
        if(rule.minimum() != null && value < rule.minimum() || rule.maximum() != null && value > rule.maximum())
        {
            throw new IllegalArgumentException(rule.label() + " is outside the allowed range");
        }
    }

    private static IllegalArgumentException typeError(FieldRule rule)
    {
        return new IllegalArgumentException(rule.label() + " has the wrong value type");
    }

    private static Profile parse(JsonNode node)
    {
        String id = requiredText(node, "id");
        DecoderType decoderType = DecoderType.valueOf(requiredText(node, "decoder_type"));
        if(!decoderType.isActive() || !DecoderType.PRIMARY_DECODERS.contains(decoderType))
        {
            throw new IllegalStateException("Protocol catalog advertises a retired or auxiliary decoder: " + decoderType);
        }
        String kindName = requiredText(node, "channel_kind");
        ChannelConfigurationPolicy.ChannelKind kind = "DYNAMIC".equals(kindName) ? null :
            ChannelConfigurationPolicy.ChannelKind.valueOf(kindName);
        AliasListFamily aliasFamily = AliasListFamily.valueOf(requiredText(node, "alias_family"));
        SourceMode sourceMode = SourceMode.valueOf(requiredText(node, "source_mode"));
        Set<String> settingIds = new HashSet<>();
        Map<String,Object> defaults = new HashMap<>();
        Map<String,FieldRule> fields = new LinkedHashMap<>();
        JsonNode sections = requiredArray(node, "sections");
        for(JsonNode section: sections)
        {
            requiredText(section, "id");
            requiredText(section, "label");
            for(JsonNode field: requiredArray(section, "fields"))
            {
                String path = requiredText(field, "path");
                String label = requiredText(field, "label");
                String type = requiredText(field, "type");
                Set<String> options = new HashSet<>();
                JsonNode optionNodes = field.get("options");
                if(optionNodes != null)
                {
                    if(!optionNodes.isArray())
                    {
                        throw new IllegalStateException("Channel protocol field options must be an array: " + path);
                    }
                    for(JsonNode option: optionNodes)
                    {
                        options.add(requiredText(option, "value"));
                    }
                }
                Object defaultValue = field.has("default") ? scalar(field.get("default"), path) : null;
                FieldRule rule = new FieldRule(path, label, type, field.path("required").asBoolean(false),
                    field.path("nullable").asBoolean(false), optionalDouble(field, "minimum"),
                    optionalDouble(field, "maximum"), optionalInteger(field, "number_minimum"),
                    optionalInteger(field, "number_maximum"), field.path("show_uplink").asBoolean(false),
                    Set.copyOf(options), defaultValue, visibleWhen(field));
                if(fields.put(path, rule) != null)
                {
                    throw new IllegalStateException("Duplicate field path in " + id + ": " + path);
                }
                if(path.startsWith("settings."))
                {
                    String settingId = path.substring("settings.".length());
                    if(settingId.isBlank() || !settingIds.add(settingId))
                    {
                        throw new IllegalStateException("Duplicate or blank setting path in " + id + ": " + path);
                    }
                    if(field.has("default"))
                    {
                        defaults.put(settingId, defaultValue);
                    }
                }
            }
        }
        for(FieldRule rule: fields.values())
        {
            if(rule.visibleWhen() != null && (!rule.visibleWhen().path().startsWith("settings.") ||
                !fields.containsKey(rule.visibleWhen().path()) ||
                fields.get(rule.visibleWhen().path()).visibleWhen() != null))
            {
                throw new IllegalStateException("Protocol field condition must reference an unconditional setting: " +
                    rule.path());
            }
        }
        return new Profile(id, requiredText(node, "label"), decoderType, aliasFamily, kind, sourceMode, settingIds,
            defaults, fields, textSet(node, "event_logs"), textSet(node, "recorders"),
            textSet(node, "auxiliary_decoders"));
    }

    private static VisibleWhen visibleWhen(JsonNode field)
    {
        JsonNode condition = field.get("visible_when");
        if(condition == null) return null;
        if(!condition.isObject() || !condition.has("equals"))
            throw new IllegalStateException("Invalid protocol field condition");
        return new VisibleWhen(requiredText(condition, "path"), scalar(condition.get("equals"), "visible_when.equals"));
    }

    private static Double optionalDouble(JsonNode node, String field)
    {
        JsonNode value = node.get(field);
        if(value == null) return null;
        if(!value.isNumber() || !Double.isFinite(value.doubleValue()))
        {
            throw new IllegalStateException("Channel protocol field " + field + " must be numeric");
        }
        return value.doubleValue();
    }

    private static Integer optionalInteger(JsonNode node, String field)
    {
        JsonNode value = node.get(field);
        if(value == null) return null;
        if(!value.canConvertToInt())
        {
            throw new IllegalStateException("Channel protocol field " + field + " must be an integer");
        }
        return value.intValue();
    }

    private static Object scalar(JsonNode value, String path)
    {
        if(value.isNull()) return null;
        if(value.isTextual()) return value.textValue();
        if(value.isBoolean()) return value.booleanValue();
        if(value.isIntegralNumber()) return value.longValue();
        if(value.isFloatingPointNumber()) return value.doubleValue();
        throw new IllegalStateException("Protocol default must be scalar: " + path);
    }

    private static Set<String> textSet(JsonNode node, String field)
    {
        Set<String> values = new HashSet<>();
        for(JsonNode value: requiredArray(node, field))
        {
            if(!value.isTextual() || value.textValue().isBlank() || !values.add(value.textValue()))
            {
                throw new IllegalStateException("Invalid " + field + " in channel protocol catalog");
            }
        }
        return Set.copyOf(values);
    }

    private static JsonNode requiredArray(JsonNode node, String field)
    {
        JsonNode value = node != null ? node.get(field) : null;
        if(value == null || !value.isArray())
        {
            throw new IllegalStateException("Channel protocol catalog requires array " + field);
        }
        return value;
    }

    private static String requiredText(JsonNode node, String field)
    {
        JsonNode value = node != null ? node.get(field) : null;
        if(value == null || !value.isTextual() || value.textValue().isBlank())
        {
            throw new IllegalStateException("Channel protocol catalog requires text " + field);
        }
        return value.textValue();
    }

    public enum SourceMode
    {
        SINGLE,
        MULTIPLE
    }

    public record Profile(String id, String label, DecoderType decoderType, AliasListFamily aliasFamily,
                          ChannelConfigurationPolicy.ChannelKind channelKind, SourceMode sourceMode,
                          Set<String> settingIds, Map<String,Object> defaults, Map<String,FieldRule> fields,
                          Set<String> eventLogs,
                          Set<String> recorders, Set<String> auxiliaryDecoders)
    {
        public Profile
        {
            Objects.requireNonNull(id);
            settingIds = Set.copyOf(settingIds);
            defaults = Map.copyOf(defaults);
            fields = Map.copyOf(fields);
            eventLogs = Set.copyOf(eventLogs);
            recorders = Set.copyOf(recorders);
            auxiliaryDecoders = Set.copyOf(auxiliaryDecoders);
        }

        public Map<String,Object> defaultSettings()
        {
            return Map.copyOf(defaults);
        }
    }

    public record FieldRule(String path, String label, String type, boolean required, boolean nullable,
                            Double minimum, Double maximum, Integer numberMinimum, Integer numberMaximum,
                            boolean showUplink, Set<String> options, Object defaultValue, VisibleWhen visibleWhen)
    {
        public FieldRule
        {
            options = Set.copyOf(options);
        }
    }

    public record VisibleWhen(String path, Object equalsValue) {}
}
