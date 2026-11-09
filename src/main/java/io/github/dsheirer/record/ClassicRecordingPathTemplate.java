/*
 * Copyright (C) 2026 Dennis Sheirer
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.dsheirer.record;

import io.github.dsheirer.audio.call.AudioCallRecordingMetadata;
import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierClass;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.identifier.configuration.ChannelNameConfigurationIdentifier;
import io.github.dsheirer.identifier.configuration.SiteConfigurationIdentifier;
import io.github.dsheirer.identifier.configuration.SystemConfigurationIdentifier;
import io.github.dsheirer.identifier.patch.PatchGroupIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.util.TimeStamp;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.regex.Pattern;

/** Compiled relative paths for new Classic recordings and their settings preview. */
public final class ClassicRecordingPathTemplate
{
    public static final String STARTER = "{standardName}";
    public static final int MAX_LENGTH = 512;
    private static final int MAX_COMPONENTS = 8;
    private static final int MAX_COMPONENT_BYTES = 255;
    private static final Pattern TOKEN = Pattern.compile("\\{([a-zA-Z]+)}");
    private static final Pattern UNSAFE = Pattern.compile("[<>:\"/\\\\|?*\\p{Cc}]");
    private static final Pattern RESERVED = Pattern.compile(
        "(?i)(CON|PRN|AUX|NUL|COM[1-9¹²³]|LPT[1-9¹²³])(?: *\\..*)?");
    private static final Pattern TRAILING_DOTS_AND_SPACES = Pattern.compile("[ .]+$");
    private static final long SAMPLE_TIME = LocalDateTime.of(2026, 11, 9, 14, 35, 22)
        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    private static final IdentifierCollection SAMPLE = new IdentifierCollection(List.of(
        SystemConfigurationIdentifier.create("County"), SiteConfigurationIdentifier.create("North"),
        ChannelNameConfigurationIdentifier.create("Control"), APCO25Talkgroup.create(234),
        APCO25RadioIdentifier.createFrom(4534)));

    private final String mText;
    private final List<List<Part>> mComponents;

    private ClassicRecordingPathTemplate(String text, List<List<Part>> components)
    {
        mText = text;
        mComponents = List.copyOf(components);
    }

    public String text()
    {
        return mText;
    }

    public static List<Variable> variables()
    {
        return Arrays.stream(Token.values()).map(token -> new Variable(token.mName, token.mLabel)).toList();
    }

    public record Variable(String name, String label) {}

    private enum Token
    {
        STANDARD_NAME("standardName", "Standard filename"),
        DATE("date", "Date"),
        TIMESTAMP("timestamp", "Timestamp"),
        SYSTEM("system", "System"),
        SITE("site", "Site"),
        CHANNEL("channel", "Channel"),
        TALKGROUP_ID("talkgroupId", "Talkgroup ID"),
        TALKGROUP_NAME("talkgroupName", "Talkgroup name"),
        RADIO_ID("radioId", "Transmitting radio ID");

        private final String mName;
        private final String mLabel;

        Token(String name, String label)
        {
            mName = name;
            mLabel = label;
        }

        private static Token parse(String name)
        {
            for(Token token: values())
            {
                if(token.mName.equals(name)) return token;
            }
            throw new IllegalArgumentException("Choose variables from the Insert variable menu.");
        }
    }

    private record Part(String literal, Token token) {}

    public static ClassicRecordingPathTemplate compile(String text)
    {
        if(text == null || text.isBlank() || text.length() > MAX_LENGTH)
        {
            throw new IllegalArgumentException("Enter a path template up to " + MAX_LENGTH + " characters.");
        }
        String[] components = text.split("/", -1);
        if(components.length > MAX_COMPONENTS)
        {
            throw new IllegalArgumentException("Use at most " + (MAX_COMPONENTS - 1) + " subfolders.");
        }
        List<List<Part>> compiled = new ArrayList<>();
        for(String component: components)
        {
            if(component.isBlank() || component.strip().equals(".") || component.strip().equals("..") ||
                UNSAFE.matcher(component).find())
            {
                throw new IllegalArgumentException("Use a relative path with / between folder and file names.");
            }
            var matcher = TOKEN.matcher(component);
            List<Part> parts = new ArrayList<>();
            int start = 0;
            while(matcher.find())
            {
                addLiteral(parts, component.substring(start, matcher.start()));
                parts.add(new Part(null, Token.parse(matcher.group(1))));
                start = matcher.end();
            }
            addLiteral(parts, component.substring(start));
            compiled.add(List.copyOf(parts));
        }
        return new ClassicRecordingPathTemplate(text, compiled);
    }

    private static void addLiteral(List<Part> parts, String literal)
    {
        if(literal.indexOf('{') >= 0 || literal.indexOf('}') >= 0)
        {
            throw new IllegalArgumentException("Choose variables from the Insert variable menu.");
        }
        if(!literal.isEmpty()) parts.add(new Part(literal, null));
    }

    public String preview(RecordFormat format)
    {
        Resolved path = resolve(SAMPLE, "Fire Dispatch", format, SAMPLE_TIME, 1);
        List<String> components = new ArrayList<>(path.folders());
        components.add(path.filename(""));
        return String.join("/", components);
    }

