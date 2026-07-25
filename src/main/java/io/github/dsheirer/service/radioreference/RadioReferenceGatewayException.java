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
 * authentication failures can contain request details, so callers only receive a stable, credential-free failure
 * kind.  This exception itself can safely be retained as the cause of a higher-level exception.</p>
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
        TIMEOUT,
        INTERRUPTED,
        HTTP_ERROR,
        REQUEST_ENCODING,
        INVALID_RESPONSE,
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
            case TIMEOUT -> "RadioReference did not respond before the request deadline";
            case INTERRUPTED -> "The RadioReference request was interrupted";
            case HTTP_ERROR -> "RadioReference returned an HTTP error";
            case REQUEST_ENCODING -> "The RadioReference request could not be encoded";
            case INVALID_RESPONSE -> "RadioReference returned an invalid response";
            case UNAVAILABLE -> "RadioReference is unavailable";
        };
    }
}
