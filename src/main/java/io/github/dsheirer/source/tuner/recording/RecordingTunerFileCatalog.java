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
package io.github.dsheirer.source.tuner.recording;

import io.github.dsheirer.portable.PortableApplicationPaths;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Explicitly rescanned, bounded catalog of local I/Q WAV files that can be used as recording tuners. File names are
 * display-only; browser requests refer to random in-memory IDs. Scans and header reads must run on an administrator
 * request worker, never on a tuner sample or decoder callback.
 */
public final class RecordingTunerFileCatalog
{
    public static final int MAXIMUM_FILES = 256;
    public static final int MAXIMUM_SCANNED_DIRECTORY_ENTRIES = 2_048;
    private static final long MAXIMUM_HEADER_BYTES = 64 * 1_024;
    private static final Pattern RECORDED_FREQUENCY = Pattern.compile(
        ".*_(\\d+)_baseband_\\d{8}_\\d{6}\\.wav", Pattern.CASE_INSENSITIVE);
    private final Path mDirectory;
    private volatile CatalogState mState = new CatalogState(new ScanResult(List.of(), 0, false), Map.of());

    public RecordingTunerFileCatalog(Path directory)
    {
        mDirectory = Objects.requireNonNull(directory, "Recording tuner directory cannot be null")
            .toAbsolutePath().normalize();
    }

    public static Path defaultDirectory()
    {
        return PortableApplicationPaths.getDataRoot().resolve("recording_tuners").toAbsolutePath().normalize();
    }

    /**
     * Rechecks a managed recording when playback starts, including after a receiver restart. Legacy recording paths
     * outside the managed folder retain their existing behavior.
     */
    public static void requireManagedPlaybackFile(String recordingPath) throws IOException
    {
        requireManagedPlaybackFile(recordingPath, defaultDirectory());
    }

    static void requireManagedPlaybackFile(String recordingPath, Path managedDirectory) throws IOException
    {
        if(recordingPath == null || recordingPath.isBlank())
        {
            throw new IOException("Recording path is unavailable");
        }

        Path suppliedFile;
        try
        {
            suppliedFile = Path.of(recordingPath).toAbsolutePath();
        }
        catch(InvalidPathException exception)
        {
            throw new IOException("Recording path is invalid", exception);
        }

        Path directory = managedDirectory.toAbsolutePath().normalize();
        Path file = suppliedFile.normalize();
        if(!suppliedFile.startsWith(directory) && !file.startsWith(directory))
        {
            return;
        }

        if(!file.startsWith(directory) || !directory.equals(file.getParent()) ||
            Files.isSymbolicLink(directory) ||
            !file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".wav"))
        {
            throw new IOException("Managed recording is outside its directory or uses a symbolic link");
        }

