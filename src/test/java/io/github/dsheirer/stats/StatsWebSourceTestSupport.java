/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Shared source boundaries for contracts; these preserve the existing scanners, not a JavaScript grammar. */
final class StatsWebSourceTestSupport
{
    private StatsWebSourceTestSupport() {}

    static String function(String source, String signature)
    {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, () -> "Missing " + signature);
        int end = closingBrace(source, source.indexOf('{', start + signature.length()), false);
        if(end < 0)
        {
            throw new AssertionError("Unterminated " + signature);
        }
        return source.substring(start, end);
    }

    static String settingsFunction(String source, String marker)
    {
        int start = source.indexOf(marker);
        assertTrue(start >= 0, marker);
        int end = closingBrace(source, source.indexOf('{', start + marker.length()), false);
        if(end < 0)
        {
            throw new AssertionError("Unclosed function: " + marker);
        }
        return source.substring(start, end);
    }

    static String quotedFunction(String source, String signature, String unclosedMessage)
    {
        int start = source.indexOf(signature);
        if(start < 0)
        {
            throw new IllegalArgumentException("Missing " + signature);
        }
        int end = closingBrace(source, source.indexOf('{', start + signature.length()), true);
        if(end < 0)
        {
            throw new IllegalArgumentException(unclosedMessage + signature);
        }
        return source.substring(start, end);
    }

    private static int closingBrace(String source, int openingBrace, boolean ignoreQuotedText)
    {
        int depth = 0;
        char quote = 0;
        boolean escaped = false;
        for(int index = openingBrace; index < source.length(); index++)
        {
            char character = source.charAt(index);
            if(ignoreQuotedText)
            {
                if(escaped)
                {
                    escaped = false;
                    continue;
                }
                if(quote != 0)
                {
                    if(character == '\\') escaped = true;
                    else if(character == quote) quote = 0;
                    continue;
                }
                if(character == '\'' || character == '"' || character == '`')
                {
                    quote = character;
                    continue;
                }
            }
            if(character == '{')
            {
                depth++;
            }
            else if(character == '}' && --depth == 0)
            {
                return index + 1;
            }
        }
        return -1;
    }
}
