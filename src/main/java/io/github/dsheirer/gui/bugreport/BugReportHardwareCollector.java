/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.gui.bugreport;

import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.GlobalMemory;
import oshi.hardware.HWDiskStore;
import oshi.hardware.HWPartition;

/**
 * Collects a bounded hardware inventory for the diagnostic report.
 */
final class BugReportHardwareCollector
{
    private final Path mApplicationRoot;
    private final SystemInfo mSystemInfo;

    BugReportHardwareCollector(Path applicationRoot)
    {
        mApplicationRoot = applicationRoot.toAbsolutePath().normalize();
        mSystemInfo = new SystemInfo();
    }

    Map<String,Object> collect()
    {
        Map<String,Object> hardware = new LinkedHashMap<>();
        hardware.put("operating_system", operatingSystem());
        hardware.put("cpu", cpu());
        hardware.put("memory", memory());
        hardware.put("data_storage", dataStorage());
        return hardware;
    }

    private Map<String,Object> operatingSystem()
    {
        Map<String,Object> os = new LinkedHashMap<>();
        os.put("name", System.getProperty("os.name"));
        os.put("version", System.getProperty("os.version"));
        os.put("architecture", System.getProperty("os.arch"));
        return os;
    }

    private Map<String,Object> cpu()
    {
        Map<String,Object> cpu = new LinkedHashMap<>();

        try
        {
            CentralProcessor processor = mSystemInfo.getHardware().getProcessor();
            CentralProcessor.ProcessorIdentifier identifier = processor.getProcessorIdentifier();
            cpu.put("manufacturer", identifier.getVendor());
            cpu.put("model_name", identifier.getName());
            cpu.put("family", identifier.getFamily());
            cpu.put("model_identifier", identifier.getModel());
            cpu.put("stepping", identifier.getStepping());
            cpu.put("microarchitecture", identifier.getMicroarchitecture());
            cpu.put("physical_packages", processor.getPhysicalPackageCount());
            cpu.put("physical_cores", processor.getPhysicalProcessorCount());
            cpu.put("logical_threads", processor.getLogicalProcessorCount());
            cpu.put("maximum_frequency_hz", processor.getMaxFreq());
        }
        catch(Exception | LinkageError e)
        {
            cpu.put("collection_error", e.getClass().getSimpleName());
        }

        return cpu;
    }

    private Map<String,Object> memory()
    {
        Map<String,Object> memory = new LinkedHashMap<>();

        try
        {
            GlobalMemory globalMemory = mSystemInfo.getHardware().getMemory();
            long total = Math.max(0L, globalMemory.getTotal());
            long available = Math.max(0L, globalMemory.getAvailable());
            memory.put("total_bytes", total);
            memory.put("available_bytes", available);
            memory.put("used_bytes", Math.max(0L, total - available));
        }
        catch(Exception | LinkageError e)
        {
            memory.put("collection_error", e.getClass().getSimpleName());
        }

        return memory;
    }

    private Map<String,Object> dataStorage()
    {
        Map<String,Object> storage = new LinkedHashMap<>();

        try
        {
            FileStore fileStore = Files.getFileStore(mApplicationRoot);
            long total = Math.max(0L, fileStore.getTotalSpace());
            long unallocated = Math.max(0L, fileStore.getUnallocatedSpace());
            long usable = Math.max(0L, fileStore.getUsableSpace());
            storage.put("volume_name", fileStore.name());
            storage.put("filesystem_type", fileStore.type());
            storage.put("total_bytes", total);
            storage.put("used_bytes", Math.max(0L, total - unallocated));
            storage.put("unallocated_bytes", unallocated);
            storage.put("available_to_application_bytes", usable);
        }
        catch(Exception | LinkageError e)
        {
            storage.put("volume_collection_error", e.getClass().getSimpleName());
        }

        storage.put("physical_disk", physicalDisk());
        return storage;
    }

