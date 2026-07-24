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

/**
 * Stable, credential-free failure returned by the directory service.
 */
public final class RadioReferenceDirectoryException extends Exception
{
    private final Code mCode;

    public RadioReferenceDirectoryException(Code code)
    {
        super(message(code));
        mCode = code;
    }

    public Code code()
    {
        return mCode;
    }

    private static String message(Code code)
    {
        return switch(code)
        {
            case INVALID_REQUEST -> "The RadioReference request is invalid";
            case INVALID_CREDENTIALS -> "RadioReference rejected the credentials";
            case RESULT_SET_TOO_LARGE -> "The RadioReference directory response exceeds the safety limit";
            case INSECURE_TRANSPORT -> "Secure RadioReference credential transport is unavailable";
            case NOT_AUTHENTICATED -> "RadioReference is not signed in";
            case PREMIUM_REQUIRED -> "A current RadioReference premium subscription is required";
            case BUSY -> "RadioReference already has the maximum number of requests waiting";
            case TIMEOUT -> "RadioReference did not respond before the request deadline";
            case UNAVAILABLE -> "RadioReference is unavailable";
            case INTERRUPTED -> "The RadioReference request was interrupted";
            case CLOSED -> "The RadioReference service is closed";
        };
    }

    public enum Code
    {
        INVALID_REQUEST,
        INVALID_CREDENTIALS,
        RESULT_SET_TOO_LARGE,
        INSECURE_TRANSPORT,
        NOT_AUTHENTICATED,
        PREMIUM_REQUIRED,
        BUSY,
        TIMEOUT,
        UNAVAILABLE,
        INTERRUPTED,
        CLOSED
    }
}
