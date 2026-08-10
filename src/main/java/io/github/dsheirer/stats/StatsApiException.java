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

package io.github.dsheirer.stats;

/**
 * HTTP-safe API error. Messages must not contain SQL, credentials, or private configuration values.
 */
class StatsApiException extends RuntimeException
{
    private final int mStatus;
    private final String mCode;
    private final String mField;

    StatsApiException(int status, String message)
    {
        this(status, defaultCode(status), message, null);
    }

    StatsApiException(int status, String code, String message)
    {
        this(status, code, message, null);
    }

    StatsApiException(int status, String code, String message, String field)
    {
        super(message);
        mStatus = status;
        mCode = code;
        mField = field;
    }

    int status()
    {
        return mStatus;
    }

    String code()
    {
        return mCode;
    }

    String field()
    {
        return mField;
    }

    private static String defaultCode(int status)
    {
        return switch(status)
        {
            case 400 -> "invalid_request";
            case 401 -> "authentication_required";
            case 403 -> "forbidden";
            case 404 -> "not_found";
            case 405 -> "method_not_allowed";
            case 409 -> "conflict";
            case 413 -> "response_too_large";
            case 429 -> "too_many_requests";
            case 503 -> "service_unavailable";
            default -> "request_failed";
        };
    }
}
