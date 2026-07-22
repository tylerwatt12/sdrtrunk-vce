/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.web.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class Pbkdf2PasswordHasherTest
{
    private final Pbkdf2PasswordHasher mHasher = new Pbkdf2PasswordHasher(
        WebAdminCredential.MINIMUM_ITERATIONS, new SecureRandom(),
        Clock.fixed(Instant.ofEpochMilli(1_234), ZoneOffset.UTC));

    @Test
    void createsSaltedVerifierAndUsesNormalizedSingleUsername()
    {
        char[] password = "correct horse battery staple".toCharArray();
        WebAdminCredential first = mHasher.createCredential("  ADMIN.One  ", password, 1);
        WebAdminCredential second = mHasher.createCredential("admin.one", password, 2);

        assertEquals("admin.one", first.username());
        assertEquals(1_234, first.passwordChangedAtEpochMillis());
        assertNotEquals(first.saltBase64(), second.saltBase64());
        assertNotEquals(first.passwordHashBase64(), second.passwordHashBase64());
        assertTrue(mHasher.verify(first, "Admin.One", password));
        assertFalse(mHasher.verify(first, "another-admin", password));
        assertFalse(mHasher.verify(first, "admin.one", "wrong password".toCharArray()));
        assertFalse(first.toString().contains(first.passwordHashBase64()));
        assertTrue(first.toString().contains("<redacted>"));
    }

    @Test
    void rejectsUnsafeCredentialParametersAndPasswordLengths()
    {
        assertThrows(IllegalArgumentException.class,
            () -> new Pbkdf2PasswordHasher(WebAdminCredential.MINIMUM_ITERATIONS - 1));
        assertThrows(IllegalArgumentException.class,
            () -> mHasher.createCredential("admin", "short".toCharArray(), 1));
        assertThrows(IllegalArgumentException.class,
            () -> new WebAdminCredential(1, "admin", WebAdminCredential.PBKDF2_SHA256,
                WebAdminCredential.MINIMUM_ITERATIONS - 1, 256, "invalid", "invalid", 1, 1));
        assertThrows(IllegalArgumentException.class,
            () -> WebAdminCredential.normalizeUsername("administrator@example.com"));
    }
}
