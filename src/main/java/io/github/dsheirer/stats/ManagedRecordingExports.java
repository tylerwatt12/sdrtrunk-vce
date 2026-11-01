package io.github.dsheirer.stats;

import io.github.dsheirer.record.managed.ManagedRecordingCatalog;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Ephemeral user-requested exports. One encoder, two retained jobs, no receiver or catalog writer work. */
final class ManagedRecordingExports implements AutoCloseable
{
    static final int MAXIMUM_CALLS = 1000;
    private static final long MAXIMUM_BYTES = 128L * 1024 * 1024;
    private final ScheduledExecutorService mWorker = Executors.newScheduledThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "recording-mp3-export");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });
    private final Map<String,Job> mJobs = new LinkedHashMap<>();
    private boolean mRunning, mClosed, mCleanerStarted;

    static List<ManagedRecordingCatalog.RecordingCall> selection(ManagedRecordingCatalog catalog, List<Long> ids)
        throws SQLException, IOException
    {
        List<ManagedRecordingCatalog.RecordingCall> calls = new ArrayList<>();
        long bytes = 0, duration = 0;
        for(long id: ids)
        {
            var call = catalog.find(id);
            Path path;
            long size;
            try
            {
                path = catalog.audioPath(id);
                size = path == null ? 0 : Files.size(path);
            }
            catch(IOException exception)
            {
                throw new StatsApiException(404, "recording_not_found",
                    "A selected recording is unavailable. Review your selection and try again.");
            }
            if(call == null || path == null)
                throw new StatsApiException(404, "recording_not_found",
                    "A selected recording is unavailable. Review your selection and try again.");
            bytes += size;
            duration += Math.max(0, call.durationMs());
            if(bytes > MAXIMUM_BYTES || duration > Duration.ofHours(2).toMillis())
                throw new StatsApiException(400, "export_too_large",
                    "Select at most two hours and 128 MB of recordings.");
            calls.add(call);
        }
        calls.sort(Comparator.comparingLong(ManagedRecordingCatalog.RecordingCall::startMs)
            .thenComparingLong(ManagedRecordingCatalog.RecordingCall::id));
        return calls;
    }

    synchronized Job start(ManagedRecordingCatalog catalog, List<Long> ids, String owner)
    {
        clean();
        if(mClosed || mRunning || mJobs.size() >= 2)
            throw new StatsApiException(429, "export_busy",
                "Another MP3 export is active. Close an earlier export or try again shortly.");
        Job job = new Job(owner, ids.size());
        mJobs.put(job.id, job);
        mRunning = true;
        if(!mCleanerStarted)
        {
            mCleanerStarted = true;
            mWorker.scheduleWithFixedDelay(this::clean, 1, 1, TimeUnit.MINUTES);
        }
        mWorker.execute(() -> generate(job, catalog, ids));
        return job;
    }

    synchronized Job find(String id, String owner)
    {
        clean();
        Job job = mJobs.get(id);
        if(job == null || !job.owner.equals(owner))
            throw new StatsApiException(404, "export_not_found", "This MP3 export has expired. Create it again.");
        return job;
    }

    private void generate(Job job, ManagedRecordingCatalog catalog, List<Long> ids)
    {
        try
        {
            job.check();
            var calls = selection(catalog, ids);
            if(Files.getFileStore(catalog.databaseFile().getParent()).getUsableSpace() < MAXIMUM_BYTES * 2)
                throw new StatsApiException(507, "export_space_unavailable",
                    "There is not enough receiver storage to create this MP3. Free some space and try again.");
            // The catalog's parent is receiver-owned data. Temporary audio never goes to a shared OS temp folder.
            job.directory = Files.createTempDirectory(catalog.databaseFile().getParent(), "recording-export-");
            Path input = job.directory.resolve("input.mp3");
            Path output = job.directory.resolve("incident.mp3");
            long copied = 0;
            try(OutputStream stream = Files.newOutputStream(output);
                RecordingMp3Exporter encoder = new RecordingMp3Exporter(stream, job::check))
            {
                for(var call: calls)
                {
                    job.check();
                    Path source = catalog.audioPath(call.id());
                    if(source == null) throw new IOException("Selected audio unavailable");
                    try(InputStream sourceStream = Files.newInputStream(source);
                        OutputStream snapshot = Files.newOutputStream(input))
                    {
                        byte[] buffer = new byte[16384];
                        int count;
                        while((count = sourceStream.read(buffer)) != -1)
                        {
                            job.check();
                            copied += count;
                            if(copied > MAXIMUM_BYTES) throw new IOException("Selected audio too large");
                            snapshot.write(buffer, 0, count);
                        }
                    }
                    encoder.append(input);
                    Files.delete(input);
                    job.completed++;
                }
                encoder.finish();
            }
            job.check();
            job.output = output;
            job.state = "ready";
        }
        catch(CancellationException exception) { job.state = "cancelled"; }
        catch(Exception exception)
        {
            job.error = exception instanceof StatsApiException ? exception.getMessage() :
                "The MP3 could not be created. A selected recording may be unavailable or unreadable. Review your selection and try again.";
            job.state = "failed";
        }
        finally
        {
            synchronized(this) { mRunning = false; job.finished = true; clean(); }
        }
    }

    synchronized void cancel(Job job)
    {
        job.cancelled = true;
        clean();
    }

    private synchronized void clean()
    {
        long now = System.currentTimeMillis();
        mJobs.values().removeIf(job -> {
            if(now - job.created > Duration.ofMinutes(10).toMillis()) job.cancelled = true;
            if(!job.finished || job.readers > 0 || !job.cancelled && "ready".equals(job.state)) return false;
            // Failed jobs retain their safe status until dismissed/expired, while their partial audio is removed.
            if(!job.cleanup()) return false;
            return job.cancelled;
        });
    }

    synchronized InputStream open(Job job) throws IOException
    {
        if(job.cancelled || !"ready".equals(job.state))
            throw new StatsApiException(409, "export_not_ready", "The MP3 is not ready. Wait for the export to finish.");
        InputStream stream = Files.newInputStream(job.output);
        job.readers++;
        return new FilterInputStream(stream) {
            private boolean closed;
            @Override public void close() throws IOException
            {
                if(closed) return;
                closed = true;
                try { super.close(); }
                finally { synchronized(ManagedRecordingExports.this) { job.readers--; clean(); } }
            }
        };
    }

    @Override public synchronized void close()
    {
        mClosed = true;
        mJobs.values().forEach(job -> job.cancelled = true);
        mWorker.shutdownNow();
        clean();
    }

    static final class Job
    {
        final String id = UUID.randomUUID().toString();
        final String owner;
        final int total;
        final long created = System.currentTimeMillis();
        volatile String state = "running", error;
        volatile int completed;
        volatile boolean cancelled;
        volatile Path directory, output;
        boolean finished;
        int readers;

        Job(String owner, int total) { this.owner = owner; this.total = total; }

        void check()
        {
            if(cancelled || Thread.currentThread().isInterrupted() ||
                System.currentTimeMillis() - created > Duration.ofMinutes(5).toMillis())
                throw new CancellationException();
        }

        Map<String,Object> status()
        {
            Map<String,Object> status = new LinkedHashMap<>();
            status.put("id", id); status.put("state", state);
            status.put("completed", completed); status.put("total", total);
            if(error != null) status.put("error", error);
            return status;
        }

        boolean cleanup()
        {
            if(directory == null) return true;
            try
            {
                Files.deleteIfExists(directory.resolve("input.mp3"));
                Files.deleteIfExists(directory.resolve("incident.mp3"));
                Files.deleteIfExists(directory);
                return true;
            }
            catch(IOException exception) { return false; }
        }
    }
}
