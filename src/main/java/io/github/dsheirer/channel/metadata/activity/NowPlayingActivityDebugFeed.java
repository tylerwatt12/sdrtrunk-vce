/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 * ****************************************************************************
 */
package io.github.dsheirer.channel.metadata.activity;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.channel.state.State;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Temporary RadioResolve field-debug feed for observing the exact Now Playing activity-row updates that reach the UI.
 * Enable with -Drr.nowplaying.debug.feed=true and remove this class plus its call sites when no longer needed.
 */
public final class NowPlayingActivityDebugFeed
{
    public static final String ENABLE_PROPERTY = "rr.nowplaying.debug.feed";
    public static final String BIND_PROPERTY = "rr.nowplaying.debug.feed.bind";
    public static final String PORT_PROPERTY = "rr.nowplaying.debug.feed.port";
    public static final String FILE_PROPERTY = "rr.nowplaying.debug.feed.file";
    public static final String FILE_ENABLED_PROPERTY = "rr.nowplaying.debug.feed.file.enabled";
    public static final String RECENT_LIMIT_PROPERTY = "rr.nowplaying.debug.feed.recent";
    public static final String QUEUE_LIMIT_PROPERTY = "rr.nowplaying.debug.feed.queue";
    public static final int DEFAULT_PORT = 17989;

    private static final Logger LOGGER = LoggerFactory.getLogger(NowPlayingActivityDebugFeed.class);
    private static final boolean ENABLED = Boolean.getBoolean(ENABLE_PROPERTY);
    private static final NowPlayingActivityDebugFeed INSTANCE = new NowPlayingActivityDebugFeed();

    private final AtomicBoolean mStarted = new AtomicBoolean();
    private final BlockingQueue<String> mEventQueue =
        new ArrayBlockingQueue<>(getIntegerProperty(QUEUE_LIMIT_PROPERTY, 5000, 100, 100000));
    private final List<Client> mClients = new CopyOnWriteArrayList<>();
    private final Deque<String> mRecentEvents = new ArrayDeque<>();
    private final Object mRecentLock = new Object();
    private final Object mFileLock = new Object();
    private final AtomicLong mSequence = new AtomicLong();
    private final AtomicLong mDroppedEvents = new AtomicLong();
    private final int mRecentLimit = getIntegerProperty(RECENT_LIMIT_PROPERTY, 1000, 0, 10000);
    private final boolean mFileEnabled = Boolean.parseBoolean(System.getProperty(FILE_ENABLED_PROPERTY, "true"));
    private final String mBindAddress = System.getProperty(BIND_PROPERTY, "0.0.0.0");
    private final int mPort = getIntegerProperty(PORT_PROPERTY, DEFAULT_PORT, 1, 65535);
    private final Path mFilePath = getFilePath();
    private BufferedWriter mFileWriter;

    private NowPlayingActivityDebugFeed()
    {
    }

    public static void startIfEnabled()
    {
        if(ENABLED)
        {
            INSTANCE.start();
        }
    }

    public static boolean isEnabled()
    {
        return ENABLED;
    }

    public static Snapshot capture(ChannelActivityRow row)
    {
        if(!ENABLED || row == null)
        {
            return null;
        }

        return Snapshot.from(row);
    }

    public static void logRow(String origin, ChannelActivityTableModel table, ChannelActivityRow row, Snapshot before,
                              Channel parentChannel, DecodeEventType eventType, String note)
    {
        if(ENABLED)
        {
            INSTANCE.recordRow(origin, table, row, before, parentChannel, eventType, note);
        }
    }

    public static void logMiss(String origin, ChannelActivityTableModel table, Channel parentChannel, long frequency,
                               Integer timeslot, DecodeEventType eventType, String note)
    {
        if(ENABLED)
        {
            INSTANCE.recordMiss(origin, table, parentChannel, frequency, timeslot, eventType, note);
        }
    }

    public static void logTableEvent(String origin, ChannelActivityTableModel table, ChannelActivityRow row, int index,
                                     boolean firedToSwing, String note)
    {
        if(ENABLED)
        {
            INSTANCE.recordTableEvent(origin, table, row, index, firedToSwing, note);
        }
    }

    private void start()
    {
        if(mStarted.compareAndSet(false, true))
        {
            Thread writer = new Thread(this::runWriter, "now-playing-debug-feed-writer");
            writer.setDaemon(true);
            writer.start();

            Thread server = new Thread(this::runServer, "now-playing-debug-feed-server");
            server.setDaemon(true);
            server.start();

            LOGGER.info("Now Playing debug feed enabled at http://{}:{}/now-playing-debug file:{}",
                mBindAddress, mPort, mFileEnabled ? mFilePath : "disabled");
        }
    }

