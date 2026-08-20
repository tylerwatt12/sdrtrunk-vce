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
    private static final Path INDEX_HTML = Path.of("stats-web", "index.html");

    @Test
    void loadsOneBoundedCompoundDirectoryRequestAndKeepsParentPagingSeparate() throws Exception
    {
        String feature = readText(FEATURE_JAVASCRIPT);

        assertTrue(feature.contains("const API_PATH = '/api/v1/systems'"));
        assertTrue(feature.contains("const DIRECTORY_LIMIT = 25"));
        assertTrue(feature.contains("const response = await api(API_PATH"));
        assertTrue(feature.contains("includeSitePreview: true"));
        assertFalse(feature.contains("fetch("));
        assertTrue(feature.contains("!Array.isArray(system.site_preview)"));
        assertTrue(feature.contains("rows: parentRows"));
        assertTrue(feature.contains("tableRows.push({ ...system, directory_type: 'system' })"));
        assertTrue(feature.contains("tableRows.push({ ...site, directory_type: 'site' })"));
        assertTrue(feature.contains("site_preview_truncated"));
        assertTrue(feature.contains("site_preview_limit_per_system"));
        assertTrue(feature.contains("nextOffset <= offset"));
        assertTrue(feature.contains("window.sdrtrunkSystemsDirectory = Object.freeze({ load, decode })"));
    }

    @Test
    void mountsLoadingAndLocalRetryBeforeAwaitingTheDirectory() throws Exception
    {
        String app = readText(APP_JAVASCRIPT);
        String systems = function(app, "async function renderSystems()");

        assertTrue(systems.indexOf("Loading systems and sites…") <
            systems.indexOf("await window.sdrtrunkSystemsDirectory.load"));
        assertTrue(systems.contains("directoryBody.replaceChildren(...rendered)"));
        assertTrue(systems.contains("error?.name === 'AbortError'"));
        assertTrue(systems.contains("error?.status === 401 || error?.status === 403"));
        assertTrue(systems.contains("retry.addEventListener('click', () => render())"));
        assertTrue(systems.contains("pager(page, 'bottom', 'Systems')"));
        assertFalse(systems.contains("Promise.all"));
        assertFalse(systems.contains("systemApiPath(system.scope_token, 'sites')"));
    }

    @Test
    void loadsTheFeatureBeforeTheApplicationAndKeepsSiteDetailsUnderSystemsNavigation() throws Exception
    {
        String html = readText(INDEX_HTML);
        String app = readText(APP_JAVASCRIPT);
        String navigation = function(app, "function activateNavigation(view)");
        String render = function(app, "async function render()");

        assertTrue(html.indexOf("/assets/features/systems-directory.js?v=1") <
            html.indexOf("/assets/app.js?v=102"));
        assertTrue(navigation.contains("['system', 'talkgroup', 'radio', 'site']"));
        assertFalse(navigation.contains("view === 'site' ? 'sites'"));
        assertTrue(render.indexOf("const effectiveView = handlers[view] ? view : 'dashboard'") <
            render.indexOf("activateNavigation(effectiveView)"));
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
