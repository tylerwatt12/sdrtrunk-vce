/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
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

package io.github.dsheirer.web.settings;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.dsheirer.portable.PortableApplicationPaths;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Receiver resource limits that an administrator can tune at runtime.
 *
 * Heap cannot be changed inside a running JVM, so the requested heap is persisted to
 * {@code data/conf/resources.properties} and applied by the launcher on the next start; the
 * snapshot reports whether a restart is still required. Process priority (nice) is applied
 * immediately, subject to the operating system's rules for lowering priority without privilege.
 *
 * CPU quota is a cgroup limit, not something a process may set on itself, so it is delegated to
 * an optional privileged helper script when one is installed and otherwise reported as not applied.
 */
public final class ResourceLimitsService
{
    public static final String CONFIGURATION_FILENAME = "resources.properties";
    public static final String HELPER_PATH = "/usr/local/bin/vce-resources";
    public static final int MINIMUM_HEAP_MB = 256;
    public static final int MAXIMUM_HEAP_MB = 65536;
    public static final int MINIMUM_NICE = -20;
    public static final int MAXIMUM_NICE = 19;
    public static final int MINIMUM_CPU_QUOTA = 0;
    public static final int MAXIMUM_CPU_QUOTA = 1600;
    private static final Logger mLog = LoggerFactory.getLogger(ResourceLimitsService.class);
    private final Path mConfigurationFile;

    public ResourceLimitsService()
    {
        this(PortableApplicationPaths.getDataRoot().resolve("conf").resolve(CONFIGURATION_FILENAME));
    }

    ResourceLimitsService(Path configurationFile)
    {
        mConfigurationFile = Objects.requireNonNull(configurationFile);
    }

    /**
     * Current runtime state plus the saved limits.
     */
    public Map<String,Object> snapshot()
    {
        Properties saved = read();
        Map<String,Object> value = new LinkedHashMap<>();
        Runtime runtime = Runtime.getRuntime();

        long maxHeap = runtime.maxMemory() / (1024 * 1024);
        long totalHeap = runtime.totalMemory() / (1024 * 1024);
        long usedHeap = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);

        Map<String,Object> live = new LinkedHashMap<>();
        live.put("heapMaxMb", maxHeap);
        live.put("heapCommittedMb", totalHeap);
        live.put("heapUsedMb", usedHeap);
        live.put("availableProcessors", runtime.availableProcessors());
        live.put("processCpuLoadPercent", processCpuLoadPercent());
        live.put("nice", currentNice());
        live.put("cpuQuotaSupported", Files.isExecutable(Path.of(HELPER_PATH)));
        value.put("live", live);

        Integer requestedHeap = parseInt(saved, "maxHeapMb");
        Map<String,Object> limits = new LinkedHashMap<>();
        // Cast keeps the ternary an Integer; Integer/long would promote to Long.
        limits.put("maxHeapMb", requestedHeap != null ? (int) requestedHeap : (int) maxHeap);
        limits.put("nice", parseIntOr(saved, "nice", live.get("nice")));
        limits.put("cpuQuotaPercent", parseIntOr(saved, "cpuQuotaPercent", 0));
        value.put("limits", limits);

