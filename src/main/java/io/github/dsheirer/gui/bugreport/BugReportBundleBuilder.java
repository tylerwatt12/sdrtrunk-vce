/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.gui.bugreport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.dsheirer.application.ApplicationInfo;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.source.tuner.Tuner;
import io.github.dsheirer.source.tuner.TunerController;
import io.github.dsheirer.source.tuner.channel.TunerChannel;
import io.github.dsheirer.source.tuner.manager.ChannelSourceManager;
import io.github.dsheirer.source.tuner.manager.DiscoveredTuner;
import io.github.dsheirer.source.tuner.manager.TunerManager;
import io.github.dsheirer.source.tuner.ui.DiscoveredTunerModel;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;

/**
 * Creates a fixed-scope diagnostic ZIP. Only this class chooses bundle contents; callers cannot opt categories out.
 */
public class BugReportBundleBuilder
{
    private static final String APPLICATION_LOG_FILENAME = "sdrtrunk_app.log";
    private final Path mApplicationRoot;
    private final Path mApplicationLogDirectory;
    private final Path mDatabasePath;
    private final DiscoveredTunerModel mTunerModel;
    private final ObjectMapper mObjectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final BugReportRedactor mRedactor = new BugReportRedactor();
    private final BugReportConfigurationExporter mConfigurationExporter =
        new BugReportConfigurationExporter(mObjectMapper, mRedactor);

    public BugReportBundleBuilder(UserPreferences userPreferences, TunerManager tunerManager)
    {
        this(userPreferences.getDirectoryPreference().getDirectoryApplicationRoot(),
            userPreferences.getDirectoryPreference().getDirectoryApplicationLog(),
            userPreferences.getDirectoryPreference().getDirectoryApplicationRoot()
                .resolve("database").resolve("sdrtrunk.sqlite"),
            tunerManager.getDiscoveredTunerModel());
    }

    BugReportBundleBuilder(Path applicationRoot, Path applicationLogDirectory, Path databasePath,
                           DiscoveredTunerModel tunerModel)
    {
        mApplicationRoot = applicationRoot.toAbsolutePath().normalize();
        mApplicationLogDirectory = applicationLogDirectory.toAbsolutePath().normalize();
        mDatabasePath = databasePath.toAbsolutePath().normalize();
        mTunerModel = tunerModel;
    }

