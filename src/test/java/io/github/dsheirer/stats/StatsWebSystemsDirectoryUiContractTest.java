/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Protects the bounded Systems directory adapter and its page lifecycle wiring. */
class StatsWebSystemsDirectoryUiContractTest
{
    private static final Path APP_JAVASCRIPT = Path.of("stats-web", "assets", "app.js");
    private static final Path FEATURE_JAVASCRIPT = Path.of("stats-web", "assets", "features",
        "systems-directory.js");

    @Test
    void loadsOneBoundedCompoundDirectoryRequestAndKeepsParentPagingSeparate() throws Exception
    {
        String feature = readText(FEATURE_JAVASCRIPT);

        assertTrue(feature.contains("const API_PATH = '/api/v1/systems'"));
        assertTrue(feature.contains("const DIRECTORY_LIMIT = 25"));
        assertTrue(feature.contains("async function load(apiPage"));
        assertTrue(feature.contains("const page = await apiPage(API_PATH"));
        assertTrue(feature.contains("includeSitePreview: true"));
        assertTrue(feature.contains("return decode(page)"));
        assertFalse(feature.contains("fetch("));
        assertTrue(feature.contains("!Array.isArray(system.site_preview)"));
        assertTrue(feature.contains("const parentRows = page.rows"));
        assertTrue(feature.contains("tableRows.push({ ...system, directory_type: 'system' })"));
        assertTrue(feature.contains("tableRows.push({ ...site, directory_type: 'site' })"));
        assertTrue(feature.contains("site_preview_truncated"));
        assertTrue(feature.contains("site_preview_limit_per_system"));
        assertFalse(feature.contains("requiredInteger"));
        assertFalse(feature.contains("nextOffset <= offset"));
        assertTrue(feature.contains("window.sdrtrunkSystemsDirectory = Object.freeze({ load, decode })"));
    }

    @Test
    void mountsLoadingAndLocalRetryBeforeAwaitingTheDirectory() throws Exception
    {
        String app = readText(APP_JAVASCRIPT);
        String systems = function(app, "async function renderSystems()");
        String presenter = function(app, "function systemsDirectoryContent(data)");

        assertTrue(systems.contains("createAsyncSection('System Directory'"));
        assertTrue(systems.contains("window.sdrtrunkSystemsDirectory.load(apiPage, pageParameters())"));
        assertTrue(systems.indexOf("beginPage(renderContext") < systems.indexOf("await directory.load("));
        assertTrue(presenter.contains("pager(page, 'bottom', 'Systems')"));
        assertFalse(systems.contains("Promise.all"));
        assertFalse(systems.contains("systemApiPath(system.scope_token, 'sites')"));
    }

    @Test
    void keepsSiteDetailsUnderSystemsNavigation() throws Exception
    {
        String app = readText(APP_JAVASCRIPT);
        String navigation = function(app, "function activateNavigation(view)");
        String render = function(app, "async function render()");

        assertTrue(navigation.contains("['system', 'talkgroup', 'radio', 'site']"));
        assertFalse(navigation.contains("view === 'site' ? 'sites'"));
        int effectiveView = render.indexOf("effectiveView = handlers[view] ? view : 'dashboard'");
        assertTrue(effectiveView >= 0);
        assertTrue(effectiveView < render.indexOf("activateNavigation(effectiveView)"));
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
