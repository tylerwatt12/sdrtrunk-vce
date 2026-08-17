/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class StatsWebReceiverLocationAndFrequencyActionsUiContractTest
{
    private static final Path APP_JAVASCRIPT = Path.of("stats-web", "assets", "app.js");
    private static final Path APP_CSS = Path.of("stats-web", "assets", "app.css");

    @Test
    void configuresOneReceiverLocationFromBrowserOrManualCoordinates() throws Exception
    {
        String source = Files.readString(APP_JAVASCRIPT);
        String settings = block(source, "async function renderAdminSettings()");
        String admin = block(source, "async function renderAdmin()");
        String viewAllowed = block(source, "function viewAllowed(view)");

        assertTrue(source.contains("ADMIN_SETTINGS: 'admin-settings'"));
        assertTrue(viewAllowed.contains("capabilityAllowed(ACCESS_CAPABILITIES.ADMIN_SETTINGS)"));
        assertTrue(admin.contains("id: 'settings', label: 'Web Settings'"));
        assertTrue(admin.contains("ACCESS_CAPABILITIES.ADMIN_SETTINGS"));
        assertTrue(admin.contains("await renderAdminSettings()"));
        assertTrue(settings.contains("requestJson('/api/v1/admin/receiver-location', { csrf: false })"));
        assertTrue(settings.contains("method: 'PUT'"));
        assertTrue(settings.contains("method: 'DELETE'"));
        assertTrue(settings.contains("navigator.geolocation.getCurrentPosition"));
        assertTrue(settings.contains("window.isSecureContext"));
        assertTrue(settings.contains("Use Browser Location"));
        assertTrue(settings.contains("Save Receiver Location"));
        assertTrue(settings.contains("receiverLocationField('latitude', 'Latitude', -90, 90"));
        assertTrue(settings.contains("receiverLocationField('longitude', 'Longitude', -180, 180"));
        assertTrue(settings.contains("enableHighAccuracy: false"));
        assertTrue(settings.contains("maximumAge: 300_000"));
    }

    @Test
    void opensOneFutureProofFrequencyActionModalWithoutTurningClicksIntoNetworkPolling() throws Exception
    {
        String source = Files.readString(APP_JAVASCRIPT);
        String tuner = block(source, "function tunerSpectrumPanel()");
        String actions = block(source, "function openTunerFrequencyActions(selection)");
        String handoff = block(source, "function openRadioReferenceFrequencyQuery(frequencyHz)");
        String pointerUp = block(tuner, "function onPlotPointerUp(event)");
        String pointerCancel = block(tuner, "function onPlotPointerCancel(event)");

        assertTrue(actions.contains("'RadioReference Lookup'"));
        assertTrue(actions.contains("'Listen'"));
        assertTrue(actions.contains("'Add System'"));
        assertTrue(actions.contains("true);"));
        assertTrue(actions.contains("openReadOnlyModal('Frequency actions'"));
        assertTrue(source.contains(
            "RADIO_REFERENCE_FREQUENCY_QUERY_URL = 'https://www.radioreference.com/db/query/'"));
        assertTrue(handoff.contains("['qFreq'"));
        assertTrue(handoff.contains("['stids[]', 'dbWide']"));
        assertTrue(handoff.contains("['a', 'queryFreqState']"));
        assertTrue(handoff.contains("popup.opener = null"));
        assertTrue(tuner.contains("function frequencySelectionAtPointer(event)"));
        assertTrue(source.contains("Click a frequency to choose an action."));
        assertTrue(tuner.contains("rawFrequencyHz"));
        assertTrue(tuner.contains("frequencyHz: snap?.frequencyHz ?? rawFrequencyHz"));
        assertTrue(tuner.contains("activeCarrier: carrier"));
        assertTrue(tuner.contains("canvas.addEventListener('click', onPlotClick)"));
        assertTrue(tuner.contains("flag.addEventListener('click'"));
        assertTrue(pointerUp.contains("if (moved) queueViewportUpdate();"));
        assertTrue(pointerUp.contains("else openFrequencyActionsAtPointer(event);"));
        assertFalse(pointerCancel.contains("openFrequencyActionsAtPointer"));
        assertFalse(tuner.contains("updateCursor(ratio).then"));
        assertFalse(tuner.contains("acceptTunerFrame(frame).then"));
    }

    @Test
    void stylesResponsiveLocationAndDisabledFutureActions() throws Exception
    {
        String css = Files.readString(APP_CSS);
        assertTrue(css.contains(".receiver-location-form"));
        assertTrue(css.contains(".read-only-modal.frequency-action-modal"));
        assertTrue(css.contains(".tuner-frequency-action-list"));
        assertTrue(css.contains(".tuner-frequency-action.disabled-action"));
        assertTrue(css.contains("color: var(--muted);"));
    }

    private static String block(String source, String marker)
    {
        int start = source.indexOf(marker);
        assertTrue(start >= 0, marker);
        int opening = source.indexOf('{', start);
        int depth = 0;

        for(int index = opening; index < source.length(); index++)
        {
            char character = source.charAt(index);

            if(character == '{')
            {
                depth++;
            }
            else if(character == '}' && --depth == 0)
            {
                return source.substring(start, index + 1);
            }
        }

        throw new AssertionError("Unclosed block: " + marker);
    }
}
