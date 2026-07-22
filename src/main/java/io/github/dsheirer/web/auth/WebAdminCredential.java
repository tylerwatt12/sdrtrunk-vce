/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.web.auth;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Versioned, single-account web administrator password verifier.
 *
 * <p>This is current configuration, not authentication history.  It contains no recoverable password and is intended
 * to be stored as the one {@code web.auth.v1} value in {@code application_settings}.</p>
 */
public record WebAdminCredential(int version, String username, String algorithm, int iterations, int derivedKeyBits,
                                 String saltBase64, String passwordHashBase64, long passwordChangedAtEpochMillis,
                                 long authGeneration)
{
    public static final int CURRENT_VERSION = 1;
    public static final String PBKDF2_SHA256 = "PBKDF2WithHmacSHA256";
    public static final int MINIMUM_ITERATIONS = 600_000;
    public static final int MAXIMUM_ITERATIONS = 5_000_000;
    public static final int DERIVED_KEY_BITS = 256;
    public static final int MINIMUM_SALT_BYTES = 16;
    public static final int MAXIMUM_SALT_BYTES = 64;
    public static final int MAXIMUM_USERNAME_CHARACTERS = 64;
    private static final Pattern USERNAME_PATTERN = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

    public WebAdminCredential
    {
        if(version != CURRENT_VERSION)
        {
            throw new IllegalArgumentException("Unsupported web administrator credential version");
        }

        username = normalizeUsername(username);

        if(!PBKDF2_SHA256.equals(algorithm))
        {
            throw new IllegalArgumentException("Unsupported web administrator password algorithm");
        }

        if(iterations < MINIMUM_ITERATIONS || iterations > MAXIMUM_ITERATIONS)
        {
            throw new IllegalArgumentException("Web administrator password work factor is outside safe bounds");
        }

        if(derivedKeyBits != DERIVED_KEY_BITS)
        {
            throw new IllegalArgumentException("Unsupported web administrator derived-key size");
        }

        requireDecodedLength(saltBase64, MINIMUM_SALT_BYTES, MAXIMUM_SALT_BYTES,
            "Web administrator password salt");
        requireDecodedLength(passwordHashBase64, DERIVED_KEY_BITS / Byte.SIZE, DERIVED_KEY_BITS / Byte.SIZE,
            "Web administrator password verifier");

        if(passwordChangedAtEpochMillis <= 0)
        {
            throw new IllegalArgumentException("Web administrator password-change time must be positive");
        }

        if(authGeneration < 1)
        {
            throw new IllegalArgumentException("Web administrator auth generation must be positive");
        }
    }

    /**
     * Normalizes the only configured account name and rejects ambiguous or unbounded names.
     */
    public static String normalizeUsername(String username)
    {
        Objects.requireNonNull(username, "Web administrator username cannot be null");
        String normalized = Normalizer.normalize(username.strip(), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);

        if(normalized.length() > MAXIMUM_USERNAME_CHARACTERS || !USERNAME_PATTERN.matcher(normalized).matches())
        {
            throw new IllegalArgumentException(
                "Web administrator username must contain only lowercase letters, numbers, dot, underscore, or hyphen");
        }

        return normalized;
    }

    byte[] decodeSalt()
    {
        return Base64.getDecoder().decode(saltBase64);
    }

    byte[] decodePasswordHash()
    {
        return Base64.getDecoder().decode(passwordHashBase64);
    }

    private static void requireDecodedLength(String encoded, int minimumBytes, int maximumBytes, String label)
    {
        Objects.requireNonNull(encoded, label + " cannot be null");
        byte[] decoded;

        try
        {
            decoded = Base64.getDecoder().decode(encoded);
        }
        catch(IllegalArgumentException exception)
        {
            throw new IllegalArgumentException(label + " is not valid Base64", exception);
        }

        if(decoded.length < minimumBytes || decoded.length > maximumBytes)
        {
            Arrays.fill(decoded, (byte)0);
            throw new IllegalArgumentException(label + " length is outside safe bounds");
        }

        Arrays.fill(decoded, (byte)0);
    }

    /**
     * Never include the verifier or salt in incidental logs and diagnostics.
     */
    @Override
    public String toString()
    {
        return "WebAdminCredential[version=" + version + ", username=" + username + ", algorithm=" + algorithm +
            ", iterations=" + iterations + ", derivedKeyBits=" + derivedKeyBits + ", passwordChangedAtEpochMillis=" +
            passwordChangedAtEpochMillis + ", authGeneration=" + authGeneration + ", verifier=<redacted>]";
    }
}
