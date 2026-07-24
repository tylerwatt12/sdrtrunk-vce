/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */
package io.github.dsheirer.record;

import io.github.dsheirer.audio.call.AudioCallId;
import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.ResolverStyle;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parsed and validated path in the immutable {@code calls/v1} managed-recording layout.
 *
 * <p>This parser is deliberately narrower than a generic safe-path check.  A path is managed only when every component
 * is one that {@link ManagedCallRecording} can emit, the directory date agrees with the UTC filename timestamp, and the
 * relative path has exactly the released v1 shape.  Unknown and future layouts therefore remain untouched.</p>
 */
final class ManagedRecordingPath
{
    static final int RELATIVE_NAME_COUNT = 10;
    static final int DIRECTORY_PREFIX_NAME_COUNT = 9;
    static final String STAGING_FILE_NAME = "audio.tmp";
    private static final int MAXIMUM_NAMED_SEGMENT_LENGTH = 28;
    private static final int MAXIMUM_SEGMENT_BASE_LENGTH = 15;
    private static final int MAXIMUM_RELATIVE_PATH_LENGTH = 208;
    private static final Pattern YEAR = Pattern.compile("[0-9]{4}");
    private static final Pattern MONTH_OR_DAY = Pattern.compile("[0-9]{2}");
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{12}");
    private static final Pattern SAFE_SEGMENT_BASE =
        Pattern.compile("_?[a-z0-9]+(?:-[a-z0-9]+)*(?:-)?");
    private static final Pattern WORK_DIRECTORY =
        Pattern.compile("\\.recording-[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-" +
            "[0-9a-f]{12}\\.work");
    private static final Pattern FILE_NAME = Pattern.compile(
        "([0-9]{8}T[0-9]{6}\\.[0-9]{3}Z)-" +
            "((?:0|[1-9a-z][0-9a-z]*)-(?:0|[1-9a-z][0-9a-z]*)-(?:0|[1-9a-z][0-9a-z]*)|" +
            "unknown-[0-9a-f]{12})(\\.wav|\\.mp3)");
    private static final DateTimeFormatter FILE_TIME = new DateTimeFormatterBuilder()
        .appendPattern("uuuuMMdd'T'HHmmss.SSS'Z'")
        .toFormatter(Locale.ROOT)
        .withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter FILE_TIME_OUTPUT =
        DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss.SSS'Z'", Locale.ROOT).withZone(ZoneOffset.UTC);
    private static final Set<String> WINDOWS_RESERVED_NAMES = Set.of("con", "prn", "aux", "nul",
        "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
        "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9");

    private final Path mRelativePath;
    private final LocalDate mDate;
    private final Instant mCompletedAt;
    private final String mSystemSegment;
    private final String mSiteSegment;
    private final String mChannelSegment;
    private final String mTalkgroupSegment;
    private final String mCallIdentity;
    private final RecordFormat mFormat;

    private ManagedRecordingPath(Path relativePath, LocalDate date, Instant completedAt, String systemSegment,
                                 String siteSegment, String channelSegment, String talkgroupSegment,
                                 String callIdentity, RecordFormat format)
    {
        mRelativePath = relativePath;
        mDate = date;
        mCompletedAt = completedAt;
        mSystemSegment = systemSegment;
        mSiteSegment = siteSegment;
        mChannelSegment = channelSegment;
        mTalkgroupSegment = talkgroupSegment;
        mCallIdentity = callIdentity;
        mFormat = format;
    }

    /**
     * Creates and resolves the one real recording root shared by the writer, catalog admission, and retention.
     * Platform aliases and an explicitly configured root symlink are resolved once; links below this boundary remain
     * forbidden.
     */
    static Path prepareRoot(Path recordingRoot)
    {
        Objects.requireNonNull(recordingRoot, "Recording root cannot be null");
        Path normalized = recordingRoot.toAbsolutePath().normalize();

        try
        {
            Files.createDirectories(normalized);
            Path realRoot = normalized.toRealPath();
            BasicFileAttributes attributes =
                Files.readAttributes(realRoot, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);

            if(attributes.isSymbolicLink() || !attributes.isDirectory())
            {
                throw new IOException("Recording root is not a real directory");
            }

            return realRoot;
        }
        catch(IOException exception)
        {
            throw new IllegalArgumentException("Unable to prepare the recording root: " + normalized, exception);
        }
    }

