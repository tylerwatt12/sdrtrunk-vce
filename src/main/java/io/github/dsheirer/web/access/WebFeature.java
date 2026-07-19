/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.web.access;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Web features with stable identifiers for access-policy configuration.  Identifiers, rather than enum names or
 * ordinals, form the external contract.
 */
public enum WebFeature
{
    STATUS_STATISTICS("status-statistics"),
    CALL_AUDIO("call-audio"),
    WIDEBAND_SIGNAL("wideband-signal"),
    SELECTED_CHANNEL_SIGNAL("selected-channel-signal"),
    EVENTS("events"),
    MESSAGES("messages");

    private static final Map<String,WebFeature> FEATURES_BY_ID;

    static
    {
        Map<String,WebFeature> featuresById = new LinkedHashMap<>();

        for(WebFeature feature: values())
        {
            WebFeature previous = featuresById.put(feature.mId, feature);

            if(previous != null)
            {
                throw new IllegalStateException("Duplicate web feature identifier: " + feature.mId);
            }
        }

        FEATURES_BY_ID = Collections.unmodifiableMap(featuresById);
    }

    private final String mId;

    WebFeature(String id)
    {
        mId = id;
    }

    /**
     * Stable external identifier.
     */
    public String getId()
    {
        return mId;
    }

    /**
     * Resolves a stable external identifier.
     *
     * @throws IllegalArgumentException if the identifier is unknown
     */
    public static WebFeature fromId(String id)
    {
        WebFeature feature = FEATURES_BY_ID.get(id);

        if(feature == null)
        {
            throw new IllegalArgumentException("Unknown web feature identifier: " + id);
        }

        return feature;
    }
}
