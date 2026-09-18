/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */
package io.github.dsheirer.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dsheirer.application.ApplicationInfo;
import io.github.dsheirer.database.SdrTrunkDatabase;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Builds and uploads administrator-requested support bundles away from receiver processing threads. */
public final class SupportBundleService implements AutoCloseable
{
    public static final URI DEFAULT_UPLOAD_URI = URI.create("https://radioresolve.com/vcebugreport.php");
    public static final String UPLOAD_USER_AGENT = "sdrtrunk-vce-support/1";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final long RECENT_ACTIVITY_MILLISECONDS = Duration.ofHours(2).toMillis();
    private static final int MAXIMUM_JOBS = 8;
    private static final int LOG_FILE_LIMIT = 2;
    private static final long LOG_BYTE_LIMIT = 16L * 1024L * 1024L;
    private static final Set<String> SETUP_TABLES = Set.of("database_metadata", "configuration_channel");
    private static final Set<String> ALIAS_TABLES = Set.of("alias_list", "alias", "scan_list",
        "alias_scan_list_membership", "alias_list_unmatched_talkgroup_scan_list_membership",
        "alias_list_new_alias_scan_list_membership", "alias_broadcast_channel",
        "alias_list_unmatched_talkgroup_stream", "alias_list_new_alias_stream");
    private static final Set<String> ALWAYS_PRIVATE_TABLES = Set.of("web_user", "web_access_policy",
        "application_settings", "application_icons", "configuration_broadcast_stream", "vault_metadata",
        "vault_payload", "sqlite_sequence");
    private static final List<String> ACTIVITY_PREFIXES = List.of("receiver_", "radio_system",
        "trunked_", "p25_", "dmr_", "nxdn_", "conventional_", "activity_", "alias_activity_");
    private static final List<String> TIME_COLUMNS = List.of("timestamp_ms", "timestamp", "time_start",
        "time_start_ms", "started_at_ms", "updated_at_ms", "last_seen_at_ms", "last_seen_ms",
        "bucket_start_ms", "created_at_ms", "event_at_ms");
    private static final Pattern PRIVATE_COLUMN = Pattern.compile(
        "(?i)(password|password_verifier|api_key|access_token|refresh_token|session_id|secret|vault_payload|action_config_json)");
    private static final Pattern LOG_SECRET = Pattern.compile(
        "(?i)(authorization\\s*[:=]\\s*(?:bearer\\s+)?|api[_ -]?key\\s*[:=]\\s*|password\\s*[:=]\\s*)([^\\s,;]+)");
    private static final Pattern USER_PATH = Pattern.compile("(?i)(/Users/|/home/|[A-Z]:\\\\Users\\\\)[^/\\\\\\s]+", Pattern.CASE_INSENSITIVE);

    private final Path mDatabasePath;
    private final Path mLogDirectory;
    private final Supplier<Map<String,Object>> mHealthSupplier;
    private final URI mUploadUri;
    private final HttpClient mHttpClient;
    private final ExecutorService mWorker;
    private final Map<String,Job> mJobs = new ConcurrentHashMap<>();
    private final AtomicBoolean mClosed = new AtomicBoolean();

    public SupportBundleService(Path databasePath, Path logDirectory,
                                Supplier<Map<String,Object>> healthSupplier)
    {
        this(databasePath, logDirectory, healthSupplier, DEFAULT_UPLOAD_URI,
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build());
    }

