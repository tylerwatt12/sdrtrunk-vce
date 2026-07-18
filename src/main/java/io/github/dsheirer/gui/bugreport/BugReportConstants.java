/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.gui.bugreport;

import java.net.URI;

/**
 * Shared bug-report protocol and disclosure constants.
 */
public final class BugReportConstants
{
    public static final int BUNDLE_FORMAT_VERSION = 1;
    public static final int CONSENT_VERSION = 1;
    public static final long MAX_BUNDLE_BYTES = 100L * 1024L * 1024L;
    public static final int MAX_ADDITIONAL_SCREENSHOTS = 10;
    public static final long MAX_SCREENSHOT_SOURCE_BYTES = 15L * 1024L * 1024L;
    public static final long MAX_SCREENSHOT_PIXELS = 50_000_000L;
    public static final long MAX_ADDITIONAL_SCREENSHOT_PIXELS = 75_000_000L;
    public static final int MAX_SCREENSHOT_DIMENSION = 16_384;
    public static final URI REPORT_ENDPOINT = URI.create("https://bugreport.radioresolve.com/api/v1/reports");
    public static final String DESTINATION = "https://bugreport.radioresolve.com";
    public static final String MANUAL_UPLOAD_DESTINATION = DESTINATION + "/manual-upload";
    public static final String EXCLUSION_NOTICE =
        "Excluded items: Passwords, API keys, encryption keys, the encryption vault, and recordings.";
    public static final String DISCLOSURE =
        "This report uploads application screenshots, the current application log, complete sanitized configuration " +
        "data, exact tuner identifiers and serial numbers, hostname, machine specifications, and the local timestamp " +
        "to " + DESTINATION + ". The report server records the public IP address used for the upload.";
    public static final String RETENTION_NOTICE =
        "Submitted reports are retained for 30 days unless they are deleted sooner or retained for an active " +
        "investigation.";
    public static final String SCREENSHOT_WARNING =
        "Screenshots are not automatically redacted. Review them for sensitive information before submitting the " +
        "report.";
    public static final String CONSENT_LABEL =
        "I understand and consent to upload this diagnostic report.";

    private BugReportConstants()
    {
    }
}