    private Map<String,Object> physicalDisk()
    {
        Map<String,Object> diskReport = new LinkedHashMap<>();

        try
        {
            DiskMatch match = findDataDisk(mSystemInfo.getHardware().getDiskStores());

            if(match == null)
            {
                diskReport.put("matched", false);
                diskReport.put("reason", "The application data volume could not be correlated to one physical disk.");
                return diskReport;
            }

            HWDiskStore disk = match.disk();
            String manufacturer = inferManufacturer(disk.getModel());
            diskReport.put("matched", true);
            diskReport.put("name", disk.getName());
            diskReport.put("manufacturer", manufacturer);
            diskReport.put("manufacturer_inferred_from_model", !manufacturer.equals("Unavailable"));
            diskReport.put("model", disk.getModel());
            diskReport.put("type", disk.getDiskType());
            diskReport.put("capacity_bytes", Math.max(0L, disk.getSize()));
            diskReport.put("partition_name", match.partition().getName());
            diskReport.put("partition_type", match.partition().getType());
            diskReport.put("disk_serial_number_included", false);
        }
        catch(Exception | LinkageError e)
        {
            diskReport.put("matched", false);
            diskReport.put("collection_error", e.getClass().getSimpleName());
        }

        return diskReport;
    }

    private DiskMatch findDataDisk(List<HWDiskStore> disks)
    {
        DiskMatch bestMatch = null;
        int bestMountLength = -1;

        for(HWDiskStore disk: disks)
        {
            for(HWPartition partition: disk.getPartitions())
            {
                String mountPoint = partition.getMountPoint();

                if(isApplicationPathOnMount(mountPoint) && mountPoint.length() > bestMountLength)
                {
                    bestMatch = new DiskMatch(disk, partition);
                    bestMountLength = mountPoint.length();
                }
            }
        }

        return bestMatch;
    }

    private boolean isApplicationPathOnMount(String mountPoint)
    {
        if(mountPoint == null || mountPoint.isBlank())
        {
            return false;
        }

        try
        {
            Path mount = Path.of(mountPoint).toAbsolutePath().normalize();

            if(isWindows())
            {
                return mApplicationRoot.toString().toLowerCase(Locale.US)
                    .startsWith(mount.toString().toLowerCase(Locale.US));
            }

            return mApplicationRoot.startsWith(mount);
        }
        catch(Exception e)
        {
            return false;
        }
    }

    private static boolean isWindows()
    {
        return System.getProperty("os.name", "").toLowerCase(Locale.US).contains("win");
    }

    private static String inferManufacturer(String model)
    {
        if(model == null || model.isBlank())
        {
            return "Unavailable";
        }

        String normalized = model.strip().toUpperCase(Locale.US);

        if(normalized.startsWith("APPLE"))
        {
            return "Apple";
        }
        else if(normalized.startsWith("SAMSUNG"))
        {
            return "Samsung";
        }
        else if(normalized.startsWith("WDC") || normalized.startsWith("WD ") ||
            normalized.startsWith("WESTERN DIGITAL"))
        {
            return "Western Digital";
        }
        else if(normalized.startsWith("SEAGATE") || normalized.matches("ST[0-9].*"))
        {
            return "Seagate";
        }
        else if(normalized.startsWith("TOSHIBA"))
        {
            return "Toshiba";
        }
        else if(normalized.startsWith("KIOXIA"))
        {
            return "Kioxia";
        }
        else if(normalized.startsWith("SANDISK"))
        {
            return "SanDisk";
        }
        else if(normalized.startsWith("KINGSTON"))
        {
            return "Kingston";
        }
        else if(normalized.startsWith("MICRON"))
        {
            return "Micron";
        }
        else if(normalized.startsWith("CRUCIAL"))
        {
            return "Crucial";
        }
        else if(normalized.startsWith("INTEL"))
        {
            return "Intel";
        }
        else if(normalized.startsWith("SK HYNIX") || normalized.startsWith("HYNIX") ||
            normalized.startsWith("HFS"))
        {
            return "SK hynix";
        }

        return "Unavailable";
    }

    private record DiskMatch(HWDiskStore disk, HWPartition partition)
    {
    }
}