    private void recordRow(String origin, ChannelActivityTableModel table, ChannelActivityRow row, Snapshot before,
                           Channel parentChannel, DecodeEventType eventType, String note)
    {
        start();
        Snapshot after = Snapshot.from(row);
        enqueue(beginEvent("row")
            .append(",\"origin\":").append(json(origin))
            .append(tableJson(table))
            .append(channelJson("parentChannel", parentChannel))
            .append(",\"eventType\":").append(json(eventType))
            .append(",\"note\":").append(json(note))
            .append(",\"before\":").append(snapshotJson(before))
            .append(",\"after\":").append(snapshotJson(after))
            .append('}')
            .toString());
    }

    private void recordMiss(String origin, ChannelActivityTableModel table, Channel parentChannel, long frequency,
                            Integer timeslot, DecodeEventType eventType, String note)
    {
        start();
        enqueue(beginEvent("miss")
            .append(",\"origin\":").append(json(origin))
            .append(tableJson(table))
            .append(channelJson("parentChannel", parentChannel))
            .append(",\"eventType\":").append(json(eventType))
            .append(",\"frequency\":").append(frequency)
            .append(",\"timeslot\":").append(timeslot != null ? timeslot : "null")
            .append(",\"note\":").append(json(note))
            .append('}')
            .toString());
    }

    private void recordTableEvent(String origin, ChannelActivityTableModel table, ChannelActivityRow row, int index,
                                  boolean firedToSwing, String note)
    {
        start();
        enqueue(beginEvent("table")
            .append(",\"origin\":").append(json(origin))
            .append(tableJson(table))
            .append(",\"index\":").append(index)
            .append(",\"firedToSwing\":").append(firedToSwing)
            .append(",\"note\":").append(json(note))
            .append(",\"row\":").append(snapshotJson(Snapshot.from(row)))
            .append('}')
            .toString());
    }

    private StringBuilder beginEvent(String type)
    {
        return new StringBuilder(768)
            .append("{\"type\":").append(json(type))
            .append(",\"seq\":").append(mSequence.incrementAndGet())
            .append(",\"time\":").append(json(Instant.now().toString()))
            .append(",\"thread\":").append(json(Thread.currentThread().getName()));
    }

    private void enqueue(String event)
    {
        if(!mEventQueue.offer(event))
        {
            mDroppedEvents.incrementAndGet();
        }
    }

    private void runWriter()
    {
        while(true)
        {
            try
            {
                String event = mEventQueue.take();
                remember(event);
                writeFile(event);

                for(Client client: mClients)
                {
                    if(!client.write(event))
                    {
                        mClients.remove(client);
                        client.close();
                    }
                }
            }
            catch(InterruptedException ie)
            {
                Thread.currentThread().interrupt();
                return;
            }
            catch(Exception e)
            {
                LOGGER.warn("Error while publishing Now Playing debug event", e);
            }
        }
    }

    private void remember(String event)
    {
        if(mRecentLimit <= 0)
        {
            return;
        }

        synchronized(mRecentLock)
        {
            mRecentEvents.addLast(event);

            while(mRecentEvents.size() > mRecentLimit)
            {
                mRecentEvents.removeFirst();
            }
        }
    }

