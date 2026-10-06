/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.support;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A bounded read-only tail of existing application files. No log appender, decoder observer, or receiver callback is
 * installed. File reads and redaction run on the admitted HTTP request thread; cached snapshots are immutable and
 * browser response writes never retain the reader permit.
 */
public final class ApplicationLogService implements AutoCloseable
{
    public static final int MAXIMUM_ENTRIES = 500;
    static final int MAXIMUM_READ_BYTES = 1024 * 1024;
    static final int MAXIMUM_ENTRY_CHARACTERS = 64 * 1024;
    static final int MAXIMUM_DIRECTORY_ENTRIES = 128;
    private static final int ANCHOR_BYTES = 1024;
    private static final long CACHE_NANOSECONDS = 1_000_000_000L;
    private static final String CURRENT_FILE = "sdrtrunk_app.log";
    private static final String TRUNCATED_MESSAGE = "\n[message truncated]";
    private static final Pattern ARCHIVE = Pattern.compile("([0-9]{8})_sdrtrunk_app\\.log");
    private static final Pattern HEADER = Pattern.compile(
        "^(\\d{8} \\d{6}\\.\\d{3}) \\[[^\\r\\n]*?]\\s+(TRACE|DEBUG|INFO|WARN|ERROR)\\s+(\\S+) - (.*)$");
    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("uuuuMMdd HHmmss.SSS")
        .withResolverStyle(ResolverStyle.STRICT);
    private static final Pattern AUTHORIZATION = Pattern.compile(
        "(?i)((?:proxy-)?authorization[\\\"']?\\s*[:=]\\s*[\\\"']?)(?:bearer|basic)\\s+[^\\s,;\\\"']+");
    private static final Pattern SECRET = Pattern.compile(
        "(?i)((?:[\\\"']?)(?:api(?:[_ -]|%5f|%20|%2d)?key|access(?:[_ -]|%5f|%20|%2d)?token|" +
            "refresh(?:[_ -]|%5f|%20|%2d)?token|session(?:[_ -]|%5f|%20|%2d)?(?:id|token)|" +
            "password|passwd|pwd|secret|client(?:[_ -]|%5f|%20|%2d)?secret|authorization|proxy-authorization|token)" +
            "[\\\"']?\\s*[:=]\\s*)(?:\\\"[^\\\"\\r\\n]*\\\"|'[^'\\r\\n]*'|[^\\s,;&}]+)");
    private static final Pattern ENDPOINT = Pattern.compile("(?i)\\b[a-z][a-z0-9+.-]{1,31}://[^\\s\\\"'<>]+");
    private static final Pattern IPV4 = Pattern.compile("(?<![\\w.])(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?::[0-9]{1,5})?(?![\\w.])");
    private static final Pattern HOST_PORT = Pattern.compile("(?i)(?<![\\w.])[a-z][a-z0-9.-]{0,252}:[0-9]{1,5}\\b");
    private static final Pattern LOCAL_HOST = Pattern.compile("(?i)\\b[a-z0-9][a-z0-9.-]{0,252}\\.(?:local|lan|internal)\\b");
    private static final Pattern IPV6 = Pattern.compile("\\[(?=[^]\\r\\n]{0,64}:)[0-9a-fA-F:.%]{1,64}](?::[0-9]{1,5})?");
    private static final Pattern BARE_IPV6 = Pattern.compile(
        "(?i)(?<![a-z0-9:])(?=[0-9a-f:]{0,64}:[0-9a-f:]{0,64}:)[0-9a-f]{0,4}:[0-9a-f:]{1,64}(?![a-z0-9:])");
    private static final Pattern QUOTED_USER_PATH = Pattern.compile(
        "(?i)([\\\"'])((?:/Users/|/home/|[a-z]:\\\\Users\\\\)[^\\r\\n\\\"']*)[\\\"']");
    private static final Pattern USER_PATH = Pattern.compile(
        "(?i)(?:/Users/|/home/|[a-z]:\\\\Users\\\\)[^\\r\\n\\\"'<>]*");
    private final Supplier<Path> mDirectory;
    private final LongSupplier mNanoClock;
    private final LongSupplier mWallClock;
    private final Semaphore mReader = new Semaphore(1);
    private final AtomicBoolean mClosed = new AtomicBoolean();
    private volatile Cached mCurrent;
    private volatile Cached mPrevious;

    public ApplicationLogService(Supplier<Path> directory)
    {
        this(directory, System::nanoTime, System::currentTimeMillis);
    }

    public ApplicationLogService(Path directory)
    {
        this(() -> directory);
    }

