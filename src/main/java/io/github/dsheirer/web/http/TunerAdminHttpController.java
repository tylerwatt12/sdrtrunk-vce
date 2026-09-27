/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.web.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import io.github.dsheirer.source.tuner.manager.DiscoveredTuner;
import io.github.dsheirer.source.tuner.manager.DiscoveredTuner.OperatorState;
import io.github.dsheirer.source.tuner.manager.TunerSettingsService;
import io.github.dsheirer.source.tuner.manager.TunerManager;
import io.github.dsheirer.web.tuner.TunerAdministrationService;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Administrator tuner inventory and asynchronous maintenance requests. */
public final class TunerAdminHttpController
{
    public static final String PATH = "/api/v1/admin/tuners";
    public static final String RESCAN_PATH = PATH + "/rescan";
    public static final String RF_ANALYSIS_PATH = PATH + "/rf-analysis";
    private static final Pattern TUNER_PATH = Pattern.compile("^" + PATH + "/(tuner-[0-9a-f]{32})$");
    private static final Pattern ENABLED_PATH = Pattern.compile("^" + PATH + "/(tuner-[0-9a-f]{32})/enabled$");
    private static final Pattern STATE_PATH = Pattern.compile("^" + PATH + "/(tuner-[0-9a-f]{32})/state$");
    private static final Pattern RESTORE_PATH = Pattern.compile("^" + PATH + "/(tuner-[0-9a-f]{32})/restore$");
    private static final Pattern SETTING_PATH = Pattern.compile("^" + PATH +
        "/(tuner-[0-9a-f]{32})/settings/([a-z][a-z0-9_]*)$");
    private final TunerAdministrationService mAdministration;
    private final TunerSettingsService mSettings;
    private final TunerManager mManager;
    private final Supplier<List<Long>> mActiveFrequencies;

    public TunerAdminHttpController(TunerAdministrationService administration, TunerSettingsService settings,
                                    TunerManager manager)
    {
        this(administration, settings, manager, List::of);
    }

    public TunerAdminHttpController(TunerAdministrationService administration, TunerSettingsService settings,
                                    TunerManager manager, Supplier<List<Long>> activeFrequencies)
    {
        mAdministration = Objects.requireNonNull(administration);
        mSettings = Objects.requireNonNull(settings);
        mManager = Objects.requireNonNull(manager);
        mActiveFrequencies = Objects.requireNonNull(activeFrequencies);
    }

    public void handle(HttpExchange exchange) throws IOException
    {
        if(!WebHttpSupport.requireNoQuery(exchange))
        {
            return;
        }
        String path = exchange.getRequestURI().getRawPath();
        if(PATH.equals(path))
        {
            readInventory(exchange);
            return;
        }
        if(RESCAN_PATH.equals(path))
        {
            rescan(exchange);
            return;
        }
        if(RF_ANALYSIS_PATH.equals(path))
        {
            readRfAnalysis(exchange);
            return;
        }
        Matcher tuner = TUNER_PATH.matcher(path);
        if(tuner.matches())
        {
            removeRecording(exchange, tuner.group(1));
            return;
        }
        Matcher enabled = ENABLED_PATH.matcher(path);
        if(enabled.matches())
        {
            setEnabled(exchange, enabled.group(1));
            return;
        }
        Matcher state = STATE_PATH.matcher(path);
        if(state.matches())
        {
            setState(exchange, state.group(1));
            return;
        }
        Matcher restore = RESTORE_PATH.matcher(path);
        if(restore.matches())
        {
            restoreChannels(exchange, restore.group(1));
            return;
        }
        Matcher setting = SETTING_PATH.matcher(path);
        if(setting.matches())
        {
            handleSetting(exchange, setting.group(1), setting.group(2));
            return;
        }
        WebHttpSupport.notFound(exchange);
    }

    private void readInventory(HttpExchange exchange) throws IOException
    {
        if(!"GET".equals(exchange.getRequestMethod()))
        {
            WebHttpSupport.methodNotAllowed(exchange, "GET");
            return;
        }
        if(WebHttpSupport.hasRequestBody(exchange))
        {
            ApiHttpResponse.sendError(exchange, 400, "invalid_request", "GET requests cannot include a body");
            return;
        }
        ApiHttpResponse.sendData(exchange, 200, mAdministration.snapshot());
    }

    private void readRfAnalysis(HttpExchange exchange) throws IOException
    {
        if(!"GET".equals(exchange.getRequestMethod()))
        {
            WebHttpSupport.methodNotAllowed(exchange, "GET");
            return;
        }
        if(WebHttpSupport.hasRequestBody(exchange))
        {
            ApiHttpResponse.sendError(exchange, 400, "invalid_request", "GET requests cannot include a body");
            return;
        }
        ApiHttpResponse.sendData(exchange, 200, new RfAnalysis(mAdministration.plannerTargets(),
            mActiveFrequencies.get()));
    }

