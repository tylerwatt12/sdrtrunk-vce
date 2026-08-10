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

import io.github.dsheirer.web.http.ApiRequestDecoder;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Strict, bounded query parameters for the version-one Stats API.
 */
final class StatsRequest
{
    static final int DEFAULT_LIMIT = 100;
    static final int MAX_LIMIT = 500;
    static final int MAX_OFFSET = 100_000;
    static final int MAX_QUERY_LENGTH = 8_192;
    static final int MAX_PARAMETER_COUNT = 32;
    static final int MAX_PARAMETER_NAME_LENGTH = 64;
    static final int MAX_PARAMETER_VALUE_LENGTH = 1_024;
    static final int MAX_SEARCH_LENGTH = 100;

    private final Map<String,String> mParameters;
    private final Set<String> mConsumed;

    StatsRequest(Map<String,String> parameters)
    {
        this(parameters, new LinkedHashSet<>());
    }

    private StatsRequest(Map<String,String> parameters, Set<String> consumed)
    {
        mParameters = Collections.unmodifiableMap(new LinkedHashMap<>(parameters != null ? parameters : Map.of()));
        mConsumed = consumed;
    }

    static StatsRequest from(URI uri)
    {
        Map<String,String> parameters = new LinkedHashMap<>();
        String query = uri != null ? uri.getRawQuery() : null;

        if(query == null || query.isBlank())
        {
            return new StatsRequest(parameters);
        }

        if(query.length() > MAX_QUERY_LENGTH)
        {
            throw invalid("query", "query is too long");
        }

        for(String parameter: query.split("&", -1))
        {
            if(parameters.size() >= MAX_PARAMETER_COUNT)
            {
                throw invalid("query", "query contains too many parameters");
            }

            String[] parts = parameter.split("=", 2);
            String key = decode(parts[0], "query", true);
            String value = parts.length == 2 ? decode(parts[1], key, true) : "";

            if(key.isBlank())
            {
                throw invalid("query", "query parameter name is required");
            }
            else if(key.length() > MAX_PARAMETER_NAME_LENGTH)
            {
                throw invalid("query", "query parameter name is too long");
            }
            else if(value.length() > MAX_PARAMETER_VALUE_LENGTH)
            {
                throw invalid(key, key + " is too long");
            }
            else if(parameters.putIfAbsent(key, value) != null)
            {
                throw invalid(key, key + " must not be repeated");
            }
        }

        return new StatsRequest(parameters);
    }

    StatsRequest withPathParameter(String key, Object value)
    {
        if(key == null || key.isBlank() || value == null)
        {
            throw new IllegalArgumentException("Path parameter name and value are required");
        }

        if(mParameters.containsKey(key))
        {
            throw invalid(key, key + " must be supplied in the path only");
        }

        Map<String,String> combined = new LinkedHashMap<>(mParameters);
        combined.put(key, String.valueOf(value));
        return new StatsRequest(combined, mConsumed);
    }

    String text(String key)
    {
        mConsumed.add(key);
        String value = mParameters.get(key);
        return value != null && !value.isBlank() ? value.strip() : null;
    }

    String requiredText(String key)
    {
        String value = text(key);

        if(value == null)
        {
            throw invalid(key, key + " is required");
        }

        return value;
    }

    int requiredIdentifier(String key)
    {
        Integer value = optionalIdentifier(key);

        if(value == null)
        {
            throw invalid(key, key + " is required");
        }

        return value;
    }

    /** Identifier paths use one unambiguous decimal representation. */
    Integer optionalIdentifier(String key)
    {
        String value = text(key);

        if(value == null)
        {
            return null;
        }

        try
        {
            if(!value.matches("[0-9]+"))
            {
                throw new NumberFormatException();
            }

            int parsed = Integer.parseInt(value);

            if(parsed < 0)
            {
                throw new NumberFormatException();
            }

            return parsed;
        }
        catch(NumberFormatException e)
        {
            throw invalid(key, key + " must be a non-negative decimal integer");
        }
    }

    Integer optionalInt(String key)
    {
        String value = text(key);

        if(value == null)
        {
            return null;
        }

        try
        {
            return Integer.valueOf(value);
        }
        catch(NumberFormatException e)
        {
            throw invalid(key, key + " must be an integer");
        }
    }

