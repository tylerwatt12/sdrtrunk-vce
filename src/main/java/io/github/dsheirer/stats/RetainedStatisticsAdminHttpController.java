/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */
package io.github.dsheirer.stats;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import io.github.dsheirer.stats.activity.ReceiverActivityMaintenance;
import io.github.dsheirer.stats.activity.StatsDatabaseMaintenanceRequest;
import io.github.dsheirer.stats.activity.StatsDatabaseMaintenanceRequest.DeletionTarget;
import io.github.dsheirer.web.http.ApiHttpResponse;
import io.github.dsheirer.web.http.ApiRequestDecoder;
import java.io.IOException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;

/** Administrator-only discovery and ordered deletion of retained statistics. */
final class RetainedStatisticsAdminHttpController
{
    static final String PATH = "/api/v1/admin/retained-statistics";
    private static final ObjectMapper MAPPER = new ObjectMapper(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());
    private static final int MAXIMUM_BODY_BYTES = 4_096;
    private static final int MAXIMUM_PAGE_SIZE = 100;
    private static final int MAXIMUM_JOBS = 64;
    private static final long COMPLETED_JOB_RETENTION_MS = 3_600_000L;

    private final RetainedStatisticsCatalog mCatalog;
    private final Consumer<StatsDatabaseMaintenanceRequest> mDispatcher;
    private final Object mJobLock = new Object();
    private final Map<String,Job> mJobs = new LinkedHashMap<>();
    private final Semaphore mActiveDeletion = new Semaphore(1, true);

    RetainedStatisticsAdminHttpController(RetainedStatisticsCatalog catalog,
                                          Consumer<StatsDatabaseMaintenanceRequest> dispatcher)
    {
        mCatalog = Objects.requireNonNull(catalog);
        mDispatcher = Objects.requireNonNull(dispatcher);
    }

    void handle(HttpExchange exchange) throws IOException
    {
        try
        {
            String path = exchange.getRequestURI().getRawPath();
            if((PATH + "/sources").equals(path))
            {
                requireMethod(exchange, "GET");
                StatsRequest request = StatsRequest.from(exchange.getRequestURI());
                request.requireOnly("kind", "q", "limit", "offset");
                String kind = request.requiredText("kind");
                String search = request.search();
                int limit = request.limit(MAXIMUM_PAGE_SIZE);
                int offset = request.offset();
                sendPage(exchange, mCatalog.sources(kind, search, limit, offset));
            }
            else if((PATH + "/sites").equals(path))
            {
                requireMethod(exchange, "GET");
                StatsRequest request = StatsRequest.from(exchange.getRequestURI());
                request.requireOnly("source_kind", "source_key", "q", "limit", "offset");
                String sourceKind = request.requiredText("source_kind");
                String sourceKey = request.requiredText("source_key");
                String search = request.search();
                int limit = request.limit(MAXIMUM_PAGE_SIZE);
                int offset = request.offset();
                sendPage(exchange, mCatalog.sites(sourceKind, sourceKey, search, limit, offset));
            }
            else if((PATH + "/results").equals(path))
            {
                requireMethod(exchange, "GET");
                StatsRequest request = StatsRequest.from(exchange.getRequestURI());
                request.requireOnly("source_kind", "source_key", "data_type", "site_configuration_id",
                    "q", "limit", "offset");
                String sourceKind = request.requiredText("source_kind");
                String sourceKey = request.requiredText("source_key");
                String dataType = request.requiredText("data_type");
                String siteConfigurationId = request.text("site_configuration_id");
                String search = request.search();
                int limit = request.limit(MAXIMUM_PAGE_SIZE);
                int offset = request.offset();
                sendPage(exchange, mCatalog.results(sourceKind, sourceKey, dataType,
                    siteConfigurationId, search, limit, offset));
            }
            else if((PATH + "/deletions").equals(path))
            {
                requireMethod(exchange, "POST");
                requireNoQuery(exchange);
                submit(exchange);
            }
            else if(path.startsWith(PATH + "/deletions/"))
            {
                requireMethod(exchange, "GET");
                requireNoQuery(exchange);
                String id = path.substring((PATH + "/deletions/").length());
                if(id.contains("/") || id.isBlank())
                {
                    throw new StatsApiException(404, "not_found", "Deletion job was not found");
                }
                showJob(exchange, id);
            }
            else
            {
                throw new StatsApiException(404, "not_found", "Resource not found");
            }
        }
        catch(StatsApiException exception)
        {
            ApiHttpResponse.sendError(exchange, exception.status(), exception.code(), exception.getMessage(),
                exception.field());
        }
        catch(IllegalArgumentException exception)
        {
            ApiHttpResponse.sendError(exchange, 400, "invalid_request", "Deletion target is invalid");
        }
        catch(RuntimeException exception)
        {
            ApiHttpResponse.sendError(exchange, 503, "statistics_unavailable",
                "Retained statistics could not be updated");
        }
    }

