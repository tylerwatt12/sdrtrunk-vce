/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.web.auth;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Stable, code-owned web capabilities.  Page capabilities cover the page and its backing APIs; API-only features
 * are separate where an administrator may reasonably assign a different tier.
 *
 * <p>Existing read-only features default to public access to preserve upgrade behavior.  Administrative capabilities
 * are fixed at admin access.  New capabilities should use the two-argument constructor, which fails safely to an
 * admin default until a deliberate product default is selected.</p>
 */
public enum WebCapability
{
    SITE_ACCESS("site-access", "Entire website", AccessTier.PUBLIC),
    DASHBOARD_VIEW("dashboard", "Dashboard", AccessTier.PUBLIC),
    LIVE_VIEW("live", "Live", AccessTier.PUBLIC),
    TUNER_SPECTRUM_VIEW("tuner-spectrum", "Tuner Spectrum", AccessTier.ADMIN, false),
    SYSTEMS_VIEW("systems", "Systems & Sites", AccessTier.PUBLIC),
    CONVENTIONAL_VIEW("conventional", "Conventional", AccessTier.PUBLIC),
    ALIASES_VIEW("aliases", "Aliases", AccessTier.PUBLIC),
    CREDITS_VIEW("credits", "Credits", AccessTier.PUBLIC),
    CSV_EXPORT("csv-export", "CSV export", AccessTier.PUBLIC),
    WEB_AUDIO_LISTEN("call-audio", "Call audio", AccessTier.PUBLIC),

    ADMIN_USERS("admin-users", "User management", AccessTier.ADMIN, false),
    ADMIN_ACCESS("admin-access", "Access management", AccessTier.ADMIN, false),
    ADMIN_ALIASES("admin-aliases", "Alias and scan-list management", AccessTier.ADMIN, false),
    ADMIN_AUDIO("admin-audio", "Web audio management", AccessTier.ADMIN, false),
    ADMIN_SETTINGS("admin-settings", "Receiver settings", AccessTier.ADMIN, false),
    RECEIVER_HEALTH("receiver-health", "Receiver Health", AccessTier.ADMIN, false);

    private static final Map<String,WebCapability> BY_ID;
    private final String mId;
    private final String mDisplayName;
    private final AccessTier mDefaultTier;
    private final boolean mConfigurable;

    static
    {
        Map<String,WebCapability> capabilities = new LinkedHashMap<>();

        for(WebCapability capability: values())
        {
            if(capabilities.put(capability.id(), capability) != null)
            {
                throw new IllegalStateException("Duplicate web capability identifier: " + capability.id());
            }
        }

        BY_ID = Collections.unmodifiableMap(capabilities);
    }

    /**
     * Safe default for a newly added, configurable feature.
     */
    WebCapability(String id, String displayName)
    {
        this(id, displayName, AccessTier.ADMIN, true);
    }

    WebCapability(String id, String displayName, AccessTier defaultTier)
    {
        this(id, displayName, defaultTier, true);
    }

    WebCapability(String id, String displayName, AccessTier defaultTier, boolean configurable)
    {
        mId = Objects.requireNonNull(id, "Web capability identifier cannot be null");
        mDisplayName = Objects.requireNonNull(displayName, "Web capability display name cannot be null");
        mDefaultTier = Objects.requireNonNull(defaultTier, "Web capability default tier cannot be null");
        mConfigurable = configurable;

        if(!IdPatternHolder.PATTERN.matcher(mId).matches() || mId.length() > 64)
        {
            throw new IllegalArgumentException("Web capability identifier is invalid");
        }

        if(mDisplayName.isBlank() || mDisplayName.length() > 80)
        {
            throw new IllegalArgumentException("Web capability display name is invalid");
        }
    }

    public String id()
    {
        return mId;
    }

    public String displayName()
    {
        return mDisplayName;
    }

    public AccessTier defaultTier()
    {
        return mDefaultTier;
    }

    public boolean configurable()
    {
        return mConfigurable;
    }

    public static Optional<WebCapability> fromId(String id)
    {
        return id == null ? Optional.empty() : Optional.ofNullable(BY_ID.get(id));
    }

    public static Map<String,WebCapability> registry()
    {
        return BY_ID;
    }

    private static final class IdPatternHolder
    {
        private static final Pattern PATTERN = Pattern.compile("[a-z][a-z0-9]*(?:-[a-z0-9]+)*");
    }
}
