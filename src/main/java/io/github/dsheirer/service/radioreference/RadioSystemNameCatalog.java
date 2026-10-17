/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.service.radioreference;

import io.github.dsheirer.module.decode.traffic.RadioSystemKey;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Immutable display names from the DSDPlus network list bundled with this build. Names, including combined source
 * labels, are hints: they never establish system ownership, a verified site, or a directory catalog identifier.
 * Lookup and search perform no filesystem, network, or database work.
 */
public final class RadioSystemNameCatalog
{
    private static final Logger LOGGER = LoggerFactory.getLogger(RadioSystemNameCatalog.class);
    private static final String RESOURCE = "/radio-systems/dsdplus.networks";
    private static final int MAXIMUM_BYTES = 2 * 1024 * 1024;
    private static final int MAXIMUM_LINES = 50_000;
    private static final int MAXIMUM_ENTRIES = 20_000;
    private static final int MAXIMUM_LINE_LENGTH = 4096;
    private static final int MAXIMUM_NAME_LENGTH = 256;
    private static final Pattern PREFIX = Pattern.compile("^\\h*([A-Za-z0-9+_-]+)\\h*,\\h*([^,\\r\\n]+)\\h*,");
    private static final Pattern P25_ID = Pattern.compile("([0-9A-Fa-f]{1,5})\\.([0-9A-Fa-f]{1,3})");
    private static final Pattern STANDARD_TIER_3_ID = Pattern.compile("([TSLH])([1-9][0-9]{0,3})");
    private static final Pattern AREA_LENGTH = Pattern.compile(",\\h*(?:[0-9]|10)\\h*(?:;[^\\r\\n]*)?");
    private final Map<String,String> mNames;

    private RadioSystemNameCatalog(Map<String,String> names)
    {
        mNames = Collections.unmodifiableMap(new LinkedHashMap<>(names));
    }

    /** Loads the packaged snapshot once, outside receiver ingress; missing optional names remain numeric identities. */
    public static RadioSystemNameCatalog bundled()
    {
        return Bundled.INSTANCE;
    }

    /** Returns only an exact canonical-key match. Unsupported families and synthetic DSDPlus IDs return null. */
    public String nameFor(String canonicalRadioSystemKey)
    {
        return canonicalRadioSystemKey != null ? mNames.get(canonicalRadioSystemKey) : null;
    }

    /** Literal, case-insensitive substring search over the complete bounded catalog, in source order. */
    public Set<String> matchingKeys(String search)
    {
        if(search == null || search.isBlank() || search.length() > MAXIMUM_NAME_LENGTH) return Set.of();
        String query = search.strip().toLowerCase(Locale.ROOT);
        Set<String> keys = new LinkedHashSet<>();
        mNames.forEach((key, name) -> {
            if(name.toLowerCase(Locale.ROOT).contains(query)) keys.add(key);
        });
        return Collections.unmodifiableSet(keys);
    }

    /**
     * Reads raw DSDPlus.networks text, preserving complete source labels and ignoring comments. Invalid rows are
     * isolated to their own line. Duplicate distinct labels are combined in source order; an oversized combination
     * is omitted altogether instead of silently selecting one of its possible names.
     */
    public static RadioSystemNameCatalog parse(String text)
    {
        if(text == null) throw new IllegalArgumentException("Radio-system name catalog is missing");
        if(text.length() > MAXIMUM_BYTES || text.getBytes(StandardCharsets.UTF_8).length > MAXIMUM_BYTES)
            throw new IllegalArgumentException("Radio-system name catalog exceeds its size limit");
        String[] lines = text.split("\\R", -1);
        if(lines.length > MAXIMUM_LINES)
            throw new IllegalArgumentException("Radio-system name catalog exceeds its line limit");
        Map<String,LinkedHashSet<String>> labels = new LinkedHashMap<>();
        Set<String> oversized = new LinkedHashSet<>();
        for(int index = 0; index < lines.length; index++)
        {
            String line = lines[index];
            if(index == 0 && line.startsWith("\uFEFF")) line = line.substring(1);
            if(line.length() > MAXIMUM_LINE_LENGTH) continue;
            Matcher prefix = PREFIX.matcher(line);
            if(!prefix.find()) continue;
            String protocol = prefix.group(1);
            String key = canonicalKey(protocol, prefix.group(2).strip());
            if(key == null || oversized.contains(key)) continue;
            String name = quotedName(line, prefix.end(), "TIIIStd".equals(protocol));
            if(name == null) continue;
            if(!labels.containsKey(key) && labels.size() + oversized.size() >= MAXIMUM_ENTRIES)
                throw new IllegalArgumentException("Radio-system name catalog exceeds its entry limit");
            LinkedHashSet<String> names = labels.computeIfAbsent(key, ignored -> new LinkedHashSet<>());
            names.add(name);
            if(String.join(" / ", names).length() > MAXIMUM_NAME_LENGTH)
            {
                labels.remove(key);
                oversized.add(key);
            }
        }
        Map<String,String> names = new LinkedHashMap<>();
        labels.forEach((key, values) -> names.put(key, String.join(" / ", values)));
        return new RadioSystemNameCatalog(names);
    }

