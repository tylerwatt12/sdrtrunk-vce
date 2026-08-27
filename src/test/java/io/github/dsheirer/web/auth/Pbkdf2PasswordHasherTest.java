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
        WebPasswordVerifier.MINIMUM_ITERATIONS, new SecureRandom(),
        Clock.fixed(Instant.ofEpochMilli(1_234), ZoneOffset.UTC));

    @Test
    void createsUniqueSaltedVerifierAndUsesNormalizedUsername()
    {
        char[] password = "correct horse battery staple".toCharArray();
        WebPasswordVerifier first = mHasher.createVerifier("  User.One  ", password, 1);
        WebPasswordVerifier second = mHasher.createVerifier("user.one", password, 2);

        assertEquals("user.one", first.username());
        assertEquals(1_234, first.passwordChangedAtEpochMillis());
        assertEquals(600_000, first.iterations());
        assertEquals(256, first.derivedKeyBits());
        assertNotEquals(first.saltBase64(), second.saltBase64());
        assertNotEquals(first.passwordHashBase64(), second.passwordHashBase64());
        assertTrue(mHasher.verify(first, "USER.ONE", password));
        assertFalse(mHasher.verify(first, "another-user", password));
        assertFalse(mHasher.verify(first, "user.one", "wrong password".toCharArray()));
        assertFalse(first.toString().contains(first.passwordHashBase64()));
        assertFalse(first.toString().contains(first.saltBase64()));
        assertTrue(first.toString().contains("<redacted>"));
    }

    @Test
    void rejectsUnsafeCredentialParametersAndPasswordLengths()
    {
        assertEquals(7, Pbkdf2PasswordHasher.MINIMUM_PASSWORD_CHARACTERS);
        assertThrows(IllegalArgumentException.class,
            () -> new Pbkdf2PasswordHasher(WebPasswordVerifier.MINIMUM_ITERATIONS - 1));
        assertThrows(IllegalArgumentException.class,
            () -> mHasher.createVerifier("admin", "six666".toCharArray(), 1));
        WebPasswordVerifier minimumLength = mHasher.createVerifier("admin", "seven77".toCharArray(), 1);
        assertTrue(mHasher.verify(minimumLength, "admin", "seven77".toCharArray()));
        assertThrows(IllegalArgumentException.class,
            () -> new WebPasswordVerifier(1, "admin", WebPasswordVerifier.PBKDF2_SHA256,
                WebPasswordVerifier.MINIMUM_ITERATIONS - 1, 256, "invalid", "invalid", 1, 1));
        assertThrows(IllegalArgumentException.class,
            () -> WebPasswordVerifier.normalizeUsername("administrator@example.com"));
    }
}