    /**
     * Parses a path relative to the configured recordings root.
     */
    static Optional<ManagedRecordingPath> parse(Path relativePath)
    {
        if(relativePath == null || relativePath.isAbsolute() || !relativePath.normalize().equals(relativePath) ||
            relativePath.getNameCount() != RELATIVE_NAME_COUNT ||
            relativePath.toString().length() > MAXIMUM_RELATIVE_PATH_LENGTH ||
            !ManagedCallRecording.MANAGED_DIRECTORY.equals(component(relativePath, 0)) ||
            !ManagedCallRecording.LAYOUT_VERSION.equals(component(relativePath, 1)))
        {
            return Optional.empty();
        }

        Path directoryPrefix = relativePath.subpath(2, DIRECTORY_PREFIX_NAME_COUNT);

        if(!isValidDirectoryPrefix(directoryPrefix))
        {
            return Optional.empty();
        }

        LocalDate date = date(directoryPrefix).orElse(null);
        Matcher matcher = FILE_NAME.matcher(component(relativePath, 9));

        if(date == null || !matcher.matches() || !validCallIdentity(matcher.group(2)))
        {
            return Optional.empty();
        }

        try
        {
            Instant completedAt = LocalDateTime.parse(matcher.group(1), FILE_TIME).toInstant(ZoneOffset.UTC);

            if(completedAt.toEpochMilli() <= 0 || !completedAt.atZone(ZoneOffset.UTC).toLocalDate().equals(date))
            {
                return Optional.empty();
            }

            RecordFormat format = switch(matcher.group(3))
            {
                case ".wav" -> RecordFormat.WAVE;
                case ".mp3" -> RecordFormat.MP3;
                default -> null;
            };

            if(format == null)
            {
                return Optional.empty();
            }

            return Optional.of(new ManagedRecordingPath(relativePath, date, completedAt,
                component(relativePath, 5), component(relativePath, 6), component(relativePath, 7),
                component(relativePath, 8), matcher.group(2), format));
        }
        catch(DateTimeException exception)
        {
            return Optional.empty();
        }
    }

    /**
     * Validates a real, non-empty regular file below the configured recordings root without following managed-tree
     * symbolic links.
     */
    static Optional<ManagedRecordingPath> inspect(Path recordingRoot, Path candidate) throws IOException
    {
        Objects.requireNonNull(recordingRoot, "Recording root cannot be null");

        if(candidate == null || !candidate.isAbsolute() || !candidate.normalize().equals(candidate))
        {
            return Optional.empty();
        }

        Path configuredRoot = recordingRoot.toAbsolutePath().normalize();
        Path realRoot = configuredRoot.toRealPath();
        Path normalizedCandidate = candidate.normalize();
        Path candidateRoot = normalizedCandidate;

        for(int index = 0; index < RELATIVE_NAME_COUNT; index++)
        {
            candidateRoot = candidateRoot.getParent();

            if(candidateRoot == null)
            {
                return Optional.empty();
            }
        }

        //Resolve only the configured/candidate roots, never a component inside the managed tree.  This accepts
        //platform aliases such as /var and /private/var while the component walk below still rejects every symbolic
        //link beneath the verified recording root.
        if(!candidateRoot.toRealPath().equals(realRoot))
        {
            return Optional.empty();
        }

        Path relative = candidateRoot.relativize(normalizedCandidate);
        Optional<ManagedRecordingPath> parsed = parse(relative);

        if(parsed.isEmpty())
        {
            return Optional.empty();
        }

        Path current = realRoot;

        for(int index = 0; index < relative.getNameCount(); index++)
        {
            current = current.resolve(relative.getName(index));
            BasicFileAttributes attributes;

            try
            {
                attributes = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            }
            catch(IOException exception)
            {
                return Optional.empty();
            }

            if(attributes.isSymbolicLink() ||
                index < relative.getNameCount() - 1 && !attributes.isDirectory() ||
                index == relative.getNameCount() - 1 && (!attributes.isRegularFile() || attributes.size() <= 0))
            {
                return Optional.empty();
            }
        }

        return parsed;
    }

    /**
     * Opens one inspected managed file without allowing a replaced managed-tree ancestor to redirect the read.
     *
     * <p>Filesystems with secure directory streams resolve every component relative to an already-open directory
     * handle. Windows holds no-share-delete handles on each verified directory while opening the file. Providers
     * without either containment primitive fail closed. Every successful path verifies the immutable catalog byte
     * size before returning the channel.</p>
     */
    static Optional<SeekableByteChannel> openReadOnly(Path recordingRoot, Path candidate, long expectedByteSize,
                                                      RecordFormat expectedFormat) throws IOException
    {
        return openReadOnly(recordingRoot, candidate, expectedByteSize, expectedFormat, () -> {});
    }

