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

/** Protects browser wiring around the behavior-tested collection and page lifecycle helper. */
class StatsWebPageLifecycleUiContractTest
{
    private static final Path APP_JAVASCRIPT = Path.of("stats-web", "assets", "app.js");
    private static final Path INDEX_HTML = Path.of("stats-web", "index.html");

    @Test
    void loadsOneModuleEntrypointWithExplicitLifecycleImports() throws Exception
    {
        String html = readText(INDEX_HTML);
        String source = readText(APP_JAVASCRIPT);
        int lifecycle = source.indexOf("import * as pageLifecycle from './core/page-lifecycle.js';");
        int systems = source.indexOf("import * as systemsDirectory from './features/systems-directory.js';");
        int application = html.indexOf("<script type=\"module\" src=\"/assets/app.js?v=126\"></script>");

        assertTrue(lifecycle >= 0);
        assertTrue(lifecycle < systems);
        assertTrue(application >= 0);
        assertFalse(html.contains("<script src=\"/assets/core/page-lifecycle.js"));
        assertFalse(html.contains("<script src=\"/assets/features/systems-directory.js"));
        assertTrue(html.contains("class=\"content\" aria-live=\"polite\" aria-busy=\"true\""));
        assertTrue(html.contains("<div class=\"loading\" role=\"status\">Loading</div>"));
    }

    @Test
    void keepsTheRootLoaderMountedAndRejectsStalePageCommits() throws Exception
    {
        String source = readText(APP_JAVASCRIPT);
        String render = function(source, "async function render()");
        String beginPage = function(source, "function beginPage(renderContext, ...children)");

        assertOrdered(render, "if (!closeReadOnlyModal()) return;", "const epoch = ++activeRenderEpoch;");
        assertOrdered(render, "content.replaceChildren(loading);", "await entry.handler();");
        assertFalse(render.contains("content.replaceChildren();"));
        assertOrdered(render, "await refreshAccessSession(false);", "if (!renderIsCurrent(renderContext)) return;");
        assertOrdered(beginPage, "content.replaceChildren(...children);",
            "content.setAttribute('aria-busy', 'false');");

        String system = function(source, "async function renderSystem()");
        assertOrdered(system, "const response = await api(systemApiPath(systemScope.scope));",
            "if (!renderIsCurrent(renderContext)) return;");
        assertOrdered(system, "if (!renderIsCurrent(renderContext)) return;", "window.history.replaceState");

        String siteSettings = function(source, "async function renderSiteSettings()");
        assertOrdered(siteSettings, "await renderAdminSiteBehaviorSettings();",
            "if (!renderIsCurrent(renderContext)) return;");
        assertOrdered(siteSettings, "if (!renderIsCurrent(renderContext)) return;",
            "await renderAdminRadioReferenceSettings();");
    }

    @Test
    void closesResponsiveNavigationBeforeEverySpaRouteCommit() throws Exception
    {
        String source = readText(APP_JAVASCRIPT);
        String render = function(source, "async function render()");
        String popState = function(source, "window.addEventListener('popstate', () =>");

        assertOrdered(render, "setNavigationOpen(false);", "const view = routeFoundation.requestedView(route);");
        assertOrdered(popState, "setNavigationOpen(false);", "const previous = `/?${route.toString()}`;");
    }

    @Test
    void providesOneAccessibleLocalLifecycleAndValidatedPageBoundary() throws Exception
    {
        String source = readText(APP_JAVASCRIPT);
        String helper = function(source, "function createAsyncSection(title, options = {})");
        String apiPage = function(source, "async function apiPage(path, parameters = {}, options = {})");

        assertTrue(helper.contains("pageLifecycle.run"));
        assertTrue(helper.contains("renderIsCurrent(renderContext) && host.isConnected"));
        assertTrue(helper.contains("host.setAttribute('role', 'region')"));
        assertTrue(helper.contains("host.setAttribute('aria-label', title)"));
        assertTrue(helper.contains("loading.setAttribute('role', 'status')"));
        assertTrue(helper.contains("failure.querySelector('.async-section-retry')?.focus()"));
        assertOrdered(helper, "replaceAsyncContent(host, present(value));",
            "host.setAttribute('aria-busy', 'false');");
        assertTrue(apiPage.contains("pageLifecycle.decodeOffsetPage(response, path)"));
    }

    @Test
    void preventsObsoleteTimersAndStreamsFromEscapingRouteCleanup() throws Exception
    {
        String source = readText(APP_JAVASCRIPT);
        String signalHealth = function(source, "async function signalHealthSection()");
        String siteSignal = function(source, "async function siteSignalHistorySection(site)");
        String talkgroupHistory = function(source, "async function talkgroupActivityHistorySection(scopeParameters)");
        String activity = function(source, "async function renderActivity(scopeParameters, title = 'Activity')");
        String timeout = function(source, "function pageTimeout(callback, delay)");
        String close = function(source, "function closePageConnections()");
        String qualityChart = function(source, "function qualityHistoryChart(site, response, metric, domain)");

        assertOrdered(signalHealth, "await loadCurrent(true, true);",
            "if (renderIsCurrent(renderContext)) pageInterval(loadCurrent, 10_000);");
        assertOrdered(siteSignal, "await load(rangeControl.buttons, true, true);",
            "if (renderIsCurrent(renderContext)) pageInterval(load, 30_000);");
        assertOrdered(talkgroupHistory, "await load(rangeControl.buttons, true, true);",
            "if (renderIsCurrent(renderContext)) pageInterval(load, 30_000);");
        assertOrdered(activity, "const data = await api('/api/v1/activity'",
            "if (!renderIsCurrent(renderContext)) return;");
        assertOrdered(activity, "if (!renderIsCurrent(renderContext)) return;",
            "pageInterval(refreshTick, 1_000)");
        assertTrue(activity.contains("!block.isConnected"));
        assertTrue(activity.contains("document.hidden"));
        assertTrue(timeout.contains("pageTimers.add(timer)"));
        assertTrue(timeout.contains("pageTimers.delete(timer)"));
        assertTrue(close.contains("window.clearTimeout(timer)"));
        assertOrdered(qualityChart, "requestAnimationFrame(() => {", "if (!wrapper.isConnected) return;");
        assertOrdered(qualityChart, "if (!wrapper.isConnected) return;", "pageObservers.set(observer, wrapper)");
    }

    private static void assertOrdered(String source, String first, String second)
    {
        int firstIndex = source.indexOf(first);
        int secondIndex = source.indexOf(second);
        assertTrue(firstIndex >= 0, () -> "Missing " + first);
        assertTrue(secondIndex >= 0, () -> "Missing " + second);
        assertTrue(firstIndex < secondIndex, () -> first + " must precede " + second);
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
