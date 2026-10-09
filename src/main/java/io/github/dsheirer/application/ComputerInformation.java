/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.application;

import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * Allowlisted application and computer facts shared by administrator status and support reports. Static discovery
 * is lazy and cached; callers invoke snapshot only on their observer workers, never on receiver callbacks. No host,
 * account, serial, network identity, JVM arguments, or installation paths are collected.
 */
public final class ComputerInformation
{
    private static final String UNAVAILABLE = "Unavailable";
    private static final int MAXIMUM_TEXT_LENGTH = 256;
    private static final int MAXIMUM_FILE_BYTES = 64 * 1024;
    private static final int MAXIMUM_COMMAND_BYTES = 4096;
    private static final long COMMAND_TIMEOUT_MILLISECONDS = 2000;
    private static final ComputerInformation CURRENT = new ComputerInformation(new SystemProbe(),
        () -> ManagementFactory.getRuntimeMXBean().getUptime());
    private final Probe mProbe;
    private final LongSupplier mUptime;
    private volatile Map<String,Object> mStaticInformation;

    ComputerInformation(Probe probe, LongSupplier uptime)
    {
        mProbe = probe;
        mUptime = uptime;
    }

    public static ComputerInformation current()
    {
        return CURRENT;
    }

    public Map<String,Object> snapshot()
    {
        Map<String,Object> information = mStaticInformation;
        if(information == null)
        {
            synchronized(this)
            {
                information = mStaticInformation;
                if(information == null)
                {
                    information = discover(mProbe);
                    mStaticInformation = information;
                }
            }
        }
        LinkedHashMap<String,Object> snapshot = new LinkedHashMap<>(information);
        snapshot.put("application_uptime_ms", Math.max(0L, mUptime.getAsLong()));
        return Map.copyOf(snapshot);
    }

    private static Map<String,Object> discover(Probe probe)
    {
        String osName = safeText(probe.property("os.name"));
        Hardware hardware = hardware(osName, probe);
        LinkedHashMap<String,Object> information = new LinkedHashMap<>();
        information.put("application", safeText(ApplicationInfo.getDisplayName()));
        information.put("version", safeText(ApplicationInfo.getVersion()));
        information.put("update_track", safeText(ApplicationInfo.getUpdateTrack()));
        information.put("update_build", safeText(ApplicationInfo.getUpdateBuild()));
        information.put("build_timestamp", safeText(ApplicationInfo.getBuildTimestamp()));
        information.put("operating_system", osName);
        information.put("operating_system_version", safeText(probe.property("os.version")));
        information.put("architecture", safeText(probe.property("os.arch")));
        information.put("cpu_model", safeText(hardware.cpuModel()));
        information.put("processors_available", positive(probe.processors()));
        information.put("installed_memory_bytes", positive(hardware.installedMemoryBytes()));
        information.put("system_memory_bytes", positive(hardware.systemMemoryBytes()));
        information.put("jvm_memory_limit_bytes", positive(probe.heapLimitBytes()));
        information.put("java_version", safeText(probe.property("java.version")));
        information.put("java_vendor", safeText(probe.property("java.vendor")));
        return Map.copyOf(information);
    }

    private static Hardware hardware(String osName, Probe probe)
    {
        String os = osName.toLowerCase(Locale.ROOT);
        if(os.contains("mac") || os.contains("darwin"))
        {
            // These two keys return only a processor model and installed memory; sysctl -a includes private facts.
            return parseHardwareLines(probe.command(List.of("/usr/sbin/sysctl", "-n",
                "machdep.cpu.brand_string", "hw.memsize")));
        }
        if(os.contains("windows"))
        {
            // Select specific safe fields rather than exporting CIM objects or computer-system identity.
            return parseHardwareLines(probe.command(List.of("powershell.exe", "-NoProfile", "-NonInteractive",
                "-Command", "[Console]::OutputEncoding=[Text.UTF8Encoding]::new(); " +
                    "(Get-CimInstance Win32_Processor | Select-Object -First 1).Name; " +
                    "[string]::Format([Globalization.CultureInfo]::InvariantCulture, '{0:0}', " +
                    "(Get-CimInstance Win32_PhysicalMemory | Measure-Object -Property Capacity -Sum).Sum)")));
        }
        if(os.contains("linux"))
        {
            // MemTotal excludes firmware/kernel reservations; do not present it as installed DIMM capacity.
            return new Hardware(cpuFromLinux(probe.file(Path.of("/proc/cpuinfo"))), 0,
                memoryFromLinux(probe.file(Path.of("/proc/meminfo"))));
        }
        return new Hardware(null, 0, 0);
    }

