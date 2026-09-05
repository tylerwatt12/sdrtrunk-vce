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

package io.github.dsheirer.stats;

import io.github.dsheirer.module.decode.traffic.RadioSystemIdentityKey;
import io.github.dsheirer.module.decode.traffic.RadioSystemKey;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Closed canonical browser-navigation reference.  A reference contains only durable server-owned identity; display
 * labels, learned text, and URL formatting are deliberately outside this type.
 */
sealed interface WebEntityRef permits WebEntityRef.KeyRef, WebEntityRef.ScopedIdentityRef
{
    String FIELD = "entity_ref";

    Kind kind();

    Map<String,Object> toMap();

    static KeyRef radioSystem(String systemKey)
    {
        return new KeyRef(Kind.RADIO_SYSTEM, systemKey);
    }

    static KeyRef channel(String configurationId)
    {
        return new KeyRef(Kind.CHANNEL, configurationId);
    }

    static ScopedIdentityRef talkgroup(String systemKey, String identityKey)
    {
        return new ScopedIdentityRef(Kind.TALKGROUP, systemKey, identityKey);
    }

    static ScopedIdentityRef patchGroup(String systemKey, String identityKey)
    {
        return new ScopedIdentityRef(Kind.PATCH_GROUP, systemKey, identityKey);
    }

    static ScopedIdentityRef radio(String systemKey, String identityKey)
    {
        return new ScopedIdentityRef(Kind.RADIO, systemKey, identityKey);
    }

    static void put(Map<String,Object> target, WebEntityRef reference)
    {
        put(target, FIELD, reference);
    }

    static void put(Map<String,Object> target, String field, WebEntityRef reference)
    {
        if(target != null && field != null && !field.isBlank() && reference != null)
        {
            target.put(field, reference.toMap());
        }
    }

    private static String requireText(String value, String label)
    {
        if(value == null || value.isBlank())
        {
            throw new IllegalArgumentException(label + " is required");
        }

        return value.strip();
    }

    private static String canonicalUuid(String value, String label)
    {
        try
        {
            String candidate = requireText(value, label);
            String canonical = UUID.fromString(candidate).toString();

            if(!canonical.equals(candidate))
            {
                throw new IllegalArgumentException(label + " must use the canonical lowercase UUID form");
            }

            return canonical;
        }
        catch(IllegalArgumentException exception)
        {
            throw new IllegalArgumentException(label + " must be a UUID", exception);
        }
    }

    enum Kind
    {
        RADIO_SYSTEM("radio_system"), CHANNEL("channel"), TALKGROUP("talkgroup"),
        PATCH_GROUP("patch_group"), RADIO("radio");

        private final String mWireName;

        Kind(String wireName)
        {
            mWireName = wireName;
        }

        String wireName()
        {
            return mWireName;
        }
    }

    record KeyRef(Kind kind, String key) implements WebEntityRef
    {
        public KeyRef
        {
            if(kind != Kind.RADIO_SYSTEM && kind != Kind.CHANNEL)
            {
                throw new IllegalArgumentException("Key references support only radio systems and channels");
            }

            key = kind == Kind.RADIO_SYSTEM ? RadioSystemKey.parse(key) : canonicalUuid(key, "Configuration ID");
        }

        @Override
        public Map<String,Object> toMap()
        {
            Map<String,Object> value = new LinkedHashMap<>();
            value.put("kind", kind.wireName());
            value.put("key", key);
            return Map.copyOf(value);
        }
    }

    record ScopedIdentityRef(Kind kind, String radioSystemKey, String identityKey) implements WebEntityRef
    {
        public ScopedIdentityRef
        {
            if(kind != Kind.TALKGROUP && kind != Kind.PATCH_GROUP && kind != Kind.RADIO)
            {
                throw new IllegalArgumentException("Scoped references support only group and radio identities");
            }
            radioSystemKey = RadioSystemKey.parse(radioSystemKey);
            identityKey = requireText(identityKey, "Identity key");
            RadioSystemIdentityKey.Identity identity = RadioSystemIdentityKey.parse(identityKey);
            int expectedKind = switch(kind)
            {
                case TALKGROUP -> RadioSystemIdentityKey.KIND_TALKGROUP;
                case PATCH_GROUP -> RadioSystemIdentityKey.KIND_PATCH_GROUP;
                case RADIO -> RadioSystemIdentityKey.KIND_RADIO;
                default -> throw new IllegalArgumentException("Unsupported scoped identity kind");
            };

            if(identity.kindCode() != expectedKind)
            {
                throw new IllegalArgumentException("Identity key kind does not match the entity kind");
            }
        }

        @Override
        public Map<String,Object> toMap()
        {
            Map<String,Object> value = new LinkedHashMap<>();
            value.put("kind", kind.wireName());
            value.put("radio_system_key", radioSystemKey);
            value.put("identity_key", identityKey);
            return Map.copyOf(value);
        }
    }
}