    SupportBundleService(Path databasePath, Path logDirectory, Supplier<Map<String,Object>> healthSupplier,
                         URI uploadUri, HttpClient httpClient)
    {
        mDatabasePath = Objects.requireNonNull(databasePath).toAbsolutePath().normalize();
        mLogDirectory = Objects.requireNonNull(logDirectory).toAbsolutePath().normalize();
        mHealthSupplier = healthSupplier != null ? healthSupplier : Map::of;
        mUploadUri = Objects.requireNonNull(uploadUri);
        mHttpClient = Objects.requireNonNull(httpClient);
        mWorker = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "support bundle worker");
            thread.setDaemon(true);
            thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 2));
            return thread;
        });
    }

    public String generate(Request request)
    {
        Objects.requireNonNull(request);
        requireOpen();
        pruneFinishedJobs();
        if(mJobs.size() >= MAXIMUM_JOBS)
        {
            throw new IllegalStateException("Too many support bundles are already being prepared");
        }
        String id = UUID.randomUUID().toString();
        Job job = new Job(id, request);
        mJobs.put(id, job);
        mWorker.execute(() -> build(job));
        return id;
    }

    public Status status(String id)
    {
        Job job = requireJob(id);
        return job.status();
    }

    public void cancel(String id)
    {
        Job job = requireJob(id);
        job.cancelled.set(true);
        if(job.state == State.READY || job.state == State.FAILED || job.state == State.UPLOADED)
        {
            deleteBundle(job);
            job.state = State.CANCELLED;
            job.message = "Stopped and removed the local support bundle.";
        }
    }

    public void submit(String id)
    {
        Job job = requireJob(id);
        synchronized(job)
        {
            if(job.state != State.READY || job.path == null)
            {
                throw new IllegalStateException("The support bundle is not ready to send");
            }
            job.state = State.UPLOADING;
            job.progress = 99;
            job.message = "Sending the support bundle…";
            job.log("Sending the support bundle");
        }
        mWorker.execute(() -> upload(job));
    }

    public PreparedDownload download(String id)
    {
        Job job = requireJob(id);
        if(job.state != State.READY || job.path == null || !Files.isRegularFile(job.path))
        {
            throw new IllegalStateException("The support bundle is not ready to download");
        }
        return new PreparedDownload(job.path, job.fileName, job.bytes);
    }

    private void build(Job job)
    {
        Path path = null;
        try
        {
            update(job, State.GENERATING, 2, "Starting…", "Starting support bundle");
            path = Files.createTempFile("sdrtrunk-vce-support-", ".zip");
            job.path = path;
            try(OutputStream file = Files.newOutputStream(path); ZipOutputStream zip = new ZipOutputStream(file))
            {
                writeJson(zip, "report.json", report(job.request));
                update(job, State.GENERATING, 10, "Collecting application details…",
                    "Collected issue details");
                checkCancelled(job);

                Map<String,Object> health = safeHealthSnapshot();
                if(job.request.sections.contains(Section.APPLICATION))
                {
                    writeJson(zip, "application-and-computer.json", machineInformation());
                    job.included.add(Section.APPLICATION.id);
                }
                if(job.request.sections.contains(Section.HEALTH))
                {
                    writeJson(zip, "current-receiver-status.json", health);
                    job.included.add(Section.HEALTH.id);
                }
                if(job.request.sections.contains(Section.HARDWARE))
                {
                    writeJson(zip, "tuners-and-usb.json", healthSection(health,
                        Set.of("tuners", "usb", "receiver-queues", "channelizer", "channels")));
                    job.included.add(Section.HARDWARE.id);
                }
                update(job, State.GENERATING, 25, "Collecting receiver information…",
                    "Collected receiver information");
                checkCancelled(job);

                if(job.request.sections.contains(Section.SETUP) || job.request.sections.contains(Section.ALIASES) ||
                    job.request.sections.contains(Section.RECENT_ACTIVITY) ||
                    job.request.sections.contains(Section.FULL_ACTIVITY))
                {
                    exportDatabase(zip, job);
                }
                update(job, State.GENERATING, 80, "Collecting recent messages…",
                    "Collected selected receiver data");
                checkCancelled(job);

                if(job.request.sections.contains(Section.LOGS))
                {
                    exportLogs(zip, job);
                    job.included.add(Section.LOGS.id);
                }
                writeJson(zip, "bundle-summary.json", bundleSummary(job));
                update(job, State.GENERATING, 95, "Finishing the bundle…", "Finished writing bundle contents");
            }
            checkCancelled(job);
            job.bytes = Files.size(path);
            job.fileName = "sdrtrunk-vce-support-" + Instant.now().toString().replace(':', '-') + ".zip";
            update(job, State.READY, 100, "Ready to send.", "Support bundle is ready");
        }
        catch(CancelledException exception)
        {
            deleteBundle(job);
            update(job, State.CANCELLED, 0, "Stopped and removed the local support bundle.",
                "Bundle creation stopped");
        }
        catch(Exception exception)
        {
            deleteBundle(job);
            String error = safeMessage(exception, "The support bundle could not be created.");
            update(job, State.FAILED, 0, error, "Bundle creation failed");
        }
    }

    private void upload(Job job)
    {
        try
        {
            String boundary = "----sdrtrunk-vce-" + UUID.randomUUID();
            String disposition = "--" + boundary + "\r\n" +
                "Content-Disposition: form-data; name=\"support_bundle\"; filename=\"" + job.fileName + "\"\r\n" +
                "Content-Type: application/zip\r\n\r\n";
            HttpRequest.BodyPublisher body = HttpRequest.BodyPublishers.concat(
                HttpRequest.BodyPublishers.ofByteArray(disposition.getBytes(StandardCharsets.UTF_8)),
                HttpRequest.BodyPublishers.ofFile(job.path),
                HttpRequest.BodyPublishers.ofByteArray(("\r\n--" + boundary + "--\r\n")
                    .getBytes(StandardCharsets.UTF_8)));
            HttpRequest request = HttpRequest.newBuilder(mUploadUri)
                .timeout(Duration.ofHours(2))
                .header("User-Agent", UPLOAD_USER_AGENT)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(body).build();
            HttpResponse<Void> response = mHttpClient.send(request, HttpResponse.BodyHandlers.discarding());
            if(response.statusCode() < 200 || response.statusCode() >= 300)
            {
                throw new IOException("The server did not accept the support bundle (" + response.statusCode() + ")");
            }
            deleteBundle(job);
            update(job, State.UPLOADED, 100, "Your bug report was sent. The local support bundle was removed.",
                "Bug report sent successfully");
        }
        catch(Exception exception)
        {
            String error = safeMessage(exception, "The support bundle could not be sent.");
            update(job, State.READY, 100, error + " The local bundle is still available.",
                "Upload failed; local bundle kept");
        }
    }

    private void exportDatabase(ZipOutputStream zip, Job job) throws IOException, SQLException, CancelledException
    {
        try(Connection connection = SdrTrunkDatabase.open(mDatabasePath))
        {
            try(Statement statement = connection.createStatement())
            {
                statement.execute("PRAGMA query_only=ON");
            }
            List<String> tables = databaseTables(connection);
            for(String table: tables)
            {
                checkCancelled(job);
                if(ALWAYS_PRIVATE_TABLES.contains(table))
                {
                    job.omitted.put(table, "Contains private account, sign-in, key, or service information");
                    continue;
                }
                if(job.request.sections.contains(Section.SETUP) && SETUP_TABLES.contains(table))
                {
                    exportTable(zip, connection, table, "receiver-setup/", 0, job);
                    job.included.add(Section.SETUP.id);
                }
                else if(job.request.sections.contains(Section.ALIASES) && ALIAS_TABLES.contains(table))
                {
                    exportTable(zip, connection, table, "aliases-and-listening/", 0, job);
                    job.included.add(Section.ALIASES.id);
                }
                else if(isActivityTable(table))
                {
                    if(job.request.sections.contains(Section.FULL_ACTIVITY))
                    {
                        exportTable(zip, connection, table, "full-activity-history/", 0, job);
                        job.included.add(Section.FULL_ACTIVITY.id);
                    }
                    else if(job.request.sections.contains(Section.RECENT_ACTIVITY))
                    {
                        long cutoff = System.currentTimeMillis() - RECENT_ACTIVITY_MILLISECONDS;
                        if(exportTable(zip, connection, table, "recent-activity-history/", cutoff, job))
                        {
                            job.included.add(Section.RECENT_ACTIVITY.id);
                        }
                    }
                }
                else if(!SETUP_TABLES.contains(table) && !ALIAS_TABLES.contains(table))
                {
                    job.omitted.put(table, "Not part of a support-bundle section in this app version");
                }
            }
        }
    }

    private boolean exportTable(ZipOutputStream zip, Connection connection, String table, String folder, long cutoff,
                                Job job) throws SQLException, IOException, CancelledException
    {
        List<String> columns = columns(connection, table);
        String timeColumn = cutoff > 0 ? timeColumn(columns) : null;
        if(cutoff > 0 && timeColumn == null)
        {
            job.omitted.put(table, "No reliable time field was available for the recent-history window");
            return false;
        }
        String sql = "SELECT * FROM " + quote(table) + (timeColumn != null ?
            " WHERE " + quote(timeColumn) + " >= ?" : "");
        ZipEntry entry = new ZipEntry(folder + friendlyFileName(table) + ".csv");
        zip.putNextEntry(entry);
        NonClosingOutputStream nonClosing = new NonClosingOutputStream(zip);
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(nonClosing, StandardCharsets.UTF_8));
        long rows = 0;
        try(PreparedStatement statement = connection.prepareStatement(sql))
        {
            statement.setFetchSize(500);
            if(timeColumn != null)
            {
                statement.setLong(1, cutoff);
            }
            try(ResultSet results = statement.executeQuery())
            {
                ResultSetMetaData metadata = results.getMetaData();
                for(int column = 1; column <= metadata.getColumnCount(); column++)
                {
                    if(column > 1) writer.write(',');
                    writeCsv(writer, metadata.getColumnLabel(column));
                }
                writer.newLine();
                while(results.next())
                {
                    if((rows & 255) == 0) checkCancelled(job);
                    for(int column = 1; column <= metadata.getColumnCount(); column++)
                    {
                        if(column > 1) writer.write(',');
                        String label = metadata.getColumnLabel(column);
                        Object value = PRIVATE_COLUMN.matcher(label).find() ? "[removed]" : results.getObject(column);
                        writeCsv(writer, value != null ? String.valueOf(value) : "");
                    }
                    writer.newLine();
                    rows++;
                }
            }
        }
        writer.flush();
        zip.closeEntry();
        job.rowCounts.put(table, rows);
        return true;
    }

    private void exportLogs(ZipOutputStream zip, Job job) throws IOException, CancelledException
    {
        if(!Files.isDirectory(mLogDirectory)) return;
        List<Path> logs = new ArrayList<>();
        try(DirectoryStream<Path> stream = Files.newDirectoryStream(mLogDirectory, "*sdrtrunk_app.log*"))
        {
            stream.forEach(logs::add);
        }
        logs.sort((left, right) -> Long.compare(lastModified(right), lastModified(left)));
        for(int index = 0; index < Math.min(LOG_FILE_LIMIT, logs.size()); index++)
        {
            checkCancelled(job);
            Path log = logs.get(index);
            byte[] bytes = readTail(log, LOG_BYTE_LIMIT);
            String text = new String(bytes, StandardCharsets.UTF_8);
            text = LOG_SECRET.matcher(text).replaceAll("$1[removed]");
            text = USER_PATH.matcher(text).replaceAll("$1[user]");
            writeText(zip, "application-log/" + (index == 0 ? "current.log" : "previous.log"), text);
        }
    }

    private Map<String,Object> report(Request request)
    {
        LinkedHashMap<String,Object> report = new LinkedHashMap<>();
        report.put("title", request.title);
        report.put("email", request.email);
        report.put("category", request.category);
        report.put("issue", request.issue);
        report.put("description", request.description);
        report.put("steps_to_reproduce", request.steps);
        report.put("created_at", Instant.now().toString());
        return report;
    }

    private Map<String,Object> machineInformation()
    {
        Runtime runtime = Runtime.getRuntime();
        LinkedHashMap<String,Object> information = new LinkedHashMap<>();
        information.put("application", ApplicationInfo.getDisplayName());
        information.put("version", ApplicationInfo.getVersion());
        information.put("update_track", ApplicationInfo.getUpdateTrack());
        information.put("update_build", ApplicationInfo.getUpdateBuild());
        information.put("operating_system", System.getProperty("os.name"));
        information.put("operating_system_version", System.getProperty("os.version"));
        information.put("architecture", System.getProperty("os.arch"));
        information.put("java_version", System.getProperty("java.version"));
        information.put("processors", runtime.availableProcessors());
        information.put("memory_available_bytes", runtime.maxMemory());
        information.put("memory_in_use_bytes", runtime.totalMemory() - runtime.freeMemory());
        information.put("database_size_bytes", fileSize(mDatabasePath));
        return information;
    }

    private Map<String,Object> bundleSummary(Job job)
    {
        LinkedHashMap<String,Object> summary = new LinkedHashMap<>();
        summary.put("bundle_format", 1);
        summary.put("included_sections", List.copyOf(job.included));
        summary.put("exported_rows", new LinkedHashMap<>(job.rowCounts));
        summary.put("omitted_data", new LinkedHashMap<>(job.omitted));
        summary.put("private_data_never_included", List.of("Passwords and sign-in records", "Service keys and tokens",
            "Encryption keys", "The complete receiver database", "Audio recordings and baseband captures"));
        return summary;
    }

    @SuppressWarnings("unchecked")
    private Map<String,Object> healthSection(Map<String,Object> health, Set<String> ids)
    {
        LinkedHashMap<String,Object> result = new LinkedHashMap<>();
        result.put("generated_at_ms", health.get("generated_at_ms"));
        Object measurements = health.get("measurements");
        if(measurements instanceof Collection<?> collection)
        {
            result.put("measurements", collection.stream().filter(item -> item instanceof Map<?,?> map &&
                ids.contains(String.valueOf(map.get("id")))).toList());
        }
        return result;
    }

    private Map<String,Object> safeHealthSnapshot()
    {
        Map<String,Object> snapshot = mHealthSupplier.get();
        return snapshot != null ? snapshot : Map.of();
    }

    private static List<String> databaseTables(Connection connection) throws SQLException
    {
        List<String> result = new ArrayList<>();
        try(PreparedStatement statement = connection.prepareStatement(
            "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name"); ResultSet rows = statement.executeQuery())
        {
            while(rows.next()) result.add(rows.getString(1));
        }
        return result;
    }

    private static List<String> columns(Connection connection, String table) throws SQLException
    {
        List<String> result = new ArrayList<>();
        try(Statement statement = connection.createStatement();
            ResultSet rows = statement.executeQuery("PRAGMA table_info(" + quote(table) + ")"))
        {
            while(rows.next()) result.add(rows.getString("name"));
        }
        return result;
    }

    private static String timeColumn(List<String> columns)
    {
        for(String candidate: TIME_COLUMNS)
        {
            for(String column: columns)
            {
                if(candidate.equalsIgnoreCase(column)) return column;
            }
        }
        return null;
    }

    private static boolean isActivityTable(String table)
    {
        return ACTIVITY_PREFIXES.stream().anyMatch(table::startsWith) || "statistics_status".equals(table);
    }

    private static String friendlyFileName(String table)
    {
        return table.replace('_', '-');
    }

    private static String quote(String identifier)
    {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    private static void writeCsv(BufferedWriter writer, String value) throws IOException
    {
        writer.write('"');
        writer.write(value.replace("\"", "\"\"").replace("\u0000", ""));
        writer.write('"');
    }

    private static void writeJson(ZipOutputStream zip, String name, Object value) throws IOException
    {
        zip.putNextEntry(new ZipEntry(name));
        OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValue(new NonClosingOutputStream(zip), value);
        zip.closeEntry();
    }

    private static void writeText(ZipOutputStream zip, String name, String value) throws IOException
    {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(value.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static byte[] readTail(Path path, long maximumBytes) throws IOException
    {
        long size = Files.size(path);
        long start = Math.max(0, size - maximumBytes);
        try(var channel = Files.newByteChannel(path))
        {
            channel.position(start);
            byte[] bytes = new byte[(int)Math.min(maximumBytes, size)];
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes);
            while(buffer.hasRemaining() && channel.read(buffer) >= 0) { }
            return buffer.position() == bytes.length ? bytes : java.util.Arrays.copyOf(bytes, buffer.position());
        }
    }

    private static long lastModified(Path path)
    {
        try { return Files.getLastModifiedTime(path).toMillis(); }
        catch(IOException exception) { return 0; }
    }

    private static long fileSize(Path path)
    {
        try { return Files.size(path); }
        catch(IOException exception) { return -1; }
    }

    private void update(Job job, State state, int progress, String message, String log)
    {
        job.state = state;
        job.progress = progress;
        job.message = message;
        job.log(log);
    }

    private static void checkCancelled(Job job) throws CancelledException
    {
        if(job.cancelled.get()) throw new CancelledException();
    }

    private Job requireJob(String id)
    {
        Job job = id != null ? mJobs.get(id) : null;
        if(job == null) throw new IllegalArgumentException("Support bundle not found");
        return job;
    }

    private void requireOpen()
    {
        if(mClosed.get()) throw new IllegalStateException("Support bundles are unavailable");
    }

    private void pruneFinishedJobs()
    {
        long cutoff = System.currentTimeMillis() - Duration.ofHours(6).toMillis();
        mJobs.entrySet().removeIf(entry -> {
            Job job = entry.getValue();
            if(job.createdAtMs >= cutoff) return false;
            deleteBundle(job);
            return true;
        });
    }

    private static void deleteBundle(Job job)
    {
        Path path = job.path;
        job.path = null;
        if(path != null)
        {
            try { Files.deleteIfExists(path); }
            catch(IOException ignored) { }
        }
    }

    private static String safeMessage(Exception exception, String fallback)
    {
        String message = exception.getMessage();
        return message != null && !message.isBlank() ? message : fallback;
    }

    @Override
    public void close()
    {
        if(mClosed.compareAndSet(false, true))
        {
            mJobs.values().forEach(job -> {
                job.cancelled.set(true);
                deleteBundle(job);
            });
            mWorker.shutdownNow();
        }
    }

    public enum Section
    {
        APPLICATION("application"), HEALTH("health"), HARDWARE("hardware"), SETUP("setup"), ALIASES("aliases"),
        LOGS("logs"), RECENT_ACTIVITY("recent-activity"), FULL_ACTIVITY("full-activity");
        private final String id;
        Section(String id) { this.id = id; }
        public String id() { return id; }
        public static Section fromId(String id)
        {
            for(Section section: values()) if(section.id.equals(id)) return section;
            throw new IllegalArgumentException("Unknown support bundle section");
        }
    }

    public record Request(String title, String email, String category, String issue, String description, String steps,
                          Set<Section> sections)
    {
        public Request
        {
            title = required(title, 160, "Title");
            email = required(email, 254, "Email");
            if(!email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$"))
            {
                throw new IllegalArgumentException("Email is invalid");
            }
            category = required(category, 80, "Category");
            issue = required(issue, 80, "Issue");
            description = required(description, 10_000, "Description");
            steps = required(steps, 10_000, "Steps to reproduce");
            sections = sections != null ? Collections.unmodifiableSet(EnumSet.copyOf(sections)) : Set.of();
            if(sections.isEmpty()) throw new IllegalArgumentException("Choose at least one item to include");
            if(sections.contains(Section.FULL_ACTIVITY) && sections.contains(Section.RECENT_ACTIVITY))
            {
                EnumSet<Section> normalized = EnumSet.copyOf(sections);
                normalized.remove(Section.RECENT_ACTIVITY);
                sections = Collections.unmodifiableSet(normalized);
            }
        }
        private static String required(String value, int maximum, String label)
        {
            String result = value != null ? value.strip() : "";
            if(result.isEmpty() || result.length() > maximum)
                throw new IllegalArgumentException(label + " is required and must be shorter than " + maximum + " characters");
            return result;
        }
    }

    public enum State { QUEUED, GENERATING, READY, UPLOADING, UPLOADED, CANCELLED, FAILED }
    public record Status(String id, String state, int progress, String message, List<String> output,
                         long bytes, String fileName, boolean canSubmit, boolean canDownload) { }
    public record PreparedDownload(Path path, String fileName, long bytes) { }

    private static final class Job
    {
        private final String id;
        private final Request request;
        private final long createdAtMs = System.currentTimeMillis();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final List<String> output = Collections.synchronizedList(new ArrayList<>());
        private final Set<String> included = Collections.synchronizedSet(new LinkedHashSet<>());
        private final Map<String,Long> rowCounts = Collections.synchronizedMap(new LinkedHashMap<>());
        private final Map<String,String> omitted = Collections.synchronizedMap(new LinkedHashMap<>());
        private volatile State state = State.QUEUED;
        private volatile int progress;
        private volatile String message = "Waiting to start…";
        private volatile Path path;
        private volatile String fileName;
        private volatile long bytes;
        private Job(String id, Request request) { this.id = id; this.request = request; }
        private void log(String value)
        {
            if(value != null && !value.isBlank())
            {
                synchronized(output)
                {
                    if(output.size() >= 100) output.remove(0);
                    output.add(value);
                }
            }
        }
        private Status status()
        {
            List<String> snapshot;
            synchronized(output) { snapshot = List.copyOf(output); }
            boolean ready = state == State.READY && path != null;
            return new Status(id, state.name().toLowerCase(Locale.ROOT), progress, message, snapshot, bytes,
                fileName, ready, ready);
        }
    }

    private static final class NonClosingOutputStream extends OutputStream
    {
        private final OutputStream delegate;
        private NonClosingOutputStream(OutputStream delegate) { this.delegate = delegate; }
        @Override public void write(int value) throws IOException { delegate.write(value); }
        @Override public void write(byte[] value, int offset, int length) throws IOException
        { delegate.write(value, offset, length); }
        @Override public void flush() throws IOException { delegate.flush(); }
        @Override public void close() throws IOException { delegate.flush(); }
    }

    private static final class CancelledException extends Exception { }
}
