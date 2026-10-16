/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ComputerInformationTest
{
    private static final long GIB = 1024L * 1024L * 1024L;

    @Test
    void cachesStaticMacDiscoveryButRefreshesApplicationUptime()
    {
        FakeProbe probe = new FakeProbe("Mac OS X", "Apple M3 Pro\n34359738368\n", Map.of());
        AtomicLong uptime = new AtomicLong(1000L);
        ComputerInformation information = new ComputerInformation(probe, uptime::get);
        assertTrue(probe.commands.isEmpty(), "Construction must not start platform discovery");
        Map<String,Object> first = information.snapshot();
        assertEquals("Apple M3 Pro", first.get("cpu_model"));
        assertEquals(32 * GIB, first.get("installed_memory_bytes"));
        assertEquals(2 * GIB, first.get("jvm_memory_limit_bytes"));
        assertEquals(8L, first.get("processors_available"));
        assertEquals(1000L, first.get("application_uptime_ms"));
        assertEquals(List.of("/usr/sbin/sysctl", "-n", "machdep.cpu.brand_string", "hw.memsize"),
            probe.commands.getFirst());
        assertEquals(List.of(2000L), probe.timeouts);
        uptime.set(2000L);
        assertEquals(2000L, information.snapshot().get("application_uptime_ms"));
        assertEquals(1, probe.commands.size(), "HTTP polling and support reports reuse cached static metadata");
        assertEquals(1000L, first.get("application_uptime_ms"), "Published snapshots remain immutable");
    }

    @Test
    void windowsQueriesOnlyProcessorNameAndInstalledMemoryCapacity()
    {
        FakeProbe probe = new FakeProbe("Windows 11", "CPU=Intel(R) Core(TM) i5-6500T CPU @ 2.50GHz\r\n" +
            "RAM=17179869184\r\n", Map.of());
        Map<String,Object> snapshot = new ComputerInformation(probe, () -> 0L).snapshot();
        assertEquals("Intel(R) Core(TM) i5-6500T CPU @ 2.50GHz", snapshot.get("cpu_model"));
        assertEquals(16 * GIB, snapshot.get("installed_memory_bytes"));
        assertEquals(List.of(10000L), probe.timeouts, "Cold PowerShell/CIM startup needs more than two seconds");
        String command = String.join(" ", probe.commands.getFirst());
        assertTrue(command.contains("Win32_Processor"));
        assertTrue(command.contains("Win32_PhysicalMemory"));
        assertFalse(command.contains("SerialNumber"));
        assertFalse(command.contains("Win32_ComputerSystem"));
        assertFalse(command.contains("Format-List"));
    }

    @Test
    void windowsPreservesEachAvailableHardwareFact()
    {
        for(String output: List.of("RAM=17179869184\r\n", "CPU=\r\nRAM=17179869184\r\n"))
        {
            FakeProbe probe = new FakeProbe("Windows 11", output, Map.of());
            Map<String,Object> snapshot = new ComputerInformation(probe, () -> 0L).snapshot();
            assertEquals("Unavailable", snapshot.get("cpu_model"));
            assertEquals(16 * GIB, snapshot.get("installed_memory_bytes"));
        }
        for(String output: List.of("CPU=AMD Ryzen 7 7840U\r\n", "CPU=AMD Ryzen 7 7840U\r\nRAM=invalid\r\n"))
        {
            FakeProbe probe = new FakeProbe("Windows 11", output, Map.of());
            Map<String,Object> snapshot = new ComputerInformation(probe, () -> 0L).snapshot();
            assertEquals("AMD Ryzen 7 7840U", snapshot.get("cpu_model"));
            assertEquals("Unavailable", snapshot.get("installed_memory_bytes"));
        }
        FakeProbe failed = new FakeProbe("Windows 11", null, Map.of());
        Map<String,Object> snapshot = new ComputerInformation(failed, () -> 0L).snapshot();
        assertEquals("Unavailable", snapshot.get("cpu_model"));
        assertEquals("Unavailable", snapshot.get("installed_memory_bytes"));
        assertEquals(2 * GIB, snapshot.get("jvm_memory_limit_bytes"));
    }

    @Test
    void commandCollectsHardwareAfterTwoSecondsAndHonorsItsDeadline() throws Exception
    {
        Path java = Path.of(System.getProperty("java.home"), "bin",
            System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        List<String> command = List.of(java.toString(), "-cp",
            Path.of(ComputerInformationTest.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(),
            DelayedHardwareOutput.class.getName());
        ComputerInformation.SystemProbe probe = new ComputerInformation.SystemProbe();
        assertEquals("Delayed CPU", ComputerInformation.parseWindowsHardware(probe.command(command, 10000)).cpuModel());
        assertNull(probe.command(command, 100), "Timed-out discovery must return without waiting for the child");
    }

    public static final class DelayedHardwareOutput
    {
        public static void main(String[] arguments) throws InterruptedException
        {
            Thread.sleep(2200);
            System.out.println("CPU=Delayed CPU");
            System.out.println("RAM=17179869184");
        }
    }

    @Test
    void linuxSeparatesOsUsableMemoryFromUnknownInstalledCapacity()
    {
        FakeProbe probe = new FakeProbe("Linux", null, Map.of(
            Path.of("/proc/cpuinfo"), "processor : 0\nmodel name : AMD Ryzen 7 7840U\nSerial : private-serial\n",
            Path.of("/proc/meminfo"), "MemTotal:       16000000 kB\nMemFree: 4000000 kB\n"));
        Map<String,Object> snapshot = new ComputerInformation(probe, () -> 3000L).snapshot();
        assertEquals("AMD Ryzen 7 7840U", snapshot.get("cpu_model"));
        assertEquals("Unavailable", snapshot.get("installed_memory_bytes"));
        assertEquals(16_000_000L * 1024, snapshot.get("system_memory_bytes"));
        assertEquals(2 * GIB, snapshot.get("jvm_memory_limit_bytes"));
        assertTrue(probe.commands.isEmpty());
        assertFalse(snapshot.toString().contains("private-serial"));
    }

    @Test
    void unavailableDiscoveryNeverSubstitutesHeapForInstalledRam()
    {
        FakeProbe probe = new FakeProbe("Mac OS X", null, Map.of());
        Map<String,Object> snapshot = new ComputerInformation(probe, () -> -10L).snapshot();
        assertEquals("Unavailable", snapshot.get("cpu_model"));
        assertEquals("Unavailable", snapshot.get("installed_memory_bytes"));
        assertEquals(2 * GIB, snapshot.get("jvm_memory_limit_bytes"));
        assertEquals(0L, snapshot.get("application_uptime_ms"));
        assertEquals(Set.of("application", "version", "update_track", "update_build", "build_timestamp",
            "operating_system", "operating_system_version", "architecture", "cpu_model", "processors_available",
            "installed_memory_bytes", "system_memory_bytes", "jvm_memory_limit_bytes", "java_version", "java_vendor",
            "application_uptime_ms"), snapshot.keySet());
    }

    @Test
    void malformedOrPrivateValuesHaveSafeUnavailableFallbacks()
    {
        for(String value: List.of("/Users/person/private", "C:\\Users\\person", "https://private.example",
            "person@example.com", "line\nprivate", "a".repeat(257), "person@example.com @ 2.40GHz"))
        {
            assertEquals("Unavailable", ComputerInformation.safeText(value));
            assertEquals("Unavailable", ComputerInformation.safeCpuModel(value));
        }
        assertEquals("Intel(R) Core(TM) i5-6500T CPU @ 2.50GHz",
            ComputerInformation.safeCpuModel("Intel(R) Core(TM) i5-6500T CPU @ 2.50GHz"));
        assertEquals("Unavailable", ComputerInformation.safeCpuModel("CPU\t@ 2.40GHz"));
        assertEquals("Unavailable", ComputerInformation.safeCpuModel("CPU\n@ 2.40GHz"));
        assertEquals("Unavailable", ComputerInformation.safeText(null));
        assertEquals("Apple M3", ComputerInformation.safeText(" Apple M3 "));
        assertNull(ComputerInformation.cpuFromLinux("processor : 0\nHardware : device\n"));
        assertEquals(0L, ComputerInformation.memoryFromLinux("MemTotal: 999999999999999999 kB"));
        assertEquals(0L, ComputerInformation.memoryFromLinux("MemTotal: 4 GB"));
        assertEquals(0L, ComputerInformation.parseHardwareLines("CPU\ninvalid\n").installedMemoryBytes());
        assertNull(ComputerInformation.parseHardwareLines("unexpected extra output\nCPU\n4\n").cpuModel());
    }

    private static final class FakeProbe implements ComputerInformation.Probe
    {
        private final String osName;
        private final String output;
        private final Map<Path,String> files;
        private final List<List<String>> commands = new ArrayList<>();
        private final List<Long> timeouts = new ArrayList<>();

        private FakeProbe(String osName, String output, Map<Path,String> files)
        {
            this.osName = osName;
            this.output = output;
            this.files = files;
        }

        @Override public String property(String name)
        {
            return switch(name)
            {
                case "os.name" -> osName;
                case "os.version" -> "1.2";
                case "os.arch" -> "aarch64";
                case "java.version" -> "25.0.1";
                case "java.vendor" -> "Example Java";
                default -> throw new AssertionError("Unexpected property: " + name);
            };
        }
        @Override public String command(List<String> arguments, long timeoutMilliseconds)
        {
            commands.add(arguments);
            timeouts.add(timeoutMilliseconds);
            return output;
        }
        @Override public String file(Path path) { return files.get(path); }
        @Override public int processors() { return 8; }
        @Override public long heapLimitBytes() { return 2 * GIB; }
    }
}
