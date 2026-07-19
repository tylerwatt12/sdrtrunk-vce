/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.web.access;

import java.util.Objects;

/**
 * Transport-neutral authorization request.
 */
public record FeatureAccessRequest(WebFeature feature, AuthorizationSubject subject, WebTransport transport)
{
    public FeatureAccessRequest
    {
        Objects.requireNonNull(feature, "Feature cannot be null");
        Objects.requireNonNull(subject, "Authorization subject cannot be null");
        Objects.requireNonNull(transport, "Web transport cannot be null");
    }
}
