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

/** Protects the bounded Radio Systems directory and its unified Radio navigation. */
class StatsWebRadioSystemsDirectoryUiContractTest
{
    private static final Path APP_JAVASCRIPT = Path.of("stats-web", "assets", "app.js");
    private static final Path FEATURE_JAVASCRIPT = Path.of("stats-web", "assets", "features",
        "radio-systems-directory.js");
    private static final Path INDEX_HTML = Path.of("stats-web", "index.html");

    @Test
    void loadsOneBoundedRadioSystemPageWithChannelPreviews() throws Exception
    {
        String feature = readText(FEATURE_JAVASCRIPT);

        assertTrue(feature.contains("const API_PATH = '/api/v1/radio-systems'"));
        assertTrue(feature.contains("const DIRECTORY_LIMIT = 25"));
        assertTrue(feature.contains("const page = await apiPage(API_PATH"));
        assertTrue(feature.contains("includeChannelPreview: true"));
        assertTrue(feature.contains("Array.isArray(system.channel_preview)"));
        assertTrue(feature.contains("directory_type: 'radio_system'"));
        assertTrue(feature.contains("directory_type: 'channel'"));
        assertTrue(feature.contains("channel_preview_truncated"));
        assertTrue(feature.contains("channel_preview_limit_per_system"));
        assertFalse(feature.contains("site_preview"));
        assertFalse(feature.contains("scope_token"));
        assertFalse(feature.contains("guid"));
    }

    @Test
    void rendersCurrentRadioSystemNamesAndCanonicalLinks() throws Exception
    {
        String app = readText(APP_JAVASCRIPT);
        String directory = function(app, "async function renderRadioSystems()");
        String presenter = function(app, "function radioSystemsDirectoryContent(data)");

        assertTrue(directory.contains("createAsyncSection('Radio Systems'"));
        assertTrue(directory.contains("radioSystemsDirectory.load(apiPage, pageParameters())"));
        assertTrue(directory.contains("pageHeader('Radio Systems'"));
        assertTrue(presenter.contains("radioSystemLink(row.entity_ref, label)"));
        assertTrue(presenter.contains("channelNameSummary(row)"));
        assertTrue(presenter.contains("row.alias_lists"));
        assertTrue(presenter.contains("pager(page, 'bottom', 'Radio systems')"));
        assertFalse(app.contains("systemApiPath("));
        assertFalse(app.contains("siteApiPath("));
    }

    @Test
    void distinguishesProvenNativeSystemsFromSavedChannelFallbacks() throws Exception
    {
        String app = readText(APP_JAVASCRIPT);
        String label = function(app, "function radioSystemLabel(row)");
        String savedScope = function(app, "function savedChannelScopeLabel(row)");
        String scopeTest = function(app, "function isSavedChannelRadioSystem(row)");
        String details = function(app, "function radioSystemsDirectoryDetails(row)");
        String directory = function(app, "async function renderRadioSystems()");
        String page = function(app, "async function renderRadioSystem()");

        assertTrue(label.contains("if (isSavedChannelRadioSystem(row)) return savedChannelScopeLabel(row)"));
        assertTrue(label.contains("row.model"));
        assertTrue(label.contains("row.network_id"));
        assertTrue(label.contains("row.location_category"));
        assertTrue(label.contains("row.system_id"));
        assertTrue(savedScope.contains("saved channel scope"));
        assertTrue(scopeTest.contains("dmr|nxdn-c|nxdn-d"));
        assertTrue(details.contains("'Scoped to this saved channel'"));
        assertTrue(details.contains("`Network ${identifierNumber(row.network_id)}`"));
        assertTrue(details.contains("`System ${identifierNumber(row.system_id)}`"));
        assertTrue(directory.contains("saved receiver channels that receive them"));
        assertTrue(page.contains("isSavedChannelRadioSystem(system) ?"));
        assertTrue(page.contains("'Saved Channel Activity' : 'System Activity'"));
        assertTrue(page.contains("'Saved Channel Scope' : 'System Info'"));
        assertFalse(directory.contains("kept separate for each saved receiver channel"));
    }

    @Test
    void placesRadioSystemsAndChannelsTogetherUnderRadioNavigation() throws Exception
    {
        String html = readText(INDEX_HTML);
        int group = html.indexOf("data-nav-group=\"radio\"");
        int systems = html.indexOf("data-view=\"radio-systems\"", group);
        int channels = html.indexOf("data-view=\"channels\"", systems);
        int groupEnd = html.indexOf("</details>", group);

        assertTrue(group >= 0);
        assertTrue(systems > group);
        assertTrue(channels > systems);
        assertTrue(groupEnd > channels);
        assertFalse(html.contains("data-view=\"conventional\""));
        assertFalse(html.contains("data-view=\"sites\""));
    }

    private static String readText(Path path) throws Exception
    {
        assertTrue(Files.isRegularFile(path), () -> "Missing " + path.toAbsolutePath());
        return Files.readString(path).replace("\r\n", "\n").replace('\r', '\n');
    }

    private static String function(String source, String signature)
    {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, () -> "Missing " + signature);
        int openingBrace = source.indexOf('{', start + signature.length());
        int depth = 0;

        for(int index = openingBrace; index < source.length(); index++)
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

        throw new AssertionError("Unterminated " + signature);
    }
}