    static Optional<SeekableByteChannel> openReadOnly(Path recordingRoot, Path candidate, long expectedByteSize,
                                                      RecordFormat expectedFormat, Runnable afterInspection)
        throws IOException
    {
        Objects.requireNonNull(afterInspection, "Managed recording open observer cannot be null");

        if(expectedByteSize < 1 || expectedFormat == null)
        {
            throw new IllegalArgumentException("Managed recording open metadata is invalid");
        }

        Optional<ManagedRecordingPath> inspected = inspect(recordingRoot, candidate);

        if(inspected.isEmpty() || inspected.get().format() != expectedFormat)
        {
            return Optional.empty();
        }

        Path realRoot = recordingRoot.toAbsolutePath().normalize().toRealPath();
        Path relative = inspected.get().relativePath();
        afterInspection.run();
        Optional<SeekableByteChannel> opened = Optional.empty();
        boolean secureProvider = false;

        try(DirectoryStream<Path> directory = Files.newDirectoryStream(realRoot))
        {
            if(directory instanceof SecureDirectoryStream<?> secureDirectory)
            {
                secureProvider = true;
                @SuppressWarnings("unchecked")
                SecureDirectoryStream<Path> secure = (SecureDirectoryStream<Path>)secureDirectory;
                opened = openSecurely(secure, relative, expectedByteSize);
            }
        }
        catch(IOException | RuntimeException exception)
        {
            closeQuietly(opened);
            throw exception;
        }

        if(secureProvider)
        {
            return opened;
        }

        if(isWindows())
        {
            return WindowsManagedRecordingOpen.open(realRoot, candidate, relative, expectedByteSize,
                expectedFormat);
        }

        return Optional.empty();
    }

    private static Optional<SeekableByteChannel> openSecurely(SecureDirectoryStream<Path> root, Path relative,
                                                               long expectedByteSize)
    {
        SecureDirectoryStream<Path> current = root;
        java.util.ArrayList<SecureDirectoryStream<Path>> opened = new java.util.ArrayList<>();
        SeekableByteChannel channel = null;

        try
        {
            for(int index = 0; index < relative.getNameCount() - 1; index++)
            {
                SecureDirectoryStream<Path> child =
                    current.newDirectoryStream(relative.getName(index), LinkOption.NOFOLLOW_LINKS);
                opened.add(child);
                current = child;
            }

            Set<OpenOption> options = Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
            channel = current.newByteChannel(relative.getFileName(), options);

            if(channel.size() != expectedByteSize)
            {
                channel.close();
                return Optional.empty();
            }

            SeekableByteChannel openedChannel = channel;
            channel = null;
            return Optional.of(openedChannel);
        }
        catch(IOException | RuntimeException exception)
        {
            if(channel != null)
            {
                try
                {
                    channel.close();
                }
                catch(IOException closeException)
                {
                    exception.addSuppressed(closeException);
                }
            }

            return Optional.empty();
        }
        finally
        {
            for(int index = opened.size() - 1; index >= 0; index--)
            {
                try
                {
                    opened.get(index).close();
                }
                catch(IOException ignored)
                {
                }
            }
        }
    }

    private static void closeQuietly(Optional<SeekableByteChannel> opened)
    {
        if(opened != null && opened.isPresent())
        {
            try
            {
                opened.get().close();
            }
            catch(IOException ignored)
            {
            }
        }
    }

    private static boolean isWindows()
    {
        String name = System.getProperty("os.name", "");
        return name.regionMatches(true, 0, "windows", 0, "windows".length());
    }

    /**
     * Validates one through seven directory components relative to {@code calls/v1}.
     */
    static boolean isValidDirectoryPrefix(Path prefix)
    {
        if(prefix == null || prefix.isAbsolute() || !prefix.normalize().equals(prefix) ||
            prefix.getNameCount() < 1 || prefix.getNameCount() > 7)
        {
            return false;
        }

        String year = component(prefix, 0);

        if(!YEAR.matcher(year).matches())
        {
            return false;
        }

        int parsedYear = Integer.parseInt(year);

        if(parsedYear < 1970)
        {
            return false;
        }

        if(prefix.getNameCount() >= 2)
        {
            String month = component(prefix, 1);

            if(!MONTH_OR_DAY.matcher(month).matches())
            {
                return false;
            }

            int parsedMonth = Integer.parseInt(month);

            if(parsedMonth < 1 || parsedMonth > 12)
            {
                return false;
            }
        }

        if(prefix.getNameCount() >= 3 && date(prefix).isEmpty())
        {
            return false;
        }

        if(prefix.getNameCount() >= 4 &&
            !validManagedSegment(component(prefix, 3), "_unknown"))
        {
            return false;
        }

        if(prefix.getNameCount() >= 5 &&
            !validManagedSegment(component(prefix, 4), "_conventional") &&
            !"_unknown".equals(component(prefix, 4)))
        {
            return false;
        }

        if(prefix.getNameCount() >= 6 &&
            !validManagedSegment(component(prefix, 5), "_unknown"))
        {
            return false;
        }

        return prefix.getNameCount() < 7 ||
            validManagedSegment(component(prefix, 6), "_unknown");
    }

