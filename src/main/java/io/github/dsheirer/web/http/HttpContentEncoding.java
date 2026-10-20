/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.web.http;

import java.util.List;
import java.util.Locale;

/** Shared Accept-Encoding negotiation for prepared static and JSON representations. */
final class HttpContentEncoding
{
    enum Encoding { IDENTITY, GZIP, UNACCEPTABLE }
    private HttpContentEncoding() { }
    static Encoding choose(List<String> values, int identityBytes, Integer gzipBytes)
    {
        if(values == null || values.isEmpty()) return Encoding.IDENTITY;
        double gzip = -1, identity = -1, wildcard = -1;
        for(String value: values)
        {
            for(String part: value.split(","))
            {
                String[] parameters = part.trim().split(";");
                String name = parameters[0].trim().toLowerCase(Locale.ROOT);
                double quality = 1;
                for(int index = 1; index < parameters.length; index++)
                {
                    String parameter = parameters[index].trim();
                    if(parameter.toLowerCase(Locale.ROOT).startsWith("q="))
                    {
                        try { quality = Double.parseDouble(parameter.substring(2).trim()); }
                        catch(NumberFormatException ignored) { quality = 0; }
                        if(!Double.isFinite(quality) || quality < 0 || quality > 1) quality = 0;
                    }
                }
                switch(name)
                {
                    case "gzip" -> gzip = quality;
                    case "identity" -> identity = quality;
                    case "*" -> wildcard = quality;
                    default -> { }
                }
            }
        }
        if(gzip < 0) gzip = Math.max(0, wildcard);
        if(identity < 0) identity = wildcard == 0 ? 0 : 1;
        if(gzipBytes != null && gzip > 0 && gzip >= identity &&
            (identity == 0 || gzipBytes < identityBytes)) return Encoding.GZIP;
        if(identity > 0) return Encoding.IDENTITY;
        if(gzipBytes != null && gzip > 0) return Encoding.GZIP;
        return Encoding.UNACCEPTABLE;
    }
}