    Resolved resolve(IdentifierCollection identifiers, AudioCallRecordingMetadata metadata, RecordFormat format,
                     long timestamp, int unknownIndex)
    {
        Identifier<?> group = talkgroup(identifiers);
        String alias = null;
        if(group != null && group.getValue() != null && metadata != null &&
            group.getValue().toString().equals(metadata.destinationValue()))
        {
            alias = metadata.destinationAlias();
        }
        return resolve(identifiers, alias, format, timestamp, unknownIndex);
    }

    private Resolved resolve(IdentifierCollection identifiers, String talkgroupAlias, RecordFormat format,
                             long timestamp, int unknownIndex)
    {
        EnumMap<Token,String> values = new EnumMap<>(Token.class);
        List<String> components = new ArrayList<>(mComponents.size());
        for(List<Part> parts: mComponents)
        {
            StringBuilder component = new StringBuilder();
            for(Part part: parts)
            {
                if(part.token() == null) component.append(part.literal());
                else component.append(values.computeIfAbsent(part.token(), token ->
                    value(token, identifiers, talkgroupAlias, format, timestamp, unknownIndex)));
            }
            components.add(fit(safeComponent(component.toString()), MAX_COMPONENT_BYTES));
        }
        return new Resolved(List.copyOf(components.subList(0, components.size() - 1)),
            components.getLast(), format.getExtension());
    }

    private static String value(Token token, IdentifierCollection identifiers, String alias, RecordFormat format,
                                long timestamp, int unknownIndex)
    {
        return switch(token)
        {
            case STANDARD_NAME -> {
                String name = BasicRecordingContract.filename(identifiers, format, timestamp, unknownIndex, 1);
                yield name.substring(0, name.length() - format.getExtension().length());
            }
            case DATE -> TimeStamp.DATE_FORMAT.format(Instant.ofEpochMilli(timestamp));
            case TIMESTAMP -> TimeStamp.getFileFormattedDateTime(timestamp);
            case SYSTEM -> configuredName(identifiers, Form.SYSTEM, "Unknown system");
            case SITE -> configuredName(identifiers, Form.SITE, "Unknown site");
            case CHANNEL -> configuredName(identifiers, Form.CHANNEL, "Unknown channel");
            case TALKGROUP_ID -> talkgroupId(identifiers);
            case TALKGROUP_NAME -> present(alias, talkgroupId(identifiers));
            case RADIO_ID -> {
                Identifier<?> source = identifiers != null ? identifiers.getFromIdentifier() : null;
                yield source != null && source.getForm() == Form.RADIO ?
                    present(BasicRecordingContract.sourceIdentifier(source), "Unknown radio") : "Unknown radio";
            }
        };
    }

    private static String configuredName(IdentifierCollection identifiers, Form form, String fallback)
    {
        Identifier<?> identifier = identifiers != null ?
            identifiers.getIdentifier(IdentifierClass.CONFIGURATION, form, Role.ANY) : null;
        return identifier != null && identifier.getValue() instanceof String name ? present(name, fallback) : fallback;
    }

    private static Identifier<?> talkgroup(IdentifierCollection identifiers)
    {
        if(identifiers == null) return null;
        Identifier<?> group = identifiers.getIdentifier(IdentifierClass.USER, Form.TALKGROUP, Role.TO);
        if(group != null) return group;
        if(identifiers.getToIdentifier() instanceof PatchGroupIdentifier patch && patch.getValue() != null)
        {
            return patch.getValue().getPatchGroup();
        }
        return null;
    }

    private static String talkgroupId(IdentifierCollection identifiers)
    {
        return present(BasicRecordingContract.targetIdentifier(talkgroup(identifiers)), "Unknown talkgroup");
    }

    private static String present(String value, String fallback)
    {
        return value != null && !value.isBlank() ? value : fallback;
    }

    private static String safeComponent(String value)
    {
        String cleaned = TRAILING_DOTS_AND_SPACES.matcher(UNSAFE.matcher(value.strip()).replaceAll("-"))
            .replaceFirst("");
        if(cleaned.isEmpty()) return "Unknown";
        return RESERVED.matcher(cleaned).matches() ? "_" + cleaned : cleaned;
    }

    private static String fit(String value, int bytes)
    {
        if(value.getBytes(StandardCharsets.UTF_8).length <= bytes) return value;
        int end = 0;
        while(end < value.length())
        {
            int codePoint = value.codePointAt(end);
            bytes -= codePoint <= 0x7f ? 1 : codePoint <= 0x7ff ? 2 : codePoint <= 0xffff ? 3 : 4;
            if(bytes < 0) break;
            end += Character.charCount(codePoint);
        }
        return TRAILING_DOTS_AND_SPACES.matcher(value.substring(0, end)).replaceFirst("");
    }

    record Resolved(List<String> folders, String stem, String extension)
    {
        String filename(String suffix)
        {
            return fit(stem, MAX_COMPONENT_BYTES - extension.length() - suffix.length()) + suffix + extension;
        }

        Path createDirectoryUnder(Path root) throws IOException
        {
            if(folders.isEmpty()) return root;
            Path realRoot = root.toRealPath();
            Path directory = realRoot;
            for(String folder: folders)
            {
                Path child = directory.resolve(folder);
                if(!Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS))
                {
                    try
                    {
                        Files.createDirectory(child);
                    }
                    catch(FileAlreadyExistsException exception)
                    {
                        if(!Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) throw exception;
                    }
                }
                directory = child.toRealPath();
                if(!directory.startsWith(realRoot))
                {
                    throw new IOException("Classic recording folder is outside the recordings directory");
                }
            }
            return directory;
        }
    }
}
