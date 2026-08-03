/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.web.auth;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Java-standard PBKDF2-HMAC-SHA-256 password verifier, ported from the webfirst authentication core.
 */
public final class Pbkdf2PasswordHasher
{
    public static final int DEFAULT_ITERATIONS = 600_000;
    public static final int SALT_BYTES = 32;
    public static final int MINIMUM_PASSWORD_CHARACTERS = 7;
    public static final int MAXIMUM_PASSWORD_CHARACTERS = 256;
    private static final char[] INVALID_PASSWORD = {'\u0000'};

    private final int mIterations;
    private final SecureRandom mSecureRandom;
    private final Clock mClock;

    public Pbkdf2PasswordHasher()
    {
        this(DEFAULT_ITERATIONS, new SecureRandom(), Clock.systemUTC());
    }

    public Pbkdf2PasswordHasher(int iterations)
    {
        this(iterations, new SecureRandom(), Clock.systemUTC());
    }

    Pbkdf2PasswordHasher(int iterations, SecureRandom secureRandom, Clock clock)
    {
        if(iterations < WebAdminCredential.MINIMUM_ITERATIONS ||
            iterations > WebAdminCredential.MAXIMUM_ITERATIONS)
        {
            throw new IllegalArgumentException("PBKDF2 work factor is outside safe bounds");
        }

        mIterations = iterations;
        mSecureRandom = Objects.requireNonNull(secureRandom, "Secure random cannot be null");
        mClock = Objects.requireNonNull(clock, "Clock cannot be null");
    }

    public WebAdminCredential createCredential(String username, char[] password, long credentialVersion)
    {
        requireNewPassword(password);
        byte[] salt = new byte[SALT_BYTES];
        mSecureRandom.nextBytes(salt);
        byte[] derived = derive(password, salt, mIterations);

        try
        {
            return new WebAdminCredential(WebAdminCredential.CURRENT_VERSION,
                WebAdminCredential.normalizeUsername(username), WebAdminCredential.PBKDF2_SHA256, mIterations,
                WebAdminCredential.DERIVED_KEY_BITS, Base64.getEncoder().encodeToString(salt),
                Base64.getEncoder().encodeToString(derived), positiveNow(), credentialVersion);
        }
        finally
        {
            Arrays.fill(salt, (byte)0);
            Arrays.fill(derived, (byte)0);
        }
    }

    /**
     * Derives and compares the verifier even when the supplied username is invalid or does not match.
     */
    public boolean verify(WebAdminCredential credential, String username, char[] password)
    {
        Objects.requireNonNull(credential, "Web credential cannot be null");
        boolean candidateValid = password != null && password.length > 0 &&
            password.length <= MAXIMUM_PASSWORD_CHARACTERS;
        char[] candidate = candidateValid ? password : INVALID_PASSWORD;
        boolean usernameMatches = false;

        try
        {
            usernameMatches = credential.username().equals(WebAdminCredential.normalizeUsername(username));
        }
        catch(IllegalArgumentException | NullPointerException exception)
        {
            // Continue through the expensive verifier to avoid exposing account-name validity through timing.
        }

        byte[] salt = credential.decodeSalt();
        byte[] expected = credential.decodePasswordHash();
        byte[] actual = derive(candidate, salt, credential.iterations());

        try
        {
            return candidateValid && usernameMatches && MessageDigest.isEqual(expected, actual);
        }
        finally
        {
            Arrays.fill(salt, (byte)0);
            Arrays.fill(expected, (byte)0);
            Arrays.fill(actual, (byte)0);
        }
    }

    private static byte[] derive(char[] password, byte[] salt, int iterations)
    {
        PBEKeySpec keySpec = new PBEKeySpec(password, salt, iterations, WebAdminCredential.DERIVED_KEY_BITS);

        try
        {
            return SecretKeyFactory.getInstance(WebAdminCredential.PBKDF2_SHA256).generateSecret(keySpec).getEncoded();
        }
        catch(GeneralSecurityException exception)
        {
            throw new IllegalStateException("Required web password algorithm is unavailable", exception);
        }
        finally
        {
            keySpec.clearPassword();
        }
    }

    private static void requireNewPassword(char[] password)
    {
        if(password == null || password.length < MINIMUM_PASSWORD_CHARACTERS ||
            password.length > MAXIMUM_PASSWORD_CHARACTERS)
        {
            throw new IllegalArgumentException("Web password length is outside safe bounds");
        }
    }

    private long positiveNow()
    {
        return Math.max(1, mClock.millis());
    }
}