    static Hardware parseHardwareLines(String output)
    {
        if(output == null) return new Hardware(null, 0, 0);
        String[] lines = output.strip().split("\\R");
        return lines.length == 2 ? new Hardware(lines[0], parsePositiveLong(lines[1]), 0) :
            new Hardware(null, 0, 0);
    }

    static String cpuFromLinux(String output)
    {
        if(output == null) return null;
        for(String line: output.split("\\R"))
        {
            int separator = line.indexOf(':');
            if(separator > 0 && "model name".equalsIgnoreCase(line.substring(0, separator).strip()))
            {
                return line.substring(separator + 1).strip();
            }
        }
        return null;
    }

    static long memoryFromLinux(String output)
    {
        if(output == null) return 0;
        for(String line: output.split("\\R"))
        {
            if(line.startsWith("MemTotal:"))
            {
                String[] parts = line.substring("MemTotal:".length()).strip().split("\\s+");
                if(parts.length == 2 && "kB".equals(parts[1]))
                {
                    try
                    {
                        return Math.multiplyExact(parsePositiveLong(parts[0]), 1024L);
                    }
                    catch(ArithmeticException exception)
                    {
                        return 0;
                    }
                }
            }
        }
        return 0;
    }

    static String safeText(String value)
    {
        if(value == null || value.isBlank()) return UNAVAILABLE;
        String text = value.strip();
        if(text.length() > MAXIMUM_TEXT_LENGTH || text.contains("/") || text.contains("\\") ||
            text.contains("@") || text.contains("://") || text.chars().anyMatch(Character::isISOControl))
        {
            return UNAVAILABLE;
        }
        return text;
    }

    private static long parsePositiveLong(String value)
    {
        try
        {
            return Math.max(0L, Long.parseLong(value.strip()));
        }
        catch(NumberFormatException exception)
        {
            return 0;
        }
    }

    private static Object positive(long value)
    {
        return value > 0 ? value : UNAVAILABLE;
    }

    record Hardware(String cpuModel, long installedMemoryBytes, long systemMemoryBytes) {}

    interface Probe
    {
        String property(String name);
        String command(List<String> arguments);
        String file(Path path);
        int processors();
        long heapLimitBytes();
    }

    private static final class SystemProbe implements Probe
    {
        @Override public String property(String name)
        {
            try { return System.getProperty(name); }
            catch(SecurityException exception) { return null; }
        }

        @Override public int processors() { return Runtime.getRuntime().availableProcessors(); }
        @Override public long heapLimitBytes() { return Runtime.getRuntime().maxMemory(); }

        @Override public String file(Path path)
        {
            try(InputStream stream = Files.newInputStream(path))
            {
                byte[] bytes = stream.readNBytes(MAXIMUM_FILE_BYTES);
                return new String(bytes, StandardCharsets.UTF_8);
            }
            catch(Exception exception) { return null; }
        }

        @Override public String command(List<String> arguments)
        {
            Process process = null;
            try
            {
                process = new ProcessBuilder(arguments).redirectError(ProcessBuilder.Redirect.DISCARD).start();
                Process running = process;
                AtomicReference<byte[]> output = new AtomicReference<>();
                Thread reader = new Thread(() -> {
                    try(InputStream stream = running.getInputStream())
                    {
                        output.set(stream.readNBytes(MAXIMUM_COMMAND_BYTES + 1));
                    }
                    catch(Exception exception) { /* Unavailable discovery leaves receiver work unaffected. */ }
                }, "computer information output");
                reader.setDaemon(true);
                reader.start();
                if(!process.waitFor(COMMAND_TIMEOUT_MILLISECONDS, TimeUnit.MILLISECONDS)) return null;
                reader.join(250);
                byte[] bytes = output.get();
                return process.exitValue() == 0 && bytes != null && bytes.length <= MAXIMUM_COMMAND_BYTES ?
                    new String(bytes, StandardCharsets.UTF_8) : null;
            }
            catch(InterruptedException exception)
            {
                Thread.currentThread().interrupt();
                return null;
            }
            catch(Exception exception) { return null; }
            finally
            {
                if(process != null && process.isAlive()) process.destroyForcibly();
            }
        }
    }
}