    private record RfAnalysis(List<TunerAdministrationService.PlannerTarget> tuners, List<Long> frequenciesHz) { }

    private void rescan(HttpExchange exchange) throws IOException
    {
        if(!"POST".equals(exchange.getRequestMethod()))
        {
            WebHttpSupport.methodNotAllowed(exchange, "POST");
            return;
        }
        if(WebHttpSupport.hasRequestBody(exchange))
        {
            ApiHttpResponse.sendError(exchange, 400, "invalid_request", "Rescan does not accept a body");
            return;
        }
        // The manager queues bounded USB work; never wait for discovery on the HTTP request thread.
        CompletableFuture<Integer> requested = mManager.requestUsbTunerRescan();
        try
        {
            Integer immediate = requested.isDone() ? requested.getNow(null) : null;
            if(immediate != null && immediate < 0)
            {
                ApiHttpResponse.sendError(exchange, 503, "tuner_rescan_unavailable", "USB rescan is unavailable");
                return;
            }
        }
        catch(CompletionException exception)
        {
            ApiHttpResponse.sendError(exchange, 503, "tuner_rescan_unavailable", "USB rescan is unavailable");
            return;
        }
        ApiHttpResponse.sendData(exchange, 202, Map.of("status", "scanning"));
    }

    private void removeRecording(HttpExchange exchange, String tunerId) throws IOException
    {
        if(!"DELETE".equals(exchange.getRequestMethod()))
        {
            WebHttpSupport.methodNotAllowed(exchange, "DELETE");
            return;
        }
        if(WebHttpSupport.hasRequestBody(exchange))
        {
            ApiHttpResponse.sendError(exchange, 400, "invalid_request", "DELETE does not accept a body");
            return;
        }
        DiscoveredTuner tuner = find(exchange, tunerId);
        if(tuner == null)
        {
            return;
        }
        switch(mManager.removeRecordingTuner(tuner))
        {
            case REMOVED -> ApiHttpResponse.sendData(exchange, 200, Map.of("status", "removed"));
            case NOT_FOUND -> ApiHttpResponse.sendError(exchange, 404, "tuner_not_found", "Tuner is no longer available");
            case NOT_RECORDING -> ApiHttpResponse.sendError(exchange, 422, "not_recording_tuner",
                "Only recording tuners can be removed");
            case IN_USE -> ApiHttpResponse.sendError(exchange, 409, "tuner_in_use",
                "Stop channels using this recording tuner before removing it");
            case BUSY -> ApiHttpResponse.sendError(exchange, 409, "tuner_busy",
                "Tuner maintenance is in progress; try again");
            case FAILED -> ApiHttpResponse.sendError(exchange, 503, "tuner_remove_failed",
                "Recording tuner could not be removed");
        }
    }

    private void setEnabled(HttpExchange exchange, String tunerId) throws IOException
    {
        if(!"PUT".equals(exchange.getRequestMethod()))
        {
            WebHttpSupport.methodNotAllowed(exchange, "PUT");
            return;
        }
        DiscoveredTuner tuner = find(exchange, tunerId);
        if(tuner == null)
        {
            return;
        }
        try
        {
            JsonNode request = WebHttpSupport.readJsonObject(exchange, Set.of("enabled"));
            JsonNode enabled = request.get("enabled");
            if(enabled == null || !enabled.isBoolean())
            {
                ApiHttpResponse.sendError(exchange, 400, "invalid_request", "enabled must be a boolean");
                return;
            }
            ApiHttpResponse.sendData(exchange, 202, mSettings.requestEnabled(tuner, enabled.booleanValue()));
        }
        catch(WebHttpSupport.RequestException exception)
        {
            ApiHttpResponse.sendError(exchange, exception.status(), exception.code(), exception.getMessage());
        }
        catch(TunerSettingsService.SettingUnavailableException exception)
        {
            ApiHttpResponse.sendError(exchange, 409, "tuner_busy", exception.getMessage());
        }
        catch(IllegalArgumentException exception)
        {
            ApiHttpResponse.sendError(exchange, 404, "tuner_not_found", "Tuner is no longer available");
        }
        catch(IllegalStateException exception)
        {
            ApiHttpResponse.sendError(exchange, 503, "tuner_maintenance_unavailable", "Tuner maintenance is unavailable");
        }
    }