    private static void sendPage(HttpExchange exchange, RetainedStatisticsCatalog.Page page) throws IOException
    {
        ApiHttpResponse.sendDataWithMeta(exchange, 200, page.rows(), page.meta());
    }

    private void submit(HttpExchange exchange) throws IOException
    {
        JsonNode body = readBody(exchange);
        requireFields(body, Set.of("request_id", "target"));
        String requestId = requiredText(body, "request_id", 36);
        UUID parsedId;
        try
        {
            parsedId = UUID.fromString(requestId);
        }
        catch(IllegalArgumentException exception)
        {
            throw invalid("request_id", "request_id must be a UUID");
        }
        if(!parsedId.toString().equals(requestId))
        {
            throw invalid("request_id", "request_id must be a canonical UUID");
        }
        DeletionTarget target = parseTarget(body.get("target"));
        Job job;
        StatsDatabaseMaintenanceRequest request = null;
        synchronized(mJobLock)
        {
            pruneJobs();
            job = mJobs.get(requestId);
            if(job != null)
            {
                if(!job.target.equals(target))
                {
                    throw new StatsApiException(409, "request_id_conflict",
                        "This request ID was already used for another target");
                }
            }
            else
            {
                while(mJobs.size() >= MAXIMUM_JOBS && evictOldestCompletedJob())
                {
                    // Keep active work and the newest retry keys; allow long cleanup sessions to continue.
                }
                if(mJobs.size() >= MAXIMUM_JOBS || !mActiveDeletion.tryAcquire())
                {
                    exchange.getResponseHeaders().set("Retry-After", "1");
                    throw new StatsApiException(429, "deletion_busy", "A deletion is already in progress");
                }
                job = new Job(requestId, target);
                mJobs.put(requestId, job);
                request = StatsDatabaseMaintenanceRequest.delete(target);
            }
        }
        if(request != null)
        {
            Job accepted = job;
            request.result().whenComplete((result, failure) -> finish(accepted, result, failure));
            accepted.state = "running";
            try
            {
                mDispatcher.accept(request);
            }
            catch(RuntimeException exception)
            {
                request.result().completeExceptionally(exception);
            }
        }
        ApiHttpResponse.sendData(exchange, 202, job.snapshot());
    }

    private void finish(Job job, ReceiverActivityMaintenance.Result result, Throwable failure)
    {
        synchronized(mJobLock)
        {
            if(failure != null || result == null)
            {
                job.state = "failed";
                job.error = "Deletion failed. Refresh the results and try again.";
            }
            else
            {
                job.state = "succeeded";
                job.rowsDeleted = result.rowsDeleted();
                job.outcome = switch(result.deletionOutcome())
                {
                    case NOT_FOUND -> "not_found";
                    case STALE_SITE -> "stale_site";
                    default -> "deleted";
                };
            }
            job.completedAtMs = System.currentTimeMillis();
            mActiveDeletion.release();
        }
    }

    private void showJob(HttpExchange exchange, String requestId) throws IOException
    {
        String canonical;
        try
        {
            canonical = UUID.fromString(requestId).toString();
        }
        catch(IllegalArgumentException exception)
        {
            throw new StatsApiException(404, "not_found", "Deletion job was not found");
        }
        Job job;
        synchronized(mJobLock)
        {
            pruneJobs();
            job = mJobs.get(canonical);
        }
        if(job == null)
        {
            throw new StatsApiException(404, "not_found", "Deletion job was not found");
        }
        ApiHttpResponse.sendData(exchange, 200, job.snapshot());
    }

