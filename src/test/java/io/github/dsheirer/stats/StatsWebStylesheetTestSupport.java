/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.stats;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads the stylesheet entry point and every local design-system module for source-level UI contracts. */
final class StatsWebStylesheetTestSupport
{
    private static final Path ENTRY_STYLESHEET = Path.of("stats-web", "assets", "app.css");
    private static final Path STYLE_MODULES = Path.of("stats-web", "assets", "styles");
    private static final Pattern IMPORT_PATTERN = Pattern.compile(
        "@import\\s+(?:url\\(\\s*)?[\\\"']([^\\\"']+\\.css(?:[?#][^\\\"']*)?)[\\\"']\\s*\\)?[^;]*;",
        Pattern.CASE_INSENSITIVE);

    private StatsWebStylesheetTestSupport()
    {
    }

    static String readAll() throws IOException
    {
        StringBuilder source = new StringBuilder();
        Set<Path> loaded = new LinkedHashSet<>();
        readCascade(ENTRY_STYLESHEET.toAbsolutePath().normalize(), source, loaded, new HashSet<>());

        Path moduleRoot = STYLE_MODULES.toAbsolutePath().normalize();
        if(Files.isDirectory(moduleRoot))
        {
            try(var paths = Files.walk(moduleRoot))
            {
                var unreachable = paths.filter(Files::isRegularFile)
                    .filter(candidate -> candidate.getFileName().toString().endsWith(".css"))
                    .map(candidate -> candidate.toAbsolutePath().normalize())
                    .filter(candidate -> !loaded.contains(candidate))
                    .map(moduleRoot::relativize)
                    .map(Path::toString)
                    .sorted()
                    .toList();
                if(!unreachable.isEmpty())
                {
                    throw new IOException("Stylesheet modules are not reachable from app.css: " + unreachable);
                }
            }
        }

        return source.toString();
    }

    private static void readCascade(Path stylesheet, StringBuilder combined, Set<Path> loaded,
                                    Set<Path> visiting) throws IOException
    {
        Path normalized = stylesheet.toAbsolutePath().normalize();
        if(loaded.contains(normalized))
        {
            return;
        }
        if(!visiting.add(normalized))
        {
            throw new IOException("Circular stylesheet import: " + normalized);
        }
        if(!Files.isRegularFile(normalized))
        {
            throw new IOException("Missing stylesheet: " + normalized);
        }

        String stylesheetSource = normalizeLineEndings(Files.readString(normalized));
        Matcher matcher = IMPORT_PATTERN.matcher(stylesheetSource);
        Path moduleRoot = STYLE_MODULES.toAbsolutePath().normalize();
        while(matcher.find())
        {
            String specifier = matcher.group(1);
            int suffix = specifier.indexOf('?');
            int fragment = specifier.indexOf('#');
            int end = suffix < 0 ? fragment : fragment < 0 ? suffix : Math.min(suffix, fragment);
            if(end >= 0)
            {
                specifier = specifier.substring(0, end);
            }
            Path imported = normalized.getParent().resolve(specifier).normalize();
            if(!imported.startsWith(moduleRoot))
            {
                throw new IOException("Stylesheet import must stay under " + moduleRoot + ": " + imported);
            }
            readCascade(imported, combined, loaded, visiting);
        }

        visiting.remove(normalized);
        loaded.add(normalized);
        if(!combined.isEmpty())
        {
            combined.append('\n');
        }
        combined.append(stylesheetSource);
    }

    private static String normalizeLineEndings(String source)
    {
        return source.replace("\r\n", "\n").replace('\r', '\n');
    }
}