    private static String canonicalKey(String protocol, String identity)
    {
        if("P25".equals(protocol))
        {
            Matcher match = P25_ID.matcher(identity);
            return match.matches() ? RadioSystemKey.p25(Integer.parseInt(match.group(1), 16),
                Integer.parseInt(match.group(2), 16)) : null;
        }
        if("TIIIStd".equals(protocol))
        {
            Matcher match = STANDARD_TIER_3_ID.matcher(identity);
            if(!match.matches()) return null;
            String model = switch(match.group(1))
            {
                case "T" -> "tiny";
                case "S" -> "small";
                case "L" -> "large";
                case "H" -> "huge";
                default -> throw new IllegalStateException("Unexpected Tier III model");
            };
            // DSDPlus displays standard Tier III NIDs starting at one; our decoder keys use the raw bit value.
            return RadioSystemKey.dmrTier3(model, Integer.parseInt(match.group(2)) - 1);
        }
        // Cap+ and prefixed IDs are user/catalog assignments. NEXEDGE48/96 do not carry the location category
        // required by our Type-C key, which also does not distinguish these two baud-rate families. Never guess.
        return null;
    }

    private static String quotedName(String line, int offset, boolean tierThree)
    {
        while(offset < line.length() && horizontalWhitespace(line.charAt(offset))) offset++;
        if(offset >= line.length() || line.charAt(offset++) != '"') return null;
        StringBuilder name = new StringBuilder();
        while(offset < line.length())
        {
            char character = line.charAt(offset++);
            if(character == '"')
            {
                if(offset < line.length() && line.charAt(offset) == '"')
                {
                    name.append('"');
                    offset++;
                    continue;
                }
                while(offset < line.length() && horizontalWhitespace(line.charAt(offset))) offset++;
                String remainder = line.substring(offset).stripTrailing();
                if(!remainder.isEmpty() && !remainder.startsWith(";") &&
                    !(tierThree && AREA_LENGTH.matcher(remainder).matches())) return null;
                String result = name.toString();
                return !result.isBlank() && result.length() <= MAXIMUM_NAME_LENGTH ? result : null;
            }
            if(Character.isISOControl(character) || character == '\uFFFD' || character == '<' || character == '>')
                return null;
            name.append(character);
            if(name.length() > MAXIMUM_NAME_LENGTH) return null;
        }
        return null;
    }

    private static boolean horizontalWhitespace(char character)
    {
        return character == '\t' || Character.isSpaceChar(character);
    }

    private static RadioSystemNameCatalog loadBundled()
    {
        try(InputStream input = RadioSystemNameCatalog.class.getResourceAsStream(RESOURCE))
        {
            if(input == null) throw new IOException("Bundled snapshot is missing");
            byte[] bytes = input.readNBytes(MAXIMUM_BYTES + 1);
            if(bytes.length > MAXIMUM_BYTES) throw new IOException("Bundled snapshot exceeds its size limit");
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            return parse(text);
        }
        catch(IOException | IllegalArgumentException exception)
        {
            LOGGER.warn("Bundled radio-system names are unavailable; using numeric identities");
            return new RadioSystemNameCatalog(Map.of());
        }
    }

    private static final class Bundled
    {
        private static final RadioSystemNameCatalog INSTANCE = loadBundled();
    }
}