    Long optionalLong(String key)
    {
        String value = text(key);

        if(value == null)
        {
            return null;
        }

        try
        {
            return Long.valueOf(value);
        }
        catch(NumberFormatException e)
        {
            throw invalid(key, key + " must be an integer");
        }
    }

    boolean booleanValue(String key, boolean defaultValue)
    {
        Boolean value = optionalBoolean(key);

        return value != null ? value : defaultValue;
    }

    /**
     * Parses an optional strict boolean while preserving the distinction between an omitted filter and false.
     */
    Boolean optionalBoolean(String key)
    {
        String value = text(key);

        if(value == null)
        {
            return null;
        }
        else if("true".equalsIgnoreCase(value))
        {
            return Boolean.TRUE;
        }
        else if("false".equalsIgnoreCase(value))
        {
            return Boolean.FALSE;
        }

        throw invalid(key, key + " must be true or false");
    }

    int limit()
    {
        return limit(MAX_LIMIT);
    }

    int limit(int maximum)
    {
        if(maximum < 1 || maximum > MAX_LIMIT)
        {
            throw new IllegalArgumentException("Endpoint limit must be between 1 and " + MAX_LIMIT);
        }

        Integer requested = optionalInt("limit");
        int value = requested != null ? requested : Math.min(DEFAULT_LIMIT, maximum);

        if(value < 1 || value > maximum)
        {
            throw invalid("limit", "limit must be between 1 and " + maximum);
        }

        return value;
    }

    int offset()
    {
        Integer requested = optionalInt("offset");
        int value = requested != null ? requested : 0;

        if(value < 0 || value > MAX_OFFSET)
        {
            throw invalid("offset", "offset must be between 0 and " + MAX_OFFSET);
        }

        return value;
    }

    long beforeId()
    {
        Long value = optionalLong("before_id");

        if(value == null)
        {
            return Long.MAX_VALUE;
        }
        else if(value < 1)
        {
            throw invalid("before_id", "before_id must be a positive integer");
        }

        return value;
    }

    String search()
    {
        String value = text("q");

        if(value != null && value.length() > MAX_SEARCH_LENGTH)
        {
            throw invalid("q", "q must not exceed " + MAX_SEARCH_LENGTH + " characters");
        }

        return value;
    }

    String sort(String defaultSort)
    {
        String value = text("sort");

        if(value == null)
        {
            return defaultSort;
        }
        else if(!value.matches("[a-z][a-z0-9_]{0,63}"))
        {
            throw invalid("sort", "sort is invalid");
        }

        return value;
    }

    boolean descending()
    {
        return descending(true);
    }

    boolean descending(boolean defaultValue)
    {
        String direction = text("direction");

        if(direction == null)
        {
            return defaultValue;
        }
        else if("desc".equalsIgnoreCase(direction))
        {
            return true;
        }
        else if("asc".equalsIgnoreCase(direction))
        {
            return false;
        }

        throw invalid("direction", "direction must be asc or desc");
    }

    void requireFullyConsumed()
    {
        rejectUnknown(mConsumed);
    }

    /**
     * Rejects endpoint-specific unknown query parameters before any database or export work begins.  This does not
     * consume accepted parameters; the endpoint still has to parse every accepted value before returning.
     */
    void requireOnly(String... allowedNames)
    {
        Set<String> allowed = Set.of(allowedNames != null ? allowedNames : new String[0]);
        rejectUnknown(allowed);
    }

    private void rejectUnknown(Set<String> allowed)
    {
        List<String> unknown = new ArrayList<>();

        for(String key: mParameters.keySet())
        {
            if(!allowed.contains(key))
            {
                unknown.add(key);
            }
        }

        if(!unknown.isEmpty())
        {
            String field = unknown.getFirst();
            throw new StatsApiException(400, "unknown_parameter", field + " is not supported", field);
        }
    }

    static String decodePathSegment(String value)
    {
        return decode(value, "path", false);
    }

    private static String decode(String value, String field, boolean plusAsSpace)
    {
        try
        {
            return ApiRequestDecoder.decodeComponent(value, plusAsSpace);
        }
        catch(IllegalArgumentException exception)
        {
            throw invalid(field, field + " contains invalid percent encoding");
        }
    }

    private static StatsApiException invalid(String field, String message)
    {
        return new StatsApiException(400, "invalid_parameter", message, field);
    }
}
