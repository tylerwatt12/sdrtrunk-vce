/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.web.auth;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Objects;

/**
 * Offline/local owner for inspecting, provisioning, resetting, or repairing the single web administrator account.
 *
 * <p>The caller must hold the exclusive portable data-root lock and must not run the radio/web runtime concurrently.
 * Repair is intentionally separate from normal reset: it overwrites only an unreadable credential and starts a fresh
 * auth generation because browser sessions are in-memory and cannot survive the required offline maintenance.</p>
 */
public final class LocalWebAdminProvisioningService
{
    private final WebAdminCredentialStore mCredentialStore;
    private final Pbkdf2PasswordHasher mPasswordHasher;

    public LocalWebAdminProvisioningService(Path databasePath)
    {
        this(new WebAdminCredentialStore(databasePath), new Pbkdf2PasswordHasher());
    }

    LocalWebAdminProvisioningService(WebAdminCredentialStore credentialStore, Pbkdf2PasswordHasher passwordHasher)
    {
        mCredentialStore = Objects.requireNonNull(credentialStore, "Credential store cannot be null");
        mPasswordHasher = Objects.requireNonNull(passwordHasher, "Password hasher cannot be null");
    }

    public Status inspect() throws IOException, SQLException
    {
        try
        {
            return mCredentialStore.load()
                .map(credential -> new Status(State.CONFIGURED, credential.username()))
                .orElseGet(() -> new Status(State.UNCONFIGURED, null));
        }
        catch(UnreadableWebAdminCredentialException exception)
        {
            return new Status(State.UNREADABLE, null);
        }
    }

    public SingleAdminAuthenticationService.CredentialMetadata provisionOrReset(String username, char[] password)
        throws IOException, SQLException
    {
        try(SingleAdminAuthenticationService authenticationService =
                new SingleAdminAuthenticationService(mCredentialStore))
        {
            return authenticationService.provisionOrReset(username, password);
        }
    }

    public SingleAdminAuthenticationService.CredentialMetadata repairUnreadable(String username, char[] password)
        throws IOException, SQLException
    {
        try
        {
            mCredentialStore.load();
        }
        catch(UnreadableWebAdminCredentialException expected)
        {
            char[] copy = password == null ? new char[0] : Arrays.copyOf(password, password.length);

            try
            {
                WebAdminCredential replacement = mPasswordHasher.createCredential(username, copy, 1);
                mCredentialStore.save(replacement);
                return new SingleAdminAuthenticationService.CredentialMetadata(replacement.username(),
                    replacement.passwordChangedAtEpochMillis(), replacement.authGeneration());
            }
            finally
            {
                Arrays.fill(copy, '\u0000');
            }
        }

        throw new IllegalStateException("The web administrator credential is readable and must use normal reset");
    }

    public enum State
    {
        UNCONFIGURED,
        CONFIGURED,
        UNREADABLE
    }

    public record Status(State state, String username)
    {
        public Status
        {
            Objects.requireNonNull(state, "Credential state cannot be null");

            if(state == State.CONFIGURED)
            {
                username = WebAdminCredential.normalizeUsername(username);
            }
            else if(username != null)
            {
                throw new IllegalArgumentException("Only a configured credential can expose a username");
            }
        }
    }
}