    /** Caller holds mJobLock. */
    private void pruneJobs()
    {
        long oldest = System.currentTimeMillis() - COMPLETED_JOB_RETENTION_MS;
        Iterator<Job> jobs = mJobs.values().iterator();
        while(jobs.hasNext())
        {
            Job job = jobs.next();
            if(job.completedAtMs > 0 && job.completedAtMs < oldest)
            {
                jobs.remove();
            }
        }
    }

    /** Caller holds mJobLock. */
    private boolean evictOldestCompletedJob()
    {
        Iterator<Job> jobs = mJobs.values().iterator();
        while(jobs.hasNext())
        {
            if(jobs.next().completedAtMs > 0)
            {
                jobs.remove();
                return true;
            }
        }
        return false;
    }

    private static DeletionTarget parseTarget(JsonNode target)
    {
        if(target == null || !target.isObject())
        {
            throw invalid("target", "target must be an object");
        }
        String kind = requiredText(target, "kind", 32);
        return switch(kind)
        {
            case "frequency" -> {
                requireFields(target, Set.of("kind", "site_configuration_id", "expected_site_key", "frequency_hz"));
                yield new StatsDatabaseMaintenanceRequest.Frequency(
                    requiredText(target, "site_configuration_id", 36),
                    requiredText(target, "expected_site_key", 256),
                    requiredLong(target, "frequency_hz"));
            }
            case "radio", "talkgroup" -> {
                requireFields(target, Set.of("kind", "radio_system_key", "identity_key"));
                yield new StatsDatabaseMaintenanceRequest.Identity(
                    requiredText(target, "radio_system_key", 512),
                    requiredText(target, "identity_key", 256),
                    "radio".equals(kind) ? StatsDatabaseMaintenanceRequest.IdentityKind.RADIO :
                        StatsDatabaseMaintenanceRequest.IdentityKind.TALKGROUP);
            }
            case "conventional_radio", "conventional_talkgroup" -> {
                requireFields(target, Set.of("kind", "configuration_id", "frequency_hz",
                    "timeslot", "native_id"));
                yield new StatsDatabaseMaintenanceRequest.ConventionalIdentity(
                    requiredText(target, "configuration_id", 36),
                    requiredLong(target, "frequency_hz"), requiredInt(target, "timeslot", 1, 2),
                    requiredInt(target, "native_id", 1, 16_777_215),
                    "conventional_radio".equals(kind) ? StatsDatabaseMaintenanceRequest.IdentityKind.RADIO :
                        StatsDatabaseMaintenanceRequest.IdentityKind.TALKGROUP);
            }
            case "learned_site" -> {
                requireFields(target, Set.of("kind", "radio_system_key", "rfss", "site", "include_channel_history"));
                yield new StatsDatabaseMaintenanceRequest.LearnedSite(
                    requiredText(target, "radio_system_key", 512),
                    requiredInt(target, "rfss", 0, 255), requiredInt(target, "site", 0, 255),
                    requiredBoolean(target, "include_channel_history"));
            }
            case "saved_site" -> {
                requireFields(target, Set.of("kind", "configuration_id", "expected_site_key",
                    "include_channel_history"));
                yield new StatsDatabaseMaintenanceRequest.SavedSite(
                    requiredText(target, "configuration_id", 36),
                    requiredText(target, "expected_site_key", 256),
                    requiredBoolean(target, "include_channel_history"));
            }
            case "channel" -> {
                requireFields(target, Set.of("kind", "configuration_id"));
                yield new StatsDatabaseMaintenanceRequest.Channel(requiredText(target, "configuration_id", 36));
            }
            case "system" -> {
                requireFields(target, Set.of("kind", "radio_system_key", "include_channel_history"));
                yield new StatsDatabaseMaintenanceRequest.System(
                    requiredText(target, "radio_system_key", 512),
                    requiredBoolean(target, "include_channel_history"));
            }
            default -> throw invalid("kind", "Unsupported deletion target");
        };
    }

