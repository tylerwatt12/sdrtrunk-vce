/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.gui.bugreport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dsheirer.application.ApplicationInfo;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * HTTPS multipart uploader for the RadioResolve bug-report service.
 */
public class BugReportUploader
{
    private static final Pattern REPORT_CODE = Pattern.compile(
        "VCE-[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}");
    private final ObjectMapper mObjectMapper = new ObjectMapper();
    private final HttpClient mHttpClient;
    private final URI mEndpoint;

    public BugReportUploader()
    {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NEVER).build(), BugReportConstants.REPORT_ENDPOINT);
    }

    BugReportUploader(HttpClient httpClient, URI endpoint)
    {
        mHttpClient = httpClient;
        mEndpoint = endpoint;
    }

    public BugReportSubmission upload(BugReportBundle bundle) throws IOException, InterruptedException
    {
        if(bundle.sizeBytes() > BugReportConstants.MAX_BUNDLE_BYTES)
        {
            throw new IOException("The diagnostic package exceeds the 100 MB upload limit.");
        }

        String boundary = "----sdrtrunk-vce-" + UUID.randomUUID();
        byte[] metadata = mObjectMapper.writeValueAsBytes(Map.of(
            "bundle_format_version", BugReportConstants.BUNDLE_FORMAT_VERSION,
            "client_report_id", bundle.clientReportId(),
            "submitted_at_utc", Instant.now().toString(),
            "application_version", String.valueOf(ApplicationInfo.getVersion())
        ));
        HttpRequest.BodyPublisher body = multipartBody(boundary, metadata, bundle);
        HttpRequest request = HttpRequest.newBuilder(mEndpoint)
            .timeout(Duration.ofMinutes(5))
            .header("Accept", "application/json")
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .header("User-Agent", ApplicationInfo.getProductName() + "/" +
                String.valueOf(ApplicationInfo.getVersion()))
            .header("X-Bug-Report-Protocol", Integer.toString(BugReportConstants.BUNDLE_FORMAT_VERSION))
            .header("X-Client-Report-ID", bundle.clientReportId())
            .POST(body)
            .build();
        HttpResponse<String> response = mHttpClient.send(request,
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        if(response.statusCode() < 200 || response.statusCode() >= 300)
        {
            throw new IOException("The bug report server returned HTTP " + response.statusCode() + ".");
        }

        return parseSubmission(response.body());
    }

    private HttpRequest.BodyPublisher multipartBody(String boundary, byte[] metadata, BugReportBundle bundle)
        throws IOException
    {
        String metadataHeader = "--" + boundary + "\r\n" +
            "Content-Disposition: form-data; name=\"metadata\"\r\n" +
            "Content-Type: application/json; charset=UTF-8\r\n\r\n";
        String bundleHeader = "\r\n--" + boundary + "\r\n" +
            "Content-Disposition: form-data; name=\"bundle\"; filename=\"diagnostic.zip\"\r\n" +
            "Content-Type: application/zip\r\n\r\n";
        String ending = "\r\n--" + boundary + "--\r\n";
        List<HttpRequest.BodyPublisher> parts = List.of(
            HttpRequest.BodyPublishers.ofByteArray(metadataHeader.getBytes(StandardCharsets.UTF_8)),
            HttpRequest.BodyPublishers.ofByteArray(metadata),
            HttpRequest.BodyPublishers.ofByteArray(bundleHeader.getBytes(StandardCharsets.UTF_8)),
            HttpRequest.BodyPublishers.ofFile(bundle.path()),
            HttpRequest.BodyPublishers.ofByteArray(ending.getBytes(StandardCharsets.UTF_8))
        );
        return HttpRequest.BodyPublishers.concat(parts.toArray(HttpRequest.BodyPublisher[]::new));
    }

    BugReportSubmission parseSubmission(String responseBody) throws IOException
    {
        JsonNode response = mObjectMapper.readTree(responseBody);
        String reportCode = text(response, "report_code", "reportCode");

        if(reportCode == null || !REPORT_CODE.matcher(reportCode).matches())
        {
            throw new IOException("The bug report server response did not contain a valid report code.");
        }

        return new BugReportSubmission(reportCode,
            text(response, "received_at_utc", "receivedAtUtc"),
            text(response, "retention_until_utc", "retentionUntilUtc"));
    }

    private static String text(JsonNode node, String snakeCase, String camelCase)
    {
        JsonNode value = node.get(snakeCase);

        if(value == null)
        {
            value = node.get(camelCase);
        }

        return value != null && value.isTextual() ? value.textValue() : null;
    }
}
