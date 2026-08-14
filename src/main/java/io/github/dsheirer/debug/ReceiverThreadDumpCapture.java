/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.debug;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded JVM thread projection shared by automatic incident captures. */
final class ReceiverThreadDumpCapture
{
    static final int MAXIMUM_THREAD_COUNT = 256;
    static final int MAXIMUM_STACK_DEPTH = 32;
    static final int MAXIMUM_BYTES = 512 * 1_024;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private ReceiverThreadDumpCapture()
    {
    }

    static byte[] capture()
    {
        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        ThreadInfo[] infos;

        try
        {
            //Automatic incident dumps need thread state, locks being waited on, and bounded stacks.  Collecting every
            //thread's complete locked-monitor/synchronizer inventory is substantially more intrusive and those values
            //are not serialized, so deliberately leave both global probes disabled.
            infos = bean.dumpAllThreads(false, false, MAXIMUM_STACK_DEPTH);
        }
        catch(RuntimeException e)
        {
            return json(Map.of("error", "Thread dump is unavailable", "type", e.getClass().getSimpleName()));
        }

        int count = Math.min(MAXIMUM_THREAD_COUNT, infos.length);
        List<Map<String,Object>> threads = new ArrayList<>(count);

        for(int x = 0; x < count; x++)
        {
            ThreadInfo info = infos[x];

            if(info == null)
            {
                continue;
            }

            Map<String,Object> thread = new LinkedHashMap<>();
            thread.put("id", info.getThreadId());
            thread.put("name", info.getThreadName());
            thread.put("state", info.getThreadState().name().toLowerCase());
            thread.put("lock_name", info.getLockName());
            thread.put("lock_owner_id", info.getLockOwnerId() >= 0 ? info.getLockOwnerId() : null);
            thread.put("lock_owner_name", info.getLockOwnerName());
            thread.put("suspended", info.isSuspended());
            thread.put("in_native", info.isInNative());
            List<String> frames = new ArrayList<>(info.getStackTrace().length);

            for(StackTraceElement frame: info.getStackTrace())
            {
                frames.add(frame.toString());
            }

            thread.put("stack", frames);
            threads.add(thread);
        }

        Map<String,Object> result = new LinkedHashMap<>();
        result.put("intrusive", true);
        result.put("thread_count", infos.length);
        result.put("thread_limit", MAXIMUM_THREAD_COUNT);
        result.put("threads_truncated", infos.length > count);
        result.put("stack_depth_limit", MAXIMUM_STACK_DEPTH);
        result.put("byte_limit", MAXIMUM_BYTES);
        result.put("threads", threads);
        byte[] serialized = json(result);

        if(serialized.length <= MAXIMUM_BYTES)
        {
            return serialized;
        }

        int low = 0;
        int high = threads.size();
        byte[] best = json(Map.of("intrusive", true, "threads", List.of(), "response_truncated", true,
            "byte_limit", MAXIMUM_BYTES));

        while(low <= high)
        {
            int middle = (low + high) >>> 1;
            result.put("threads", threads.subList(0, middle));
            result.put("included_thread_count", middle);
            result.put("response_truncated", true);
            byte[] candidate = json(result);

            if(candidate.length <= MAXIMUM_BYTES)
            {
                best = candidate;
                low = middle + 1;
            }
            else
            {
                high = middle - 1;
            }
        }

        return best.length <= MAXIMUM_BYTES ? best : json(Map.of("error", "Thread dump exceeded byte limit"));
    }

    /** Retains the largest valid prefix of a thread dump that fits the incident report's smaller storage budget. */
    static byte[] boundForStorage(byte[] captured, int byteLimit)
    {
        if(captured == null)
        {
            return json(Map.of("error", "Thread dump returned no data"));
        }

        if(captured.length <= byteLimit)
        {
            return captured;
        }

        try
        {
            JsonNode parsed = OBJECT_MAPPER.readTree(captured);

            if(!(parsed instanceof ObjectNode root) || !(root.path("threads") instanceof ArrayNode original))
            {
                return json(Map.of("error", "Thread dump exceeded incident storage budget", "captured_bytes",
                    captured.length, "storage_byte_limit", byteLimit));
            }

            int low = 0;
            int high = original.size();
            byte[] best = json(Map.of("intrusive", true, "threads", List.of(), "storage_truncated", true,
                "captured_bytes", captured.length, "storage_byte_limit", byteLimit));

            while(low <= high)
            {
                int middle = (low + high) >>> 1;
                ObjectNode candidateRoot = root.deepCopy();
                ArrayNode prefix = OBJECT_MAPPER.createArrayNode();

                for(int x = 0; x < middle; x++)
                {
                    prefix.add(original.get(x));
                }

                candidateRoot.set("threads", prefix);
                candidateRoot.put("included_thread_count", middle);
                candidateRoot.put("storage_truncated", true);
                candidateRoot.put("captured_bytes", captured.length);
                candidateRoot.put("storage_byte_limit", byteLimit);
                byte[] candidate = json(candidateRoot);

                if(candidate.length <= byteLimit)
                {
                    best = candidate;
                    low = middle + 1;
                }
                else
                {
                    high = middle - 1;
                }
            }

            return best;
        }
        catch(IOException | RuntimeException e)
        {
            return json(Map.of("error", "Thread dump exceeded storage budget and could not be compacted", "type",
                e.getClass().getSimpleName(), "storage_byte_limit", byteLimit));
        }
    }

    private static byte[] json(Object value)
    {
        try
        {
            return OBJECT_MAPPER.writeValueAsBytes(value);
        }
        catch(IOException e)
        {
            return "{\"error\":\"Thread dump serialization failed\"}".getBytes(StandardCharsets.UTF_8);
        }
    }
}
