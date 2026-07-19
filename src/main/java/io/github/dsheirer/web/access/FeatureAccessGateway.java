/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.web.access;

/**
 * Single authorization gateway for request/response and long-lived web transports.
 */
@FunctionalInterface
public interface FeatureAccessGateway
{
    FeatureAccessDecision authorize(FeatureAccessRequest request);

    default FeatureAccessDecision authorize(WebFeature feature, AuthorizationSubject subject, WebTransport transport)
    {
        return authorize(new FeatureAccessRequest(feature, subject, transport));
    }
}