    /**
     * Parses a zero-byte reservation's implied canonical recording path.
     */
    static Optional<ManagedRecordingPath> parseReservation(Path relativePath)
    {
        if(relativePath == null || relativePath.isAbsolute() || !relativePath.normalize().equals(relativePath) ||
            relativePath.getNameCount() != RELATIVE_NAME_COUNT)
        {
            return Optional.empty();
        }

        String fileName = component(relativePath, 9);

        if(fileName.length() <= ".reserve".length() + 1 || fileName.charAt(0) != '.' ||
            !fileName.endsWith(".reserve"))
        {
            return Optional.empty();
        }

        String canonicalName = fileName.substring(1, fileName.length() - ".reserve".length());
        Path canonicalPath = relativePath.getParent().resolve(canonicalName);
        return parse(canonicalPath);
    }

    static boolean isWorkDirectoryName(String value)
    {
        return value != null && WORK_DIRECTORY.matcher(value).matches();
    }

    static boolean isStagingFileName(String value)
    {
        return STAGING_FILE_NAME.equals(value);
    }

    /**
     * Produces the one canonical retained-call filename shared by the recording writer and catalog.
     */
    static String fileName(AudioCallId callId, long completedAtMs, RecordFormat format)
    {
        Objects.requireNonNull(callId, "Call identity cannot be null");
        Objects.requireNonNull(format, "Recording format cannot be null");

        if(completedAtMs <= 0)
        {
            throw new IllegalArgumentException("Completion time must be positive");
        }

        if(callId.timeslot() < 0)
        {
            throw new IllegalArgumentException("Call timeslot cannot be negative");
        }

        return FILE_TIME_OUTPUT.format(Instant.ofEpochMilli(completedAtMs)) + '-' +
            Long.toUnsignedString(callId.producerId(), 36) + '-' +
            Long.toUnsignedString(callId.sequence(), 36) + '-' +
            Integer.toUnsignedString(callId.timeslot(), 36) + format.getExtension();
    }

    Path relativePath()
    {
        return mRelativePath;
    }

    Path relativeDirectory()
    {
        return mRelativePath.getParent();
    }

    LocalDate date()
    {
        return mDate;
    }

    Instant completedAt()
    {
        return mCompletedAt;
    }

    long completedAtMs()
    {
        return mCompletedAt.toEpochMilli();
    }

    String systemSegment()
    {
        return mSystemSegment;
    }

    String siteSegment()
    {
        return mSiteSegment;
    }

    String channelSegment()
    {
        return mChannelSegment;
    }

    String talkgroupSegment()
    {
        return mTalkgroupSegment;
    }

    String callIdentity()
    {
        return mCallIdentity;
    }

    RecordFormat format()
    {
        return mFormat;
    }

    private static Optional<LocalDate> date(Path prefix)
    {
        if(prefix.getNameCount() < 3)
        {
            return Optional.empty();
        }

        try
        {
            return Optional.of(LocalDate.of(Integer.parseInt(component(prefix, 0)),
                Integer.parseInt(component(prefix, 1)), Integer.parseInt(component(prefix, 2))));
        }
        catch(DateTimeException | NumberFormatException exception)
        {
            return Optional.empty();
        }
    }

    private static boolean validManagedSegment(String value, String fixedFallback)
    {
        if(fixedFallback.equals(value))
        {
            return true;
        }

        if(value == null || value.length() > MAXIMUM_NAMED_SEGMENT_LENGTH)
        {
            return false;
        }

        int separator = value.lastIndexOf('~');

        if(separator < 1 || separator != value.length() - 13)
        {
            return false;
        }

        String base = value.substring(0, separator);
        String hash = value.substring(separator + 1);

        if(base.length() > MAXIMUM_SEGMENT_BASE_LENGTH || !SAFE_SEGMENT_BASE.matcher(base).matches() ||
            !HASH.matcher(hash).matches())
        {
            return false;
        }

        return base.charAt(0) == '_' || !WINDOWS_RESERVED_NAMES.contains(base);
    }

    private static boolean validCallIdentity(String identity)
    {
        if(identity.startsWith("unknown-"))
        {
            return HASH.matcher(identity.substring("unknown-".length())).matches();
        }

        String[] components = identity.split("-", -1);

        if(components.length != 3)
        {
            return false;
        }

        try
        {
            Long.parseUnsignedLong(components[0], 36);
            Long.parseUnsignedLong(components[1], 36);
            Integer.parseInt(components[2], 36);
            return true;
        }
        catch(NumberFormatException exception)
        {
            return false;
        }
    }

    private static String component(Path path, int index)
    {
        return path.getName(index).toString();
    }
}
