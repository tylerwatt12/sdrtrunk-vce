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

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Parsed, bounded query parameters for Stats Server requests.
 */
record StatsRequest(Map<String,String> parameters)
{
    static final int DEFAULT_LIMIT = 100;
    static final int MAX_LIMIT = 500;

    static StatsRequest from(URI uri)
    {
        Map<String,String> parameters = new LinkedHashMap<>();
        String query = uri != null ? uri.getRawQuery() : null;

        if(query != null && !query.isBlank())
        {
            for(String parameter: query.split("&"))
            {
                String[] parts = parameter.split("=", 2);
                String key = decode(parts[0]);

                if(!key.isBlank())
                {
                    parameters.putIfAbsent(key, parts.length == 2 ? decode(parts[1]) : "");
                }
            }
        }

        return new StatsRequest(Map.copyOf(parameters));
    }

    String text(String key)
    {
        String value = parameters.get(key);
        return value != null && !value.isBlank() ? value.strip() : null;
    }

    String requiredText(String key)
    {
        String value = text(key);

        if(value == null)
        {
            throw new StatsApiException(400, key + " is required");
        }

        return value;
    }

    int requiredIdentifier(String key)
    {
        Integer value = optionalIdentifier(key);

        if(value == null)
        {
            throw new StatsApiException(400, key + " is required");
        }

        return value;
    }

    Integer optionalIdentifier(String key)
    {
        String value = text(key);

        if(value == null)
        {
            return null;
        }

        try
        {
            String candidate = value.toUpperCase();
            int radix = candidate.startsWith("0X") || candidate.matches(".*[A-F].*") ? 16 : 10;
            candidate = candidate.startsWith("0X") ? candidate.substring(2) : candidate;
            int parsed = Integer.parseInt(candidate, radix);
            return parsed >= 0 ? parsed : null;
        }
        catch(NumberFormatException e)
        {
            throw new StatsApiException(400, key + " is invalid");
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
            throw new StatsApiException(400, key + " is invalid");
        }
    }

    int limit()
    {
        Integer requested = optionalInt("limit");
        return Math.max(1, Math.min(MAX_LIMIT, requested != null ? requested : DEFAULT_LIMIT));
    }

    int offset()
    {
        Integer requested = optionalInt("offset");
        return Math.max(0, requested != null ? requested : 0);
    }

    long beforeId()
    {
        String value = text("before_id");

        if(value == null)
        {
            return Long.MAX_VALUE;
        }

        try
        {
            return Math.max(1, Long.parseLong(value));
        }
        catch(NumberFormatException e)
        {
            throw new StatsApiException(400, "before_id is invalid");
        }
    }

    String search()
    {
        String value = text("q");
        return value != null && value.length() > 100 ? value.substring(0, 100) : value;
    }

    String sort(String defaultSort)
    {
        String value = text("sort");
        return value != null && value.matches("[a-z_]+") ? value : defaultSort;
    }

    boolean descending()
    {
        return !"asc".equalsIgnoreCase(text("direction"));
    }

    private static String decode(String value)
    {
        return URLDecoder.decode(value != null ? value : "", StandardCharsets.UTF_8);
    }
}