    private static JsonNode readBody(HttpExchange exchange) throws IOException
    {
        String type = exchange.getRequestHeaders().getFirst("Content-Type");
        if(type == null || !"application/json".equals(type.toLowerCase(Locale.ROOT).split(";", 2)[0].strip()))
        {
            throw new StatsApiException(415, "invalid_content_type", "Content-Type must be application/json");
        }
        byte[] bytes = ApiRequestDecoder.readBody(exchange, MAXIMUM_BODY_BYTES);
        try
        {
            if(bytes.length == 0 || bytes.length > MAXIMUM_BODY_BYTES)
            {
                throw invalid("body", "JSON body is missing or too large");
            }
            return MAPPER.readTree(bytes);
        }
        catch(StatsApiException exception)
        {
            throw exception;
        }
        catch(Exception exception)
        {
            throw invalid("body", "JSON body is invalid");
        }
        finally
        {
            Arrays.fill(bytes, (byte)0);
        }
    }

    private static void requireFields(JsonNode object, Set<String> allowed)
    {
        if(object == null || !object.isObject())
        {
            throw invalid("body", "JSON object is required");
        }
        Iterator<String> fields = object.fieldNames();
        while(fields.hasNext())
        {
            String field = fields.next();
            if(!allowed.contains(field))
            {
                throw invalid(field, field + " is not supported");
            }
        }
        for(String required: allowed)
        {
            if(!object.has(required))
            {
                throw invalid(required, required + " is required");
            }
        }
    }

    private static String requiredText(JsonNode object, String field, int maximum)
    {
        JsonNode value = object.get(field);
        if(value == null || !value.isTextual() || value.textValue().isBlank() ||
            value.textValue().length() > maximum)
        {
            throw invalid(field, field + " is invalid");
        }
        return value.textValue();
    }

    private static long requiredLong(JsonNode object, String field)
    {
        JsonNode value = object.get(field);
        if(value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() <= 0)
        {
            throw invalid(field, field + " must be a positive integer");
        }
        return value.longValue();
    }

    private static int requiredInt(JsonNode object, String field, int minimum, int maximum)
    {
        JsonNode value = object.get(field);
        if(value == null || !value.isIntegralNumber() || !value.canConvertToInt() ||
            value.intValue() < minimum || value.intValue() > maximum)
        {
            throw invalid(field, field + " is out of range");
        }
        return value.intValue();
    }

    private static boolean requiredBoolean(JsonNode object, String field)
    {
        JsonNode value = object.get(field);
        if(value == null || !value.isBoolean())
        {
            throw invalid(field, field + " must be true or false");
        }
        return value.booleanValue();
    }

    private static void requireMethod(HttpExchange exchange, String method)
    {
        if(!method.equals(exchange.getRequestMethod()))
        {
            exchange.getResponseHeaders().set("Allow", method);
            throw new StatsApiException(405, "method_not_allowed", "Method not allowed");
        }
    }

    private static void requireNoQuery(HttpExchange exchange)
    {
        if(exchange.getRequestURI().getRawQuery() != null)
        {
            throw invalid("query", "Query parameters are not supported");
        }
    }

    private static StatsApiException invalid(String field, String message)
    {
        return new StatsApiException(400, "invalid_parameter", message, field);
    }

    private static final class Job
    {
        private final String id;
        private final DeletionTarget target;
        private volatile String state = "queued";
        private volatile Integer rowsDeleted;
        private volatile String outcome;
        private volatile String error;
        private volatile long completedAtMs;

        private Job(String id, DeletionTarget target)
        {
            this.id = id;
            this.target = target;
        }

        private Map<String,Object> snapshot()
        {
            Map<String,Object> result = new LinkedHashMap<>();
            result.put("job_id", id);
            result.put("state", state);
            if(rowsDeleted != null) result.put("rows_deleted", rowsDeleted);
            if(outcome != null) result.put("outcome", outcome);
            if(error != null) result.put("error", error);
            return result;
        }
    }
}