    ApplicationLogService(Supplier<Path> directory, LongSupplier nanoClock, LongSupplier wallClock)
    {
        mDirectory = Objects.requireNonNull(directory);
        mNanoClock = Objects.requireNonNull(nanoClock);
        mWallClock = Objects.requireNonNull(wallClock);
    }

    /** Compatibility snapshot for callers that do not track the file revision. */
    public Snapshot snapshot(String log, String after) throws IOException
    {
        return snapshot(log, after, null);
    }

    /** A changed tail upserts its cursor entry so appended stack-trace lines are never lost. */
    public Snapshot snapshot(String log, String after, String revision) throws IOException
    {
        if(!"current".equals(log) && !"previous".equals(log))
        {
            throw new IllegalArgumentException("Unknown application log");
        }
        if(after != null && !after.matches("[0-9a-f]{16}:[0-9a-f]{1,16}"))
        {
            throw new IllegalArgumentException("Invalid application log cursor");
        }
        if(revision != null && !revision.matches("[0-9a-f]{16}:[0-9a-f]{1,16}"))
        {
            throw new IllegalArgumentException("Invalid application log revision");
        }
        if(mClosed.get())
        {
            throw new BusyException();
        }

        Cached cached = cache(log);
        long now = mNanoClock.getAsLong();
        if(cached == null || now - cached.generatedAtNanos() >= CACHE_NANOSECONDS)
        {
            if(!mReader.tryAcquire())
            {
                throw new BusyException();
            }
            try
            {
                if(mClosed.get())
                {
                    throw new BusyException();
                }
                cached = cache(log);
                if(cached == null || now - cached.generatedAtNanos() >= CACHE_NANOSECONDS)
                {
                    cached = read(log, cached, now);
                    if(!mClosed.get())
                    {
                        if("current".equals(log)) mCurrent = cached;
                        else mPrevious = cached;
                    }
                }
            }
            finally
            {
                mReader.release();
            }
        }
        Snapshot value = cached.snapshot();
        int cursorIndex = -1;
        for(int index = 0; index < value.entries().size(); index++)
        {
            if(value.entries().get(index).id().equals(after)) cursorIndex = index;
        }
        boolean gap = after != null && cursorIndex < 0;
        String reason = "";
        if(gap)
        {
            reason = !value.available() ? "unavailable" :
                after.startsWith(cached.generation() + ":") ? "history_limit" : cached.changeReason();
        }
        String currentRevision = cached.generation() + ":" + Long.toHexString(cached.size());
        boolean incremental = revision != null && after != null && !gap;
        List<Entry> entries = !incremental ? value.entries() : currentRevision.equals(revision) ? List.of() :
            value.entries().subList(cursorIndex, value.entries().size());
        return new Snapshot(value.log(), value.fileName(), value.available(), entries, value.updatedAt(),
            MAXIMUM_ENTRIES, value.truncated(), value.latestId(), gap, reason, currentRevision, incremental,
            value.entries().isEmpty() ? null : value.entries().getFirst().id());
    }

    private Cached cache(String log)
    {
        return "current".equals(log) ? mCurrent : mPrevious;
    }

    private Cached read(String log, Cached previous, long now) throws IOException
    {
        Path configured = Objects.requireNonNull(mDirectory.get()).toAbsolutePath().normalize();
        if(!Files.isDirectory(configured))
        {
            return unavailable(log, configured, previous, now);
        }
        // The configured directory is administrator-owned, not an HTTP path. Canonicalize it once so legitimate
        // platform aliases (for example macOS /var) work, then open only fixed filenames without following links.
        Path directory = configured.toRealPath();
        Path file = "current".equals(log) ? directory.resolve(CURRENT_FILE) : previousFile(directory);
        if(file == null || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
        {
            return unavailable(log, directory, previous, now);
        }

        BasicFileAttributes attributes;
        try
        {
            attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        }
        catch(NoSuchFileException exception)
        {
            return unavailable(log, directory, previous, now);
        }
        FileIdentity identity = new FileIdentity(file, attributes.fileKey(), attributes.creationTime().toMillis());
        byte[] anchor;
        byte[] bytes;
        boolean previousEndMatches;
        long size;
        long start;
        try(FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))
        {
            // The final component cannot become a symbolic link between the check and open. Verify the named file
            // remains the inspected regular file before using it, rather than following a replacement's attributes.
            BasicFileAttributes opened = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if(!opened.isRegularFile() || !Objects.equals(attributes.fileKey(), opened.fileKey()))
            {
                throw new IOException("Application log changed while opening");
            }
            size = channel.size();
            anchor = readBytes(channel, 0, (int)Math.min(ANCHOR_BYTES, size));
            previousEndMatches = previous != null && size >= previous.size() &&
                Arrays.equals(previous.endAnchor(), readBytes(channel,
                    previous.size() - previous.endAnchor().length, previous.endAnchor().length));
            start = Math.max(0, size - MAXIMUM_READ_BYTES);
            bytes = readBytes(channel, start, (int)Math.min(MAXIMUM_READ_BYTES, size));
        }

        boolean sameDirectory = previous != null && directory.equals(previous.directory());
        boolean sameFile = sameDirectory && identity.equals(previous.identity()) && size >= previous.size() &&
            prefixMatches(previous.anchor(), anchor) && previousEndMatches;
        String generation = sameFile ? previous.generation() : UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String changeReason = sameFile ? previous.changeReason() :
            previous != null && !sameDirectory ? "source_changed" : "rotation";
        Parsed parsed = parse(bytes, start, generation);
        List<Entry> entries = parsed.entries();
        Snapshot snapshot = new Snapshot(log, file.getFileName().toString(), true, entries, mWallClock.getAsLong(),
            MAXIMUM_ENTRIES, start > 0 || parsed.truncated(), entries.isEmpty() ? null : entries.getLast().id(), false, "",
            null, false, null);
        byte[] endAnchor = Arrays.copyOfRange(bytes, Math.max(0, bytes.length - ANCHOR_BYTES), bytes.length);
        return new Cached(snapshot, now, directory, identity, size, anchor, endAnchor, generation, changeReason);
    }