    public BugReportBundle build(BugReportRequest request) throws IOException, SQLException
    {
        if(request == null || request.applicationScreenshot() == null)
        {
            throw new IOException("The application screenshot could not be captured.");
        }

        if(request.additionalScreenshots().size() > BugReportConstants.MAX_ADDITIONAL_SCREENSHOTS)
        {
            throw new IOException("No more than " + BugReportConstants.MAX_ADDITIONAL_SCREENSHOTS +
                " additional screenshots can be included.");
        }

        long additionalPixels = request.additionalScreenshots().stream()
            .mapToLong(image -> (long)image.getWidth() * image.getHeight()).sum();

        if(additionalPixels > BugReportConstants.MAX_ADDITIONAL_SCREENSHOT_PIXELS)
        {
            throw new IOException("The combined additional screenshot dimensions are too large.");
        }

        String clientReportId = UUID.randomUUID().toString();
        Path reportDirectory = mApplicationRoot.resolve("bug_reports");
        Files.createDirectories(reportDirectory);
        Path bundlePath = Files.createTempFile(reportDirectory, "sdrtrunk-vce-bug-report-", ".zip");
        List<EntryDescriptor> entries = new ArrayList<>();
        List<ScreenshotDescriptor> screenshots = new ArrayList<>();

        try
        {
            try(ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(bundlePath), StandardCharsets.UTF_8))
            {
                addJson(zip, entries, "user-report.json", createUserReport(request, clientReportId));
                addJson(zip, entries, "system.json", createSystemReport(clientReportId, request.consentedAt()));
                addJson(zip, entries, "tuners.json", createTunerReport());
                addImage(zip, entries, "screenshots/application.png", request.applicationScreenshot());
                screenshots.add(new ScreenshotDescriptor("screenshots/application.png", "automatic_application"));

                for(int x = 0; x < request.additionalScreenshots().size(); x++)
                {
                    String path = "screenshots/user-%03d.png".formatted(x + 1);
                    addImage(zip, entries, path, request.additionalScreenshots().get(x));
                    screenshots.add(new ScreenshotDescriptor(path, "user_added"));
                }

                addApplicationLog(zip, entries);
                addConfiguration(zip, entries);

                Map<String,Object> manifest = createManifest(clientReportId, request.consentedAt(), entries,
                    screenshots);
                addJson(zip, entries, "manifest.json", manifest);
                addChecksums(zip, entries);
            }

            long size = Files.size(bundlePath);

            if(size > BugReportConstants.MAX_BUNDLE_BYTES)
            {
                throw new IOException("The diagnostic package is " + formatMegabytes(size) +
                    " MB, which exceeds the 100 MB upload limit.");
            }

            return new BugReportBundle(bundlePath, clientReportId, size);
        }
        catch(IOException | SQLException | RuntimeException e)
        {
            Files.deleteIfExists(bundlePath);
            throw e;
        }
    }

    private Map<String,Object> createUserReport(BugReportRequest request, String clientReportId)
    {
        Map<String,Object> report = new LinkedHashMap<>();
        report.put("client_report_id", clientReportId);
        report.put("summary", mRedactor.redactText(request.summary().trim()));
        report.put("description", mRedactor.redactText(request.description().trim()));
        report.put("reproduction_steps", mRedactor.redactText(request.reproductionSteps().trim()));
        report.put("consented_at_local", request.consentedAt().toString());
        report.put("consented_at_utc", request.consentedAt().toInstant().toString());
        report.put("consent_version", BugReportConstants.CONSENT_VERSION);
        return report;
    }

    private Map<String,Object> createSystemReport(String clientReportId, ZonedDateTime capturedAt)
    {
        Map<String,Object> report = new LinkedHashMap<>();
        report.put("client_report_id", clientReportId);
        report.put("captured_at_local", capturedAt.toString());
        report.put("captured_at_utc", capturedAt.toInstant().toString());
        report.put("time_zone_id", ZoneId.systemDefault().getId());
        report.put("time_zone_display_name", TimeZone.getDefault().getDisplayName());
        report.put("locale", Locale.getDefault().toLanguageTag());
        report.put("hostname", hostname());
        report.put("public_ip_address", "Observed and stored by the report server from the HTTPS request");

        Map<String,Object> application = new LinkedHashMap<>();
        application.put("product", ApplicationInfo.getProductName());
        application.put("display_name", ApplicationInfo.getDisplayName());
        application.put("version", ApplicationInfo.getVersion());
        application.put("build_timestamp", ApplicationInfo.getBuildTimestamp());
        application.put("build_jdk", ApplicationInfo.getBuildJdk());
        application.put("build_os", ApplicationInfo.getBuildOs());
        application.put("process_started_at_utc",
            Instant.ofEpochMilli(ManagementFactory.getRuntimeMXBean().getStartTime()).toString());
        application.put("process_uptime_ms", ManagementFactory.getRuntimeMXBean().getUptime());
        application.put("jvm_arguments", ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
            .map(mRedactor::redactText).toList());
        report.put("application", application);

        Runtime runtime = Runtime.getRuntime();
        Map<String,Object> javaRuntime = new LinkedHashMap<>();
        javaRuntime.put("java_version", System.getProperty("java.version"));
        javaRuntime.put("java_vendor", System.getProperty("java.vendor"));
        javaRuntime.put("java_vm_name", System.getProperty("java.vm.name"));
        javaRuntime.put("java_vm_version", System.getProperty("java.vm.version"));
        javaRuntime.put("javafx_version", System.getProperty("javafx.runtime.version"));
        javaRuntime.put("default_charset", Charset.defaultCharset().name());
        javaRuntime.put("maximum_memory_bytes", runtime.maxMemory());
        javaRuntime.put("allocated_memory_bytes", runtime.totalMemory());
        javaRuntime.put("free_allocated_memory_bytes", runtime.freeMemory());
        report.put("java_runtime", javaRuntime);
        report.put("machine", new BugReportHardwareCollector(mApplicationRoot).collect());
        return report;
    }

    private List<Map<String,Object>> createTunerReport()
    {
        List<Map<String,Object>> tuners = new ArrayList<>();
        DiscoveredTunerModel model = mTunerModel;

        if(model == null)
        {
            return tuners;
        }

        for(int index = 0; index < model.getRowCount(); index++)
        {
            DiscoveredTuner discovered = model.getDiscoveredTuner(index);

            if(discovered != null)
            {
                tuners.add(createTuner(discovered));
            }
        }

        return tuners;
    }

    private Map<String,Object> createTuner(DiscoveredTuner discovered)
    {
        Map<String,Object> report = new LinkedHashMap<>();
        report.put("identifier", discovered.getId());
        report.put("serial_number_redacted", false);
        report.put("discovered_class", discovered.getClass().getName());
        report.put("tuner_class", discovered.getTunerClass().toString());
        report.put("status", discovered.getTunerStatus().toString());
        report.put("enabled", discovered.isEnabled());
        report.put("error_message", mRedactor.redactText(discovered.getErrorMessage()));
        report.put("diagnostic_report", mRedactor.redactText(discovered.getDiagnosticReport()));

        if(discovered.hasTunerConfiguration())
        {
            try
            {
                JsonNode configuration = mObjectMapper.valueToTree(discovered.getTunerConfiguration());
                report.put("configuration", mRedactor.redact(configuration));
            }
            catch(Exception e)
            {
                report.put("configuration_export_error", e.getClass().getSimpleName());
            }
        }

        if(discovered.hasTuner())
        {
            Tuner tuner = discovered.getTuner();
            report.put("unique_id", tuner.getUniqueID());
            report.put("preferred_name", tuner.getPreferredName());
            report.put("type", tuner.getTunerType().toString());
            report.put("implementation_class", tuner.getClass().getName());
            report.put("sample_size_bits", tuner.getSampleSize());
            report.put("maximum_usb_bits_per_second", tuner.getMaximumUSBBitsPerSecond());

            TunerController controller = tuner.getTunerController();
            Map<String,Object> controllerReport = new LinkedHashMap<>();
            controllerReport.put("implementation_class", controller.getClass().getName());
            controllerReport.put("frequency_hz", controller.getFrequency());
            controllerReport.put("sample_rate_hz", controller.getSampleRate());
            controllerReport.put("bandwidth_hz", controller.getBandwidth());
            controllerReport.put("usable_bandwidth_hz", controller.getUsableBandwidth());
            controllerReport.put("frequency_correction_ppm", controller.getFrequencyCorrection());
            controllerReport.put("measured_frequency_error_hz", controller.getMeasuredFrequencyError());
            controllerReport.put("measured_frequency_error_ppm", controller.getPPMFrequencyError());
            controllerReport.put("sample_rate_locked", controller.isLockedSampleRate());
            report.put("controller", controllerReport);

            ChannelSourceManager channelManager = tuner.getChannelSourceManager();

            if(channelManager != null)
            {
                Map<String,Object> channelReport = new LinkedHashMap<>();
                channelReport.put("implementation_class", channelManager.getClass().getName());
                channelReport.put("channel_count", channelManager.getTunerChannelCount());
                channelReport.put("state", mRedactor.redactText(channelManager.getStateDescription()));
                List<Map<String,Object>> channels = new ArrayList<>();

                for(TunerChannel channel: channelManager.getTunerChannels())
                {
                    channels.add(Map.of("frequency_hz", channel.getFrequency(), "bandwidth_hz", channel.getBandwidth()));
                }

                channelReport.put("channels", channels);
                report.put("channel_manager", channelReport);
            }
        }

        return report;
    }

    private void addApplicationLog(ZipOutputStream zip, List<EntryDescriptor> entries) throws IOException
    {
        Path log = mApplicationLogDirectory.resolve(APPLICATION_LOG_FILENAME);

        if(!Files.isRegularFile(log))
        {
            throw new IOException("The current application log could not be found at " + log);
        }

        String sanitizedLog = mRedactor.redactText(Files.readString(log, StandardCharsets.UTF_8));
        addBytes(zip, entries, "logs/" + APPLICATION_LOG_FILENAME,
            sanitizedLog.getBytes(StandardCharsets.UTF_8));
    }

    private void addConfiguration(ZipOutputStream zip, List<EntryDescriptor> entries)
        throws IOException, SQLException
    {
        Map<String,List<Map<String,Object>>> configuration = mConfigurationExporter.export(mDatabasePath);

        for(Map.Entry<String,List<Map<String,Object>>> table: configuration.entrySet())
        {
            addJson(zip, entries, "configuration/" + table.getKey() + ".json", table.getValue());
        }
    }

    private Map<String,Object> createManifest(String clientReportId, ZonedDateTime capturedAt,
                                               List<EntryDescriptor> entries,
                                               List<ScreenshotDescriptor> screenshots)
    {
        Map<String,Object> manifest = new LinkedHashMap<>();
        manifest.put("bundle_format_version", BugReportConstants.BUNDLE_FORMAT_VERSION);
        manifest.put("client_report_id", clientReportId);
        manifest.put("created_at_local", capturedAt.toString());
        manifest.put("created_at_utc", capturedAt.toInstant().toString());
        manifest.put("destination", BugReportConstants.DESTINATION);
        manifest.put("endpoint", BugReportConstants.REPORT_ENDPOINT.toString());
        manifest.put("manual_upload_destination", BugReportConstants.MANUAL_UPLOAD_DESTINATION);
        manifest.put("consent_version", BugReportConstants.CONSENT_VERSION);
        manifest.put("consent_statement", BugReportConstants.CONSENT_LABEL);
        manifest.put("disclosure", BugReportConstants.DISCLOSURE);
        manifest.put("exclusion_notice", BugReportConstants.EXCLUSION_NOTICE);
        manifest.put("retention_notice", BugReportConstants.RETENTION_NOTICE);
        manifest.put("screenshot_warning", BugReportConstants.SCREENSHOT_WARNING);
        manifest.put("optional_categories", false);
        manifest.put("tuner_serial_numbers_redacted", false);
        manifest.put("local_ip_addresses_included", false);
        manifest.put("disk_serial_numbers_included", false);
        manifest.put("raw_database_included", false);
        manifest.put("screenshot_redaction_applied", false);
        manifest.put("screenshot_source_metadata_retained", false);
        manifest.put("screenshots", List.copyOf(screenshots));
        manifest.put("entries", List.copyOf(entries));
        return manifest;
    }

    private void addJson(ZipOutputStream zip, List<EntryDescriptor> entries, String name, Object value)
        throws IOException
    {
        addBytes(zip, entries, name, mObjectMapper.writeValueAsBytes(value));
    }

    private void addImage(ZipOutputStream zip, List<EntryDescriptor> entries, String name, BufferedImage image)
        throws IOException
    {
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        if(!ImageIO.write(image, "png", output))
        {
            throw new IOException("PNG screenshot encoding is unavailable.");
        }

        addBytes(zip, entries, name, output.toByteArray());
    }

    private void addChecksums(ZipOutputStream zip, List<EntryDescriptor> entries) throws IOException
    {
        StringBuilder checksums = new StringBuilder();

        for(EntryDescriptor entry: entries)
        {
            checksums.append(entry.sha256()).append("  ").append(entry.path()).append('\n');
        }

        addBytes(zip, null, "checksums.sha256", checksums.toString().getBytes(StandardCharsets.UTF_8));
    }

    private void addBytes(ZipOutputStream zip, List<EntryDescriptor> entries, String name, byte[] value)
        throws IOException
    {
        ZipEntry entry = new ZipEntry(name);
        zip.putNextEntry(entry);
        zip.write(value);
        zip.closeEntry();

        if(entries != null)
        {
            entries.add(new EntryDescriptor(name, value.length, sha256(value)));
        }
    }

    private String hostname()
    {
        try
        {
            return InetAddress.getLocalHost().getHostName();
        }
        catch(Exception e)
        {
            return "Unavailable (" + e.getClass().getSimpleName() + ")";
        }
    }

    private static String sha256(byte[] value) throws IOException
    {
        try
        {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        }
        catch(NoSuchAlgorithmException e)
        {
            throw new IOException("SHA-256 is unavailable", e);
        }
    }

    private static String formatMegabytes(long bytes)
    {
        return String.format(Locale.US, "%.1f", bytes / (1024.0 * 1024.0));
    }

    private record EntryDescriptor(String path, long size_bytes, String sha256)
    {
    }

    private record ScreenshotDescriptor(String path, String source)
    {
    }
}
