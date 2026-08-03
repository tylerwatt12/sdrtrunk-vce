/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.web.auth;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * One versioned, bounded current-value document persisted under {@code web.access.v1}.
 */
record WebAccessConfiguration(int formatVersion, WebAdminCredential primaryAdmin, List<WebStoredUser> users,
                              Map<String,AccessTier> policyOverrides)
{
    static final int CURRENT_FORMAT_VERSION = 1;
    static final int MAXIMUM_USERS = 256;

    WebAccessConfiguration
    {
        if(formatVersion != CURRENT_FORMAT_VERSION)
        {
            throw new IllegalArgumentException("Unsupported web access configuration format");
        }

        Objects.requireNonNull(users, "Web users cannot be null");
        Objects.requireNonNull(policyOverrides, "Web policy overrides cannot be null");

        if(users.size() > MAXIMUM_USERS)
        {
            throw new IllegalArgumentException("Web user count exceeds the configured bound");
        }

        if(primaryAdmin == null && (!users.isEmpty() || !policyOverrides.isEmpty()))
        {
            throw new IllegalArgumentException(
                "Web users and policy overrides require a JavaFX-provisioned primary administrator");
        }

        if(primaryAdmin != null && !WebAccessService.PRIMARY_ADMIN_USERNAME.equals(primaryAdmin.username()))
        {
            throw new IllegalArgumentException("The primary web administrator username must be admin");
        }

        List<WebStoredUser> canonicalUsers = new ArrayList<>(users.size());
        Set<String> usernames = new HashSet<>();

        for(WebStoredUser user: users)
        {
            Objects.requireNonNull(user, "Web user cannot be null");

            if(!usernames.add(user.credential().username()))
            {
                throw new IllegalArgumentException("Duplicate web username in access configuration");
            }

            canonicalUsers.add(user);
        }

        canonicalUsers.sort((first, second) -> first.credential().username().compareTo(second.credential().username()));
        users = List.copyOf(canonicalUsers);

        if(policyOverrides.size() > WebCapability.values().length)
        {
            throw new IllegalArgumentException("Web policy override count exceeds the configured bound");
        }

        Map<String,AccessTier> canonicalOverrides = new LinkedHashMap<>();

        for(Map.Entry<String,AccessTier> entry: policyOverrides.entrySet())
        {
            WebCapability capability = WebCapability.fromId(entry.getKey())
                .orElseThrow(() -> new IllegalArgumentException("Unknown web capability in access configuration"));
            AccessTier tier = Objects.requireNonNull(entry.getValue(), "Web policy tier cannot be null");

            if(!capability.configurable())
            {
                throw new IllegalArgumentException("A fixed web capability cannot have a policy override");
            }

            if(tier != capability.defaultTier())
            {
                canonicalOverrides.put(capability.id(), tier);
            }
        }

        policyOverrides = Map.copyOf(canonicalOverrides);
    }

    static WebAccessConfiguration empty()
    {
        return new WebAccessConfiguration(CURRENT_FORMAT_VERSION, null, List.of(), Map.of());
    }

    WebAccessConfiguration withPrimaryAdmin(WebAdminCredential replacement)
    {
        return new WebAccessConfiguration(formatVersion, Objects.requireNonNull(replacement), users, policyOverrides);
    }

    WebAccessConfiguration withUsers(List<WebStoredUser> replacement)
    {
        return new WebAccessConfiguration(formatVersion, primaryAdmin, replacement, policyOverrides);
    }

    WebAccessConfiguration withPolicyOverrides(Map<String,AccessTier> replacement)
    {
        return new WebAccessConfiguration(formatVersion, primaryAdmin, users, replacement);
    }

    @Override
    public String toString()
    {
        return "WebAccessConfiguration[formatVersion=" + formatVersion + ", primaryAdminConfigured=" +
            (primaryAdmin != null) + ", users=" + users.size() + ", policyOverrides=" + policyOverrides + "]";
    }
}
