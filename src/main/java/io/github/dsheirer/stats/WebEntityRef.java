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

    static KeyRef system(String scopeToken)
    {
        return new KeyRef(Kind.SYSTEM, requireText(scopeToken, "System scope"));
    }

    static KeyRef site(String guid)
    {
        return new KeyRef(Kind.SITE, canonicalUuid(guid, "Site GUID"));
    }

    static KeyRef conventional(String configurationId)
    {
        return new KeyRef(Kind.CONVENTIONAL, canonicalUuid(configurationId, "Configuration ID"));
    }

    static ScopedIdentityRef talkgroup(String scopeToken, int identifier)
    {
        return new ScopedIdentityRef(Kind.TALKGROUP, requireText(scopeToken, "System scope"), identifier);
    }

    static ScopedIdentityRef patchGroup(String scopeToken, int identifier)
    {
        return new ScopedIdentityRef(Kind.PATCH_GROUP, requireText(scopeToken, "System scope"), identifier);
    }

    static ScopedIdentityRef radio(String scopeToken, int identifier)
    {
        return new ScopedIdentityRef(Kind.RADIO, requireText(scopeToken, "System scope"), identifier);
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
        SYSTEM("system"), SITE("site"), CONVENTIONAL("conventional"), TALKGROUP("talkgroup"),
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
            if(kind != Kind.SYSTEM && kind != Kind.SITE && kind != Kind.CONVENTIONAL)
            {
                throw new IllegalArgumentException("Key references support only system, site, and conventional");
            }

            key = requireText(key, "Entity key");
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

    record ScopedIdentityRef(Kind kind, String scope, int id) implements WebEntityRef
    {
        public ScopedIdentityRef
        {
            if(kind != Kind.TALKGROUP && kind != Kind.PATCH_GROUP && kind != Kind.RADIO)
            {
                throw new IllegalArgumentException("Scoped references support only group and radio identities");
            }
            if(id <= 0)
            {
                throw new IllegalArgumentException("Identity ID must be positive");
            }

            scope = requireText(scope, "System scope");
        }

        @Override
        public Map<String,Object> toMap()
        {
            Map<String,Object> value = new LinkedHashMap<>();
            value.put("kind", kind.wireName());
            value.put("scope", scope);
            value.put("id", id);
            return Map.copyOf(value);
        }
    }
}
