/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.web;

import java.util.Locale;

/** Shared bounded, privacy-safe administrator diagnostic labels. Projection is exclusively observer-side. */
public final class WebDiagnosticLabels
{
    private static final int MAXIMUM_LABEL_CODE_POINTS = 160;
    private static final String HIDDEN_PATH = "[path hidden]";
    private static final String HIDDEN_SECRET = "[secret hidden]";
    private static final String HIDDEN_ENDPOINT = "[endpoint hidden]";

    private WebDiagnosticLabels() { }

    public static String safeLabel(String value)
    {
        if(value == null)
        {
            return null;
        }
        String text = value.strip();
        String lower = text.toLowerCase(Locale.ROOT);
        if(lower.contains("-----begin ") || lower.contains("password=") || lower.contains("password:") ||
            lower.contains("token=") || lower.contains("token:") || lower.contains("api_key=") ||
            lower.contains("api-key=") || lower.contains("apikey=") || looksLikeCredentialUrl(text))
        {
            return HIDDEN_SECRET;
        }
        if(text.contains("://"))
        {
            return HIDDEN_ENDPOINT;
        }
        if(looksLikeAbsolutePath(text))
        {
            return HIDDEN_PATH;
        }

        StringBuilder cleaned = new StringBuilder(Math.min(text.length(), MAXIMUM_LABEL_CODE_POINTS));
        int copiedCodePoints = 0;
        for(int offset = 0; offset < text.length() && copiedCodePoints < MAXIMUM_LABEL_CODE_POINTS;)
        {
            int codePoint = text.codePointAt(offset);
            offset += Character.charCount(codePoint);
            cleaned.appendCodePoint(Character.isISOControl(codePoint) ? ' ' : codePoint);
            copiedCodePoints++;
        }
        return cleaned.toString();
    }

    private static boolean looksLikeCredentialUrl(String text)
    {
        int scheme = text.indexOf("://");
        return scheme > 0 && text.indexOf('@', scheme + 3) > scheme + 3;
    }

    private static boolean looksLikeAbsolutePath(String text)
    {
        for(int index = 0; index < text.length(); index++)
        {
            if(text.regionMatches(true, index, "file:/", 0, 6))
            {
                return true;
            }
            if(index > 0 && !isPathBoundary(text.charAt(index - 1)))
            {
                continue;
            }
            char first = text.charAt(index);
            if(first == '/' && index + 1 < text.length() && !Character.isWhitespace(text.charAt(index + 1)) ||
                first == '\\' && index + 1 < text.length() && text.charAt(index + 1) == '\\' ||
                index + 2 < text.length() && Character.isLetter(first) && text.charAt(index + 1) == ':' &&
                    (text.charAt(index + 2) == '/' || text.charAt(index + 2) == '\\'))
            {
                return true;
            }
        }
        return false;
    }

    private static boolean isPathBoundary(char character)
    {
        return !Character.isLetterOrDigit(character);
    }

}