        BasicFileAttributes directoryAttributes = Files.readAttributes(directory, BasicFileAttributes.class,
            LinkOption.NOFOLLOW_LINKS);
        BasicFileAttributes fileAttributes = Files.readAttributes(file, BasicFileAttributes.class,
            LinkOption.NOFOLLOW_LINKS);
        if(!directoryAttributes.isDirectory() || directoryAttributes.isSymbolicLink() ||
            !fileAttributes.isRegularFile() || fileAttributes.isSymbolicLink() ||
            !file.toRealPath().getParent().equals(directory.toRealPath()))
        {
            throw new IOException("Managed recording is not a regular file inside its directory");
        }
    }

    public ScanResult snapshot()
    {
        return mState.result();
    }

    /**
     * Refreshes the catalog only when requested. An absent folder is created so files can be copied into it locally.
     */
    public synchronized ScanResult rescan() throws IOException
    {
        Path directory = requireDirectory();
        Map<Path, FileSnapshot> oldByPath = new HashMap<>();
        mState.filesById().values().forEach(file -> oldByPath.put(file.path(), file));
        Map<String, FileSnapshot> filesById = new LinkedHashMap<>();
        List<Entry> entries = new ArrayList<>();
        int rejectedCount = 0;
        int inspected = 0;
        boolean truncated = false;

        try(DirectoryStream<Path> stream = Files.newDirectoryStream(directory))
        {
            for(Path candidate: stream)
            {
                if(++inspected > MAXIMUM_SCANNED_DIRECTORY_ENTRIES)
                {
                    truncated = true;
                    break;
                }

                String name = candidate.getFileName().toString();
                if(!name.toLowerCase(Locale.ROOT).endsWith(".wav"))
                {
                    continue;
                }

                if(entries.size() >= MAXIMUM_FILES)
                {
                    truncated = true;
                    break;
                }

                try
                {
                    BasicFileAttributes attributes = requireRegularFile(directory, candidate);
                    WaveHeader header = readWaveHeader(candidate, attributes.size());
                    FileSnapshot old = oldByPath.get(candidate);
                    String id = old != null && old.matches(attributes, header) ? old.entry().id() :
                        UUID.randomUUID().toString();
                    Entry entry = new Entry(id, name, attributes.size(), header.sampleRateHz(),
                        suggestedFrequency(name));
                    FileSnapshot file = new FileSnapshot(candidate, entry, attributes.size(),
                        attributes.lastModifiedTime(), attributes.fileKey(), header);
                    filesById.put(id, file);
                    entries.add(entry);
                }
                catch(IOException | RuntimeException exception)
                {
                    rejectedCount++;
                }
            }
        }

        entries.sort(Comparator.comparing(Entry::name, String.CASE_INSENSITIVE_ORDER));
        ScanResult result = new ScanResult(List.copyOf(entries), rejectedCount, truncated);
        mState = new CatalogState(result, Map.copyOf(filesById));
        return result;
    }

    /**
     * Resolves a catalog ID immediately before tuner creation. The file may have changed since rescan, so check its
     * location, type, size, timestamp, and bounded WAV header again. The path is never returned to the browser.
     */
    public Path resolve(String id) throws IOException
    {
        FileSnapshot file = mState.filesById().get(id);
        if(file == null)
        {
            throw new IllegalArgumentException("Select a recording from the current catalog");
        }

        Path directory = requireDirectory();
        BasicFileAttributes attributes = requireRegularFile(directory, file.path());
        WaveHeader header = readWaveHeader(file.path(), attributes.size());
        if(!file.matches(attributes, header))
        {
            throw new IOException("Recording changed since the last rescan; rescan and select it again");
        }

        return file.path();
    }

    private Path requireDirectory() throws IOException
    {
        if(Files.isSymbolicLink(mDirectory))
        {
            throw new IOException("Recording tuner directory must not be a symbolic link");
        }
        Files.createDirectories(mDirectory);
        BasicFileAttributes attributes = Files.readAttributes(mDirectory, BasicFileAttributes.class,
            LinkOption.NOFOLLOW_LINKS);
        if(!attributes.isDirectory() || attributes.isSymbolicLink())
        {
            throw new IOException("Recording tuner directory is unavailable");
        }
        return mDirectory.toRealPath(LinkOption.NOFOLLOW_LINKS);
    }

    private static BasicFileAttributes requireRegularFile(Path directory, Path candidate) throws IOException
    {
        if(!candidate.getParent().equals(directory) || !candidate.getFileName().toString().toLowerCase(Locale.ROOT)
            .endsWith(".wav"))
        {
            throw new IOException("Recording must be a WAV file directly inside the recording tuner directory");
        }
        BasicFileAttributes attributes = Files.readAttributes(candidate, BasicFileAttributes.class,
            LinkOption.NOFOLLOW_LINKS);
        if(!attributes.isRegularFile() || attributes.isSymbolicLink() ||
            !candidate.toRealPath(LinkOption.NOFOLLOW_LINKS).getParent().equals(directory))
        {
            throw new IOException("Recording must be a regular file inside the recording tuner directory");
        }
        return attributes;
    }

    /** Reads only chunk headers and the first 16 bytes of fmt; a multi-gigabyte data chunk is never read. */
    private static WaveHeader readWaveHeader(Path file, long fileSize) throws IOException
    {
        if(fileSize < 44)
        {
            throw new IOException("WAV header is incomplete");
        }
        try(FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))
        {
            ByteBuffer riff = read(channel, 0, 12);
            if(!fourCc(riff, 0).equals("RIFF") || !fourCc(riff, 8).equals("WAVE"))
            {
                throw new IOException("Only RIFF/WAVE recordings are supported");
            }
            long riffEnd = 8 + Integer.toUnsignedLong(riff.getInt(4));
            if(riffEnd > fileSize || riffEnd < 44)
            {
                throw new IOException("WAV length is invalid");
            }

            long offset = 12;
            Integer sampleRate = null;
            while(offset + 8 <= Math.min(riffEnd, MAXIMUM_HEADER_BYTES))
            {
                ByteBuffer chunk = read(channel, offset, 8);
                String type = fourCc(chunk, 0);
                long length = Integer.toUnsignedLong(chunk.getInt(4));
                long content = offset + 8;
                long next = content + length + (length & 1);
                if(next > riffEnd + 1 || next > fileSize + 1)
                {
                    throw new IOException("WAV chunk length is invalid");
                }

                if(type.equals("fmt "))
                {
                    if(length < 16 || content + 16 > MAXIMUM_HEADER_BYTES)
                    {
                        throw new IOException("WAV format is unsupported");
                    }
                    ByteBuffer format = read(channel, content, 16);
                    int encoding = Short.toUnsignedInt(format.getShort(0));
                    int channels = Short.toUnsignedInt(format.getShort(2));
                    long rate = Integer.toUnsignedLong(format.getInt(4));
                    long byteRate = Integer.toUnsignedLong(format.getInt(8));
                    int alignment = Short.toUnsignedInt(format.getShort(12));
                    int bits = Short.toUnsignedInt(format.getShort(14));
                    if(encoding != 1 || channels != 2 || bits != 16 || alignment != 4 ||
                        rate <= 0 || rate > 20_000_000 || byteRate != rate * 4)
                    {
                        throw new IOException("Recording must contain stereo 16-bit PCM I/Q samples");
                    }
                    sampleRate = (int)rate;
                }
                else if(type.equals("data"))
                {
                    if(sampleRate == null || length == 0 || length % 4 != 0 || content + length > fileSize)
                    {
                        throw new IOException("WAV data is incomplete or format is missing");
                    }
                    return new WaveHeader(sampleRate, content, length);
                }

                offset = next;
            }
            throw new IOException("WAV data header is missing");
        }
    }

    private static ByteBuffer read(FileChannel channel, long offset, int length) throws IOException
    {
        ByteBuffer buffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
        while(buffer.hasRemaining())
        {
            if(channel.read(buffer, offset + buffer.position()) <= 0)
            {
                throw new IOException("WAV header is incomplete");
            }
        }
        buffer.flip();
        return buffer;
    }

    private static String fourCc(ByteBuffer buffer, int offset)
    {
        return "" + (char)(buffer.get(offset) & 0xff) + (char)(buffer.get(offset + 1) & 0xff) +
            (char)(buffer.get(offset + 2) & 0xff) + (char)(buffer.get(offset + 3) & 0xff);
    }

    private static Long suggestedFrequency(String filename)
    {
        Matcher matcher = RECORDED_FREQUENCY.matcher(filename);
        if(matcher.matches())
        {
            try
            {
                long frequency = Long.parseLong(matcher.group(1));
                if(frequency >= 1_000_000 && frequency <= 2_147_483_647)
                {
                    return frequency;
                }
            }
            catch(NumberFormatException ignored)
            {
                // Leave frequency for the administrator to enter.
            }
        }
        return null;
    }

    public record Entry(String id, String name, long sizeBytes, int sampleRateHz, Long suggestedCenterFrequencyHz) { }

    public record ScanResult(List<Entry> entries, int rejectedCount, boolean truncated) { }

    private record WaveHeader(int sampleRateHz, long dataOffset, long dataLength) { }

    private record FileSnapshot(Path path, Entry entry, long size, FileTime modified, Object key, WaveHeader header)
    {
        private boolean matches(BasicFileAttributes attributes, WaveHeader currentHeader)
        {
            return size == attributes.size() && modified.equals(attributes.lastModifiedTime()) &&
                Objects.equals(key, attributes.fileKey()) && header.equals(currentHeader);
        }
    }

    private record CatalogState(ScanResult result, Map<String, FileSnapshot> filesById) { }
}