    private void handleSetting(HttpExchange exchange, String tunerId, String settingId) throws IOException
    {
        String method = exchange.getRequestMethod();
        if(!"PUT".equals(method))
        {
            WebHttpSupport.methodNotAllowed(exchange, "PUT");
            return;
        }
        DiscoveredTuner tuner = find(exchange, tunerId);
        if(tuner == null)
        {
            return;
        }
        try
        {
            JsonNode request = WebHttpSupport.readJsonObject(exchange, Set.of("value"));
            JsonNode raw = request.get("value");
            if(raw == null || !(raw.isTextual() || raw.isNumber() || raw.isBoolean()))
            {
                ApiHttpResponse.sendError(exchange, 400, "invalid_request", "value must be text, number, or boolean");
                return;
            }
            Object value = raw.isTextual() ? raw.textValue() : raw.isBoolean() ? raw.booleanValue() : raw.numberValue();
            ApiHttpResponse.sendData(exchange, 200, mSettings.set(tuner, settingId, value));
        }
        catch(WebHttpSupport.RequestException exception)
        {
            ApiHttpResponse.sendError(exchange, exception.status(), exception.code(), exception.getMessage());
        }
        catch(IllegalArgumentException exception)
        {
            ApiHttpResponse.sendError(exchange, 422, "invalid_tuner_setting", exception.getMessage());
        }
        catch(TunerSettingsService.SettingUnavailableException exception)
        {
            ApiHttpResponse.sendError(exchange, 409, "tuner_setting_unavailable", exception.getMessage());
        }
        catch(IllegalStateException exception)
        {
            ApiHttpResponse.sendError(exchange, 503, "tuner_maintenance_unavailable", "Tuner maintenance is unavailable");
        }
    }

    private void setState(HttpExchange exchange, String tunerId) throws IOException
    {
        if(!"PUT".equals(exchange.getRequestMethod()))
        {
            WebHttpSupport.methodNotAllowed(exchange, "PUT");
            return;
        }
        DiscoveredTuner tuner = find(exchange, tunerId);
        if(tuner == null) return;
        try
        {
            JsonNode request = WebHttpSupport.readJsonObject(exchange, Set.of("state"));
            JsonNode raw = request.get("state");
            if(raw == null || !raw.isTextual())
            {
                ApiHttpResponse.sendError(exchange, 400, "invalid_request", "state is required");
                return;
            }
            OperatorState state;
            try
            {
                state = OperatorState.valueOf(raw.textValue().toUpperCase(Locale.ROOT));
            }
            catch(IllegalArgumentException exception)
            {
                ApiHttpResponse.sendError(exchange, 422, "invalid_tuner_state", "Unknown tuner state");
                return;
            }
            ApiHttpResponse.sendData(exchange, 202, mSettings.requestState(tuner, state));
        }
        catch(WebHttpSupport.RequestException exception)
        {
            ApiHttpResponse.sendError(exchange, exception.status(), exception.code(), exception.getMessage());
        }
        catch(TunerSettingsService.SettingUnavailableException exception)
        {
            ApiHttpResponse.sendError(exchange, 409, "tuner_busy", exception.getMessage());
        }
        catch(IllegalArgumentException exception)
        {
            ApiHttpResponse.sendError(exchange, 404, "tuner_not_found", "Tuner is no longer available");
        }
        catch(IllegalStateException exception)
        {
            ApiHttpResponse.sendError(exchange, 503, "tuner_maintenance_unavailable", "Tuner unavailable");
        }
    }

    private void restoreChannels(HttpExchange exchange, String tunerId) throws IOException
    {
        if(!"POST".equals(exchange.getRequestMethod()))
        {
            WebHttpSupport.methodNotAllowed(exchange, "POST");
            return;
        }
        if(WebHttpSupport.hasRequestBody(exchange))
        {
            ApiHttpResponse.sendError(exchange, 400, "invalid_request", "Restore does not accept a body");
            return;
        }
        DiscoveredTuner tuner = find(exchange, tunerId);
        if(tuner == null) return;
        try
        {
            ApiHttpResponse.sendData(exchange, 202, mSettings.restoreStoppedChannels(tuner));
        }
        catch(TunerSettingsService.SettingUnavailableException exception)
        {
            ApiHttpResponse.sendError(exchange, 409, "tuner_restore_unavailable", exception.getMessage());
        }
        catch(IllegalArgumentException exception)
        {
            ApiHttpResponse.sendError(exchange, 404, "tuner_not_found", "Tuner is no longer available");
        }
        catch(IllegalStateException exception)
        {
            ApiHttpResponse.sendError(exchange, 503, "tuner_maintenance_unavailable", "Tuner unavailable");
        }
    }

    private DiscoveredTuner find(HttpExchange exchange, String tunerId) throws IOException
    {
        DiscoveredTuner tuner = mAdministration.find(tunerId);
        if(tuner == null)
        {
            ApiHttpResponse.sendError(exchange, 404, "tuner_not_found", "Tuner is no longer available");
        }
        return tuner;
    }
}