    private void writeFile(String event)
    {
        if(!mFileEnabled)
        {
            return;
        }

        synchronized(mFileLock)
        {
            try
            {
                if(mFileWriter == null)
                {
                    Files.createDirectories(mFilePath.getParent());
                    mFileWriter = Files.newBufferedWriter(mFilePath, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                }

                mFileWriter.write(event);
                mFileWriter.newLine();
                mFileWriter.flush();
            }
            catch(IOException ioe)
            {
                LOGGER.warn("Unable to write Now Playing debug feed file {}", mFilePath, ioe);
            }
        }
    }

    private void runServer()
    {
        try(ServerSocket server = new ServerSocket(mPort, 50, InetAddress.getByName(mBindAddress)))
        {
            while(true)
            {
                Socket socket = server.accept();
                Thread clientThread = new Thread(() -> serve(socket), "now-playing-debug-feed-client");
                clientThread.setDaemon(true);
                clientThread.start();
            }
        }
        catch(IOException ioe)
        {
            LOGGER.warn("Unable to start Now Playing debug feed at {}:{}", mBindAddress, mPort, ioe);
        }
    }

    private void serve(Socket socket)
    {
        try(Socket s = socket)
        {
            s.setTcpNoDelay(true);
            BufferedReader reader = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            String requestLine = reader.readLine();

            if(requestLine == null)
            {
                return;
            }

            String header;

            while((header = reader.readLine()) != null && !header.isEmpty())
            {
                // Drain request headers.
            }

            String path = getPath(requestLine);

            if("/now-playing-debug".equals(path) || "/now-playing-debug/".equals(path))
            {
                stream(s);
            }
            else if("/now-playing-debug/status".equals(path))
            {
                writeResponse(s.getOutputStream(), "200 OK", "application/json; charset=utf-8", statusJson());
            }
            else
            {
                writeResponse(s.getOutputStream(), "404 Not Found", "text/plain; charset=utf-8",
                    "Use /now-playing-debug for NDJSON stream or /now-playing-debug/status for status\n");
            }
        }
        catch(IOException ioe)
        {
            LOGGER.debug("Now Playing debug feed client disconnected", ioe);
        }
    }

    private void stream(Socket socket) throws IOException
    {
        OutputStream outputStream = socket.getOutputStream();
        writeHeaders(outputStream, "200 OK", "application/x-ndjson; charset=utf-8");
        Client client = new Client(outputStream);
        mClients.add(client);
        client.write(statusJson());

        synchronized(mRecentLock)
        {
            for(String event: mRecentEvents)
            {
                if(!client.write(event))
                {
                    break;
                }
            }
        }

        try
        {
            while(!client.isClosed())
            {
                Thread.sleep(1000);
            }
        }
        catch(InterruptedException ie)
        {
            Thread.currentThread().interrupt();
        }
        finally
        {
            mClients.remove(client);
            client.close();
        }
    }

    private String statusJson()
    {
        return beginEvent("status")
            .append(",\"enabled\":true")
            .append(",\"bind\":").append(json(mBindAddress))
            .append(",\"port\":").append(mPort)
            .append(",\"path\":").append(json("/now-playing-debug"))
            .append(",\"clients\":").append(mClients.size())
            .append(",\"queuedEvents\":").append(mEventQueue.size())
            .append(",\"droppedEvents\":").append(mDroppedEvents.get())
            .append(",\"recentLimit\":").append(mRecentLimit)
            .append(",\"file\":").append(mFileEnabled ? json(mFilePath) : "null")
            .append('}')
            .toString();
    }

    private static String tableJson(ChannelActivityTableModel table)
    {
        if(table == null)
        {
            return ",\"table\":null,\"tableVisible\":false,\"tableControlActive\":false,\"tableCloseable\":false";
        }

        return new StringBuilder(160)
            .append(",\"table\":").append(json(table.getTitle()))
            .append(",\"tableVisible\":").append(table.isActivityViewVisible())
            .append(",\"tableControlActive\":").append(table.isControlActive())
            .append(",\"tableCloseable\":").append(table.isCloseable())
            .append(channelJson("ownerChannel", table.getOwnerChannel()))
            .toString();
    }

    private static String channelJson(String name, Channel channel)
    {
        if(channel == null)
        {
            return ",\"" + name + "\":null";
        }

        return new StringBuilder(120)
            .append(",\"").append(name).append("\":{")
            .append("\"id\":").append(channel.getChannelID())
            .append(",\"name\":").append(json(channel.getName()))
            .append(",\"traffic\":").append(channel.isTrafficChannel())
            .append('}')
            .toString();
    }

    private static String snapshotJson(Snapshot snapshot)
    {
        if(snapshot == null)
        {
            return "null";
        }

        return new StringBuilder(320)
            .append('{')
            .append("\"key\":").append(json(snapshot.key()))
            .append(",\"role\":").append(json(snapshot.role()))
            .append(",\"origin\":").append(json(snapshot.origin()))
            .append(",\"controlRole\":").append(json(snapshot.controlRole()))
            .append(",\"state\":").append(json(snapshot.state()))
            .append(",\"frequency\":").append(snapshot.frequency())
            .append(",\"timeslot\":").append(snapshot.timeslot() != null ? snapshot.timeslot() : "null")
            .append(",\"lcn\":").append(json(snapshot.lcn()))
            .append(",\"source\":").append(json(snapshot.source()))
            .append(",\"sourceAliases\":").append(json(snapshot.sourceAliases()))
            .append(",\"target\":").append(json(snapshot.target()))
            .append(",\"targetAliases\":").append(json(snapshot.targetAliases()))
            .append(",\"decoder\":").append(json(snapshot.decoder()))
            .append(",\"encryption\":").append(json(snapshot.encryption()))
            .append(channelJson("channel", snapshot.channel()))
            .append('}')
            .toString();
    }

    private static String getPath(String requestLine)
    {
        String[] parts = requestLine.split(" ");

        if(parts.length < 2)
        {
            return "/";
        }

        String path = parts[1];
        int query = path.indexOf('?');

        if(query >= 0)
        {
            path = path.substring(0, query);
        }

        return URLDecoder.decode(path, StandardCharsets.UTF_8);
    }

    private static void writeResponse(OutputStream outputStream, String status, String contentType, String body)
        throws IOException
    {
        writeHeaders(outputStream, status, contentType);
        outputStream.write(body.getBytes(StandardCharsets.UTF_8));
        outputStream.flush();
    }

    private static void writeHeaders(OutputStream outputStream, String status, String contentType) throws IOException
    {
        String headers = "HTTP/1.1 " + status + "\r\n" +
            "Content-Type: " + contentType + "\r\n" +
            "Cache-Control: no-cache\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Connection: close\r\n\r\n";
        outputStream.write(headers.getBytes(StandardCharsets.UTF_8));
        outputStream.flush();
    }

    private static String json(Object value)
    {
        if(value == null)
        {
            return "null";
        }

        return "\"" + escape(String.valueOf(value)) + "\"";
    }

    private static String escape(String value)
    {
        StringBuilder escaped = new StringBuilder(value.length() + 16);

        for(int x = 0; x < value.length(); x++)
        {
            char c = value.charAt(x);

            switch(c)
            {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default ->
                {
                    if(c < 0x20)
                    {
                        escaped.append(String.format("\\u%04x", (int)c));
                    }
                    else
                    {
                        escaped.append(c);
                    }
                }
            }
        }

        return escaped.toString();
    }

    private static int getIntegerProperty(String property, int fallback, int minimum, int maximum)
    {
        try
        {
            int value = Integer.parseInt(System.getProperty(property, String.valueOf(fallback)));
            return Math.max(minimum, Math.min(maximum, value));
        }
        catch(NumberFormatException nfe)
        {
            return fallback;
        }
    }

    private static Path getFilePath()
    {
        String configured = System.getProperty(FILE_PROPERTY);

        if(configured != null && !configured.isBlank())
        {
            return Paths.get(configured);
        }

        return Paths.get(System.getProperty("user.home"), "SDRTrunk", "logs", "now-playing-ui-debug.ndjson");
    }

    private static String aliases(List<Alias> aliases)
    {
        if(aliases == null || aliases.isEmpty())
        {
            return null;
        }

        List<String> names = new ArrayList<>(aliases.size());

        for(Alias alias: aliases)
        {
            if(alias != null)
            {
                names.add(alias.getName());
            }
        }

        return String.join("|", names);
    }

    public record Snapshot(String key, String role, String origin, String controlRole, String state, long frequency,
                           Integer timeslot, String lcn, String source, String sourceAliases, String target,
                           String targetAliases, String decoder, String encryption, Channel channel)
    {
        static Snapshot from(ChannelActivityRow row)
        {
            if(row == null)
            {
                return null;
            }

            Identifier<?> source = row.getSource();
            Identifier<?> target = row.getTarget();
            State state = row.getState();

            return new Snapshot(row.getKey(),
                row.getRole() != null ? row.getRole().name() : null,
                row.getOrigin() != null ? row.getOrigin().name() : null,
                row.getControlRole() != null ? row.getControlRole().name() : null,
                state != null ? state.name() : null,
                row.getFrequency(),
                row.getTimeslot(),
                row.getLcn(),
                source != null ? source.toString() : null,
                aliases(row.getSourceAliases()),
                target != null ? target.toString() : null,
                aliases(row.getTargetAliases()),
                row.getDecoder(),
                row.getEncryptionDetails(),
                row.getChannel());
        }
    }

    private static class Client
    {
        private final OutputStream mOutputStream;
        private final AtomicBoolean mClosed = new AtomicBoolean();

        Client(OutputStream outputStream)
        {
            mOutputStream = outputStream;
        }

        boolean write(String event)
        {
            if(isClosed())
            {
                return false;
            }

            try
            {
                synchronized(mOutputStream)
                {
                    mOutputStream.write(event.getBytes(StandardCharsets.UTF_8));
                    mOutputStream.write('\n');
                    mOutputStream.flush();
                }

                return true;
            }
            catch(IOException ioe)
            {
                mClosed.set(true);
                return false;
            }
        }

        boolean isClosed()
        {
            return mClosed.get();
        }

        void close()
        {
            mClosed.set(true);
        }
    }
}
