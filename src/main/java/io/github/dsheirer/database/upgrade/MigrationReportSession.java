/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.application.ApplicationInfo;
import io.github.dsheirer.database.ActivityDatabasePath;
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.support.ApplicationLogService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Local diagnostics only: report failures must never change a migration's committed outcome. */
final class MigrationReportSession
{
    static final Path REPORT_DIRECTORY = Path.of("logs", "migration-reports");
    static final int RETAINED_REPORTS = 10;
    private static final int MAX_REPORT_CHARACTERS = 1024 * 1024;
    private static final String HEADER = "VCE migration report\n";
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
        .withZone(ZoneOffset.UTC);
    private static final String OWNED_REPORT = "migration-[0-9]{8}-[0-9]{6}-[0-9]{3}-[0-9a-f-]{36}\\.txt";
    private final Instant started = Instant.now();
    private final Path root;
    private final Path source;
    private final String operation;
    private final String contents;
    private final String reviewTimings;
    private final String sourceSizes;
    private final StringBuilder phases = new StringBuilder();
    private final MigrationTimings timings = new MigrationTimings(line -> phases.append(line).append('\n'));
    private final MigrationTimings.Phase total;
    private MigrationTimings.Phase current;

    MigrationReportSession(Path root, Path source, String operation, MigrationContent content, String reviewTimings)
    {
        this(root, source, operation, content.displayName(), reviewTimings);
    }

    MigrationReportSession(Path root, Path source, String operation, String contents, String reviewTimings)
    {
        this.root = root.toAbsolutePath().normalize();
        this.source = source.toAbsolutePath().normalize();
        this.operation = operation;
        this.contents = contents;
        this.reviewTimings = reviewTimings == null ? "" : reviewTimings;
        sourceSizes = sizes(this.source);
        total = timings.phase("Total operation (excluding earlier source review)");
        current = timings.phase("Check prerequisites");
    }

    ApplicationMigrationService.ProgressListener progress(ApplicationMigrationService.ProgressListener downstream)
    {
        return label -> {
            // Engine timings also occur in the helper report on success. Retain them here for failed updates.
            if(label.startsWith("TIMING:")) phases.append(label).append('\n');
            else if(!label.startsWith("Migration plan:") && !label.startsWith("Migration scope:"))
            {
                finishPhase(true);
                current = timings.phase(label);
            }
            if(downstream != null) downstream.update(label);
        };
    }

    ApplicationMigrationService.MigrationResult completed(ApplicationMigrationService.MigrationResult result)
    {
        return new ApplicationMigrationService.MigrationResult(result.importedPreviousProfile(), result.safetyBackup(),
            result.sourcePlan(), completedText(result.helperOutput()), result.inputScope());
    }

    String completedText(String helperOutput)
    {
        try
        {
            finishPhase(true);
            total.complete();
            total.close();
            // Engine timing lines arrive through progress for failures and in helperOutput on success. Include
            // each once, while retaining the independent outer phases and source-review timings.
            String operationTimings = phases.toString().lines().filter(line -> !helperOutput.contains(line))
                .collect(java.util.stream.Collectors.joining("\n", "", "\n"));
            String diagnostics = context("Completed") + "\n" + reviewTimings + operationTimings +
                "\nTimings use elapsed wall time; nested phases overlap and must not be added together.\n";
            String location = save(diagnostics + "\n" + helperOutput);
            return helperOutput + "\n\n" + diagnostics + "\n" + location;
        }
        catch(RuntimeException | Error ignored)
        {
            return helperOutput + "\nWARNING: The migration report could not be saved. " +
                "The migration is complete; do not repeat it.";
        }
    }

    void failed(Throwable failure)
    {
        failed(failure, true);
    }

    void failed(Throwable failure, boolean retainReport)
    {
        try
        {
            finishPhase(false);
            total.close();
            // Failed external imports must leave the destination empty and the selected source unchanged, even
            // when source and destination were accidentally selected as the same location.
            if(retainReport && Files.isRegularFile(SdrTrunkDatabasePath.getDatabasePath(root), LinkOption.NOFOLLOW_LINKS))
            {
                String report = context("Failed (" + failure.getClass().getSimpleName() + ")") + "\n" +
                    reviewTimings + phases;
                // Arbitrary exception messages can contain source-owned values. Copy error retains the reason;
                // the saved report uses only the failure type and already-captured fixed timing labels.
                save(report);
            }
        }
        catch(RuntimeException | Error ignored) { /* Preserve the original migration failure. */ }
    }

    private void finishPhase(boolean completed)
    {
        if(current != null)
        {
            if(completed) current.complete();
            current.close();
            current = null;
        }
    }

