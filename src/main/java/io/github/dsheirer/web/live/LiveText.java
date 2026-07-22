/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.web.live;

/**
 * Small, allocation-bounded text normalizer for transient browser telemetry.
 */
final class LiveText
{
    private LiveText()
    {
    }

    static String normalize(String value, int maximumCharacters)
    {
        if(value == null || value.isBlank() || maximumCharacters < 1)
        {
            return "";
        }

        StringBuilder normalized = new StringBuilder(Math.min(value.length(), maximumCharacters));
        boolean previousWhitespace = false;

        for(int offset = 0; offset < value.length() && normalized.length() < maximumCharacters; )
        {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);

            if(Character.isISOControl(codePoint) || Character.isWhitespace(codePoint))
            {
                if(!previousWhitespace && !normalized.isEmpty())
                {
                    normalized.append(' ');
                }

                previousWhitespace = true;
            }
            else
            {
                int remaining = maximumCharacters - normalized.length();

                if(Character.charCount(codePoint) <= remaining)
                {
                    normalized.appendCodePoint(codePoint);
                    previousWhitespace = false;
                }
            }
        }

        int length = normalized.length();

        if(length > 0 && normalized.charAt(length - 1) == ' ')
        {
            normalized.setLength(length - 1);
        }

        return normalized.toString();
    }
}
