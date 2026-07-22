/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.web.auth;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Narrow authentication boundary used by the HTTP handler and its admission-policy tests.
 */
interface WebAdminAuthenticationOperations
{
    boolean isConfigured();

    Optional<SingleAdminAuthenticationService.CredentialMetadata> getCredentialMetadata();

    CompletableFuture<SingleAdminAuthenticationService.LoginResult> login(String username, char[] password,
                                                                           String sourceKey);

    Optional<WebAdminSession> resolveSession(String sessionId);

    boolean validateCsrf(String sessionId, String csrfToken);

    boolean logout(String sessionId);
}