        value.put("restartRequired", requestedHeap != null && requestedHeap != maxHeap);
        value.put("restartReason", requestedHeap != null && requestedHeap != maxHeap ?
            "maxHeapMb is applied by the launcher at start-up" : null);
        value.put("configurationFile", mConfigurationFile.toString());
        return value;
    }

    /**
     * Persist and apply the supplied limits. Fields absent from the request are left unchanged.
     */
    public Map<String,Object> update(JsonNode request) throws IOException
    {
        Properties properties = read();
        List<String> notes = new ArrayList<>();

        Integer maxHeapMb = optionalInt(request, "maxHeapMb");
        if(maxHeapMb != null)
        {
            requireRange("maxHeapMb", maxHeapMb, MINIMUM_HEAP_MB, MAXIMUM_HEAP_MB);
            properties.setProperty("maxHeapMb", Integer.toString(maxHeapMb));
        }

        Integer cpuQuotaPercent = optionalInt(request, "cpuQuotaPercent");
        if(cpuQuotaPercent != null)
        {
            requireRange("cpuQuotaPercent", cpuQuotaPercent, MINIMUM_CPU_QUOTA, MAXIMUM_CPU_QUOTA);
            properties.setProperty("cpuQuotaPercent", Integer.toString(cpuQuotaPercent));
            notes.add(applyCpuQuota(cpuQuotaPercent));
        }

        Integer nice = optionalInt(request, "nice");
        if(nice != null)
        {
            requireRange("nice", nice, MINIMUM_NICE, MAXIMUM_NICE);
            properties.setProperty("nice", Integer.toString(nice));
            notes.add(applyNice(nice));
        }

        write(properties);
        Map<String,Object> value = snapshot();
        value.put("notes", notes);
        return value;
    }

    private String applyNice(int nice)
    {
        int current = currentNice();

        if(nice == current)
        {
            return "priority already " + nice;
        }

        if(nice < current)
        {
            return "priority left at " + current + ": raising priority needs privilege";
        }

        try
        {
            Process process = new ProcessBuilder("renice", "-n", Integer.toString(nice), "-p",
                Long.toString(ProcessHandle.current().pid())).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes());
            process.waitFor();
            int updated = currentNice();
            return updated == nice ? "priority set to " + nice : "priority change failed: " + output.trim();
        }
        catch(IOException | InterruptedException e)
        {
            if(e instanceof InterruptedException)
            {
                Thread.currentThread().interrupt();
            }
            return "priority change failed: " + e.getMessage();
        }
    }

    private String applyCpuQuota(int percent)
    {
        Path helper = Path.of(HELPER_PATH);

        if(!Files.isExecutable(helper))
        {
            return "cpuQuotaPercent saved; applied by " + HELPER_PATH + " only when that helper is installed";
        }

        try
        {
            Process process = new ProcessBuilder(helper.toString(), "cpu-quota", Integer.toString(percent))
                .redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes());
            int status = process.waitFor();
            return status == 0 ? "cpuQuotaPercent applied" : "cpuQuotaPercent helper failed: " + output.trim();
        }
        catch(IOException | InterruptedException e)
        {
            if(e instanceof InterruptedException)
            {
                Thread.currentThread().interrupt();
            }
            return "cpuQuotaPercent helper failed: " + e.getMessage();
        }
    }

    /**
     * Available processor count and a coarse CPU duty cycle for this process.
     */
    private Double processCpuLoadPercent()
    {
        try
        {
            java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();

            if(bean instanceof com.sun.management.OperatingSystemMXBean extended)
            {
                double load = extended.getProcessCpuLoad();

                if(load >= 0)
                {
                    return Math.round(load * 1000.0) / 10.0;
                }
            }
        }
        catch(Throwable t)
        {
            mLog.debug("process CPU load unavailable: {}", t.getMessage());
        }

        return null;
    }

    /**
     * Nice value from /proc/self/stat, or null when the platform does not expose it.
     */
    private Integer currentNice()
    {
        try
        {
            String stat = Files.readString(Path.of("/proc/self/stat"));
            int close = stat.lastIndexOf(')');

            if(close < 0)
            {
                return null;
            }

            // Fields after the command name start at field 3 (state); nice is field 19.
            String[] fields = stat.substring(close + 2).trim().split("\\s+");
            return Integer.parseInt(fields[16]);
        }
        catch(IOException | NumberFormatException | ArrayIndexOutOfBoundsException e)
        {
            return null;
        }
    }

    private Properties read()
    {
        Properties properties = new Properties();

        if(Files.exists(mConfigurationFile))
        {
            try(InputStream input = Files.newInputStream(mConfigurationFile))
            {
                properties.load(input);
            }
            catch(IOException e)
            {
                mLog.warn("Unable to read resource limits from {}: {}", mConfigurationFile, e.getMessage());
            }
        }

        return properties;
    }

    private void write(Properties properties) throws IOException
    {
        Files.createDirectories(mConfigurationFile.getParent());

        try(OutputStream output = Files.newOutputStream(mConfigurationFile))
        {
            properties.store(output, "SDRTrunk VCE resource limits");
        }
    }

    private static void requireRange(String field, int value, int minimum, int maximum)
    {
        if(value < minimum || value > maximum)
        {
            throw new IllegalArgumentException(field + " must be between " + minimum + " and " + maximum);
        }
    }

    private static Integer optionalInt(JsonNode request, String field)
    {
        JsonNode node = request.get(field);

        if(node == null || node.isNull())
        {
            return null;
        }

        if(!node.isInt() && !(node.isTextual() && node.asText().matches("-?\\d+")))
        {
            throw new IllegalArgumentException(field + " must be an integer");
        }

        return node.asInt();
    }

    private static Integer parseInt(Properties properties, String key)
    {
        String value = properties.getProperty(key);
        return value != null && value.matches("-?\\d+") ? Integer.parseInt(value) : null;
    }

    private static Object parseIntOr(Properties properties, String key, Object fallback)
    {
        Integer value = parseInt(properties, key);
        return value != null ? value : fallback;
    }

    /**
     * Heap requested for the next start, or null when the launcher default applies.
     */
    public Integer requestedHeapMb()
    {
        return parseInt(read(), "maxHeapMb");
    }
}