    private Cached unavailable(String log, Path directory, Cached previous, long now)
    {
        String generation = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        Snapshot snapshot = new Snapshot(log, "current".equals(log) ? CURRENT_FILE : null, false, List.of(),
            mWallClock.getAsLong(), MAXIMUM_ENTRIES, false, null, false, "", null, false, null);
        return new Cached(snapshot, now, directory, null, 0, new byte[0], new byte[0], generation,
            previous != null && !directory.equals(previous.directory()) ? "source_changed" : "rotation");
    }

    private static boolean prefixMatches(byte[] previous, byte[] current)
    {
        return previous.length <= current.length && Arrays.equals(previous, 0, previous.length, current, 0, previous.length);
    }

    private static Path previousFile(Path directory) throws IOException
    {
        Path newest = null;
        int inspected = 0;
        try(DirectoryStream<Path> stream = Files.newDirectoryStream(directory))
        {
            for(Path file: stream)
            {
                if(++inspected > MAXIMUM_DIRECTORY_ENTRIES)
                {
                    throw new IOException("Application log directory exceeds scan limit");
                }
                Matcher archive = ARCHIVE.matcher(file.getFileName().toString());
                if(archive.matches() && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                {
                    try
                    {
                        LocalDate.parse(archive.group(1), DateTimeFormatter.BASIC_ISO_DATE);
                    }
                    catch(DateTimeParseException exception)
                    {
                        continue;
                    }
                    if(newest == null || file.getFileName().toString().compareTo(newest.getFileName().toString()) > 0)
                    {
                        newest = file;
                    }
                }
            }
        }
        return newest;
    }

    private static byte[] readBytes(FileChannel channel, long start, int count) throws IOException
    {
        ByteBuffer buffer = ByteBuffer.allocate(count);
        channel.position(start);
        while(buffer.hasRemaining())
        {
            if(channel.read(buffer) <= 0) break;
        }
        return buffer.position() == count ? buffer.array() : Arrays.copyOf(buffer.array(), buffer.position());
    }

    private static Parsed parse(byte[] bytes, long start, String generation)
    {
        ArrayDeque<Entry> entries = new ArrayDeque<>(MAXIMUM_ENTRIES);
        int beginning = 0;
        if(start > 0)
        {
            while(beginning < bytes.length && bytes[beginning++] != '\n') { }
        }
        StringBuilder current = null;
        long entryOffset = 0;
        boolean truncated = false;
        for(int end = beginning; end < bytes.length; end++)
        {
            if(bytes[end] != '\n') continue;
            int lineEnd = end > beginning && bytes[end - 1] == '\r' ? end - 1 : end;
            String line = new String(bytes, beginning, lineEnd - beginning, StandardCharsets.UTF_8);
            boolean header = HEADER.matcher(line).matches();
            if(header && current != null)
            {
                Entry entry = entry(current.toString(), generation, entryOffset);
                truncated |= add(entries, entry);
                current = null;
            }
            if(current == null && (header || start == 0))
            {
                current = new StringBuilder();
                entryOffset = start + beginning;
            }
            if(current != null)
            {
                if(!current.isEmpty()) current.append('\n');
                current.append(line);
            }
            beginning = end + 1;
        }
        // An unfinished final UTF-8 line is withheld until its newline arrives. Completed stack-trace lines remain
        // grouped with the same header and cursor on subsequent full snapshots.
        if(current != null)
        {
            truncated |= add(entries, entry(current.toString(), generation, entryOffset));
        }
        return new Parsed(List.copyOf(entries), truncated);
    }

    private static boolean add(ArrayDeque<Entry> entries, Entry entry)
    {
        boolean truncated = entry.truncated();
        if(entries.size() == MAXIMUM_ENTRIES)
        {
            entries.removeFirst();
            truncated = true;
        }
        entries.addLast(entry);
        return truncated;
    }

    private static Entry entry(String original, String generation, long offset)
    {
        String text = redact(original);
        boolean truncated = text.length() > MAXIMUM_ENTRY_CHARACTERS;
        if(truncated)
        {
            int end = MAXIMUM_ENTRY_CHARACTERS - TRUNCATED_MESSAGE.length();
            if(Character.isHighSurrogate(text.charAt(end - 1))) end--;
            text = text.substring(0, end) + TRUNCATED_MESSAGE;
        }
        int newline = text.indexOf('\n');
        String first = newline < 0 ? text : text.substring(0, newline);
        String details = newline < 0 ? "" : text.substring(newline + 1);
        Matcher header = HEADER.matcher(first);
        String time = null;
        String level = "UNKNOWN";
        String source = "";
        String message = first;
        if(header.matches())
        {
            try
            {
                time = LocalDateTime.parse(header.group(1), LOG_TIME).atZone(ZoneId.systemDefault()).toInstant().toString();
            }
            catch(DateTimeParseException exception) { /* Keep a malformed source timestamp as text. */ }
            level = header.group(2);
            source = header.group(3);
            message = header.group(4);
        }
        return new Entry(generation + ":" + Long.toHexString(offset), time, level, source, message, details, text, truncated);
    }

    static String redact(String text)
    {
        String safe = AUTHORIZATION.matcher(text).replaceAll("$1[removed]");
        safe = SECRET.matcher(safe).replaceAll("$1[removed]");
        safe = ENDPOINT.matcher(safe).replaceAll("[endpoint hidden]");
        safe = IPV4.matcher(safe).replaceAll("[endpoint hidden]");
        safe = redactHostPorts(safe);
        safe = LOCAL_HOST.matcher(safe).replaceAll("[endpoint hidden]");
        safe = IPV6.matcher(safe).replaceAll("[endpoint hidden]");
        safe = BARE_IPV6.matcher(safe).replaceAll("[endpoint hidden]");
        safe = QUOTED_USER_PATH.matcher(safe).replaceAll("$1[path hidden]$1");
        safe = USER_PATH.matcher(safe).replaceAll("[path hidden]");
        return safe.codePoints().filter(value -> value == '\n' || value == '\t' || !Character.isISOControl(value))
            .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
    }

    private static String redactHostPorts(String text)
    {
        return HOST_PORT.matcher(text).replaceAll(match -> {
            String token = match.group();
            String name = token.substring(0, token.lastIndexOf(':')).toLowerCase(Locale.ROOT);
            // Java stack frames use the same colon-number spelling as network ports. Keep their source references
            // intact; other source extensions are preserved when explicitly enclosed in a stack-frame location.
            boolean source = name.endsWith(".java") || match.start() > 0 && text.charAt(match.start() - 1) == '(' &&
                match.end() < text.length() && text.charAt(match.end()) == ')' &&
                name.matches(".*\\.(?:kt|scala|groovy|js|ts|c|cpp|h|py)");
            return source ? Matcher.quoteReplacement(token) : "[endpoint hidden]";
        });
    }

    @Override
    public void close()
    {
        mClosed.set(true);
        mCurrent = null;
        mPrevious = null;
    }

    public record Entry(String id, String time, String level, String source, String message, String details, String text,
                        boolean truncated) { }

    public record Snapshot(String log, String fileName, boolean available, List<Entry> entries, long updatedAt,
                           int maxEntries, boolean truncated, String latestId, boolean gap, String changeReason,
                           String revision, boolean incremental, String firstId) { }

    public static final class BusyException extends IOException
    {
        public BusyException()
        {
            super("Application log reader is unavailable");
        }
    }

    private record Parsed(List<Entry> entries, boolean truncated) { }
    private record FileIdentity(Path path, Object fileKey, long createdAt) { }
    private record Cached(Snapshot snapshot, long generatedAtNanos, Path directory, FileIdentity identity, long size,
                          byte[] anchor, byte[] endAnchor, String generation, String changeReason) { }
}
