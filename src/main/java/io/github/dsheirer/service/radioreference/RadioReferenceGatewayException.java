/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */
package io.github.dsheirer.service.radioreference;

import java.util.Objects;

/**
 * Sanitized failure from a RadioReference gateway.
 *
 * <p>The exception deliberately does not retain the upstream exception or its message.  Some HTTP client and
 * authentication failures can contain request details, so callers only receive a stable failure kind.</p>
 */
public final class RadioReferenceGatewayException extends Exception
{
    private final Kind mKind;

    public RadioReferenceGatewayException(Kind kind)
    {
        super(message(kind));
        mKind = Objects.requireNonNull(kind);
    }

    public Kind kind()
    {
        return mKind;
    }

    public enum Kind
    {
        INVALID_CREDENTIALS,
        INVALID_LOCATION,
        RESULT_SET_TOO_LARGE,
        INSECURE_TRANSPORT,
        UNAVAILABLE
    }

    private static String message(Kind kind)
    {
        return switch(Objects.requireNonNull(kind))
        {
            case INVALID_CREDENTIALS -> "RadioReference rejected the credentials";
            case INVALID_LOCATION -> "The RadioReference location selection is inconsistent";
            case RESULT_SET_TOO_LARGE -> "The RadioReference directory response exceeds the safety limit";
            case INSECURE_TRANSPORT -> "The RadioReference client does not provide secure credential transport";
            case UNAVAILABLE -> "RadioReference is unavailable";
        };
    }
}