    private String context(String outcome)
    {
        return HEADER + "Started (UTC): " + started + "\nFinished (UTC): " + Instant.now() +
            "\nOperation: " + operation + "\nContents: " + contents +
            "\nApplication version: " + value(ApplicationInfo.getVersion()) +
            "\nUpdate track/build: " + value(ApplicationInfo.getUpdateTrack()) + "/" +
            value(ApplicationInfo.getUpdateBuild()) + "\nBuild time: " + value(ApplicationInfo.getBuildTimestamp()) +
            "\nJava: " + System.getProperty("java.version", "unknown") + "\nOperating system: " +
            System.getProperty("os.name", "unknown") + " " + System.getProperty("os.arch", "unknown") +
            "\nSource file sizes (bytes): " + sourceSizes + "\nOutcome: " + outcome + "\n";
    }

    private String save(String report)
    {
        Path temporary = null;
        try
        {
            Path directory = root;
            for(Path part: REPORT_DIRECTORY)
            {
                requireDirectory(directory);
                directory = directory.resolve(part);
                if(!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(directory);
                requireDirectory(directory);
            }
            FileAccessAttributeSnapshot.restrictPrivateDirectory(directory);
            temporary = Files.createTempFile(directory, ".migration-report-", ".tmp");
            FileAccessAttributeSnapshot.restrictSensitiveFile(temporary);
            String safe = sanitize(report);
            if(safe.length() > MAX_REPORT_CHARACTERS)
                safe = safe.substring(0, MAX_REPORT_CHARACTERS) + "\n[Report truncated]\n";
            Files.writeString(temporary, safe, StandardCharsets.UTF_8);
            String name = "migration-" + FILE_TIME.format(started) + "-" + UUID.randomUUID() + ".txt";
            try { Files.move(temporary, directory.resolve(name), StandardCopyOption.ATOMIC_MOVE); }
            catch(AtomicMoveNotSupportedException unsupported) { Files.move(temporary, directory.resolve(name)); }
            temporary = null;
            try { prune(directory, directory.resolve(name)); }
            catch(IOException | RuntimeException ignored) { /* The newly saved report remains usable. */ }
            return "Migration report saved: " + REPORT_DIRECTORY.resolve(name).toString().replace('\\', '/');
        }
        catch(IOException | RuntimeException failure)
        {
            return "WARNING: The migration report could not be saved. Copy the report before closing setup. " +
                "A completed migration does not need to be repeated.";
        }
        finally
        {
            if(temporary != null)
            {
                try { Files.deleteIfExists(temporary); }
                catch(IOException | RuntimeException ignored) { }
            }
        }
    }

    private String sanitize(String report)
    {
        String safe = report.replace(root.toString(), "[destination data]")
            .replace(source.toString(), "[source database]");
        Path parent = source.getParent();
        Path sourceRoot = parent != null && parent.getFileName() != null &&
            "database".equals(parent.getFileName().toString()) ? parent.getParent() : parent;
        if(sourceRoot != null) safe = safe.replace(sourceRoot.toString(), "[source data]");
        return ApplicationLogService.redact(safe);
    }

    private static void requireDirectory(Path directory) throws IOException
    {
        if(Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("The migration report location is not an ordinary directory");
    }

    private static void prune(Path directory, Path newest) throws IOException
    {
        List<Path> reports;
        try(var files = Files.list(directory))
        {
            reports = files.filter(file -> file.getFileName().toString().matches(OWNED_REPORT))
                .filter(file -> Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                .filter(file -> !file.equals(newest))
                .sorted(Comparator.comparing((Path file) -> file.getFileName().toString()).reversed()).toList();
        }
        for(Path report: reports.subList(Math.min(RETAINED_REPORTS - 1, reports.size()), reports.size()))
        {
            try(var reader = Files.newBufferedReader(report, StandardCharsets.UTF_8))
            {
                char[] header = new char[HEADER.length()];
                if(reader.read(header) != header.length || !HEADER.equals(new String(header))) continue;
            }
            Files.delete(report);
        }
    }

    private static String sizes(Path source)
    {
        StringBuilder sizes = new StringBuilder();
        boolean catalog = "managed-recordings.sqlite".equals(source.getFileName().toString());
        for(Path database: catalog ? List.of(source) : List.of(source, ActivityDatabasePath.forConfiguration(source)))
        for(String suffix: List.of("", "-wal", "-journal"))
        {
            Path file = Path.of(database + suffix);
            try
            {
                if(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                {
                    if(!sizes.isEmpty()) sizes.append(", ");
                    sizes.append(database.equals(source) ? "source" : "activity")
                        .append(suffix).append('=').append(Files.size(file));
                }
            }
            catch(IOException | RuntimeException ignored) { /* Diagnostics never block admission. */ }
        }
        return sizes.isEmpty() ? "unavailable" : sizes.toString();
    }

    private static String value(String value) { return value == null || value.isBlank() ? "development" : value; }
}
