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

/** Protects the shared navigation, compact header, and playback-popover contracts. */
class StatsWebNavigationHeaderUiContractTest
{
    private static final Path APP_JAVASCRIPT = Path.of("stats-web", "assets", "app.js");
    private static final Path WEB_CALL_PLAYER = Path.of("stats-web", "assets", "web-call-player.js");
    private static final Path APP_CSS = Path.of("stats-web", "assets", "app.css");
    private static final Path INDEX_HTML = Path.of("stats-web", "index.html");

    @Test
    void exposesCompleteNavigationWithDistinctPurposeBuiltIcons() throws Exception
    {
        String html = readText(INDEX_HTML);

        assertTrue(html.contains("<meta name=\"sdrtrunk-web-revision\" content=\"108\">"));
        assertTrue(html.contains("/assets/app.css?v=90"));
        assertFalse(html.contains("/assets/web-call-player.js"));
        assertTrue(html.contains("<script type=\"module\" src=\"/assets/app.js?v=130\"></script>"));
        assertTrue(html.contains("id=\"icon-recording\""));
        assertTrue(html.contains("id=\"icon-streaming\""));
        assertTrue(html.contains("data-nav-tab=\"recording\" href=\"/?view=configuration&amp;tab=recording\""));
        assertTrue(html.contains("data-nav-tab=\"streaming\" href=\"/?view=configuration&amp;tab=streaming\""));
        assertTrue(html.contains("<span>RadioReference</span><small>Coming soon</small>"));
        assertTrue(html.contains("<use href=\"#icon-recording\"></use>"));
        assertTrue(html.contains("<use href=\"#icon-streaming\"></use>"));
        String conventional = fragment(html, "<symbol id=\"icon-conventional\"", "</symbol>");
        assertTrue(conventional.contains("M4 18a8 8 0 0 1 16 0"));
        assertTrue(conventional.contains("M12 18l4-5"));
        assertFalse(conventional.contains("<circle cx=\"12\" cy=\"13\" r=\"7\""));
    }

    @Test
    void keepsRestrictedNavigationVisibleAndMarksEachLockedDestination() throws Exception
    {
        String html = readText(INDEX_HTML);
        String source = readText(APP_JAVASCRIPT);
        String access = block(source, "function updateNavigationAccess()");

        int position = 0;
        int links = 0;
        while((position = html.indexOf("<a data-view=", position)) >= 0)
        {
            int end = html.indexOf("</a>", position);
            assertTrue(end > position);
            String link = html.substring(position, end);
            assertTrue(link.contains("class=\"nav-lock\""));
            assertFalse(html.substring(position, html.indexOf('>', position)).contains(" hidden"));
            links++;
            position = end + 4;
        }
        assertTrue(links >= 10);
        assertFalse(html.contains("nav-group-protected"));
        assertTrue(access.contains("link.classList.toggle('access-locked', locked)"));
        assertTrue(access.contains("lock.hidden = !locked"));
        assertFalse(access.contains("link.hidden"));
        assertFalse(access.contains("group.hidden"));
    }

    @Test
    void switchesToAnAccessibleDrawerBeforeTheDesktopHeaderCollides() throws Exception
    {
        String source = readText(APP_JAVASCRIPT);
        String css = readText(APP_CSS);
        String setOpen = block(source, "function setNavigationOpen(open, returnFocus = false)");
        String accessibility = block(source, "function synchronizeNavigationAccessibility(");
        String focusTargets = block(source, "function drawerNavigationFocusTargets(");
        String initialize = block(source, "function initializeNavigation()");
        String activate = block(source, "function activateNavigation(view)");

        assertTrue(source.contains("const NAVIGATION_DRAWER_MEDIA = '(max-width: 1180px)'"));
        assertTrue(source.contains("const NAVIGATION_HOVER_MEDIA = '(min-width: 1181px) and (hover: hover)'"));
        assertTrue(css.contains("@media (max-width: 1180px)"));
        assertTrue(css.contains("@media (min-width: 1181px) and (max-width: 1250px)"));
        assertFalse(css.contains("@media (max-width: 980px)"));
        assertFalse(css.contains("@media (min-width: 981px)"));

        assertTrue(accessibility.contains("navigation.toggleAttribute('inert', hiddenDrawer)"));
        assertTrue(accessibility.contains("navigation.setAttribute('aria-hidden', String(hiddenDrawer))"));
        assertTrue(focusTargets.contains("[toggle, ...navigation.querySelectorAll"));
        assertTrue(focusTargets.contains("control.getClientRects().length > 0"));
        assertTrue(setOpen.contains("firstUsableNavigationControl(navigation)?.focus()"));
        assertTrue(setOpen.contains("else if (returnFocus && navigationUsesDrawer())"));
        assertTrue(setOpen.contains("toggle.focus()"));
        assertTrue(initialize.contains("setNavigationOpen(!open, open)"));
        assertTrue(initialize.contains("setNavigationOpen(false, true)"));
        assertTrue(initialize.contains("group.addEventListener('pointerenter'"));
        assertTrue(initialize.contains("group.addEventListener('pointerleave'"));
        assertTrue(initialize.contains("group.addEventListener('focusin'"));
        assertTrue(initialize.contains("group.addEventListener('focusout'"));
        assertTrue(initialize.contains("event.key === 'Tab' && drawerMedia.matches"));
        assertTrue(initialize.contains("targets[next].focus()"));
        assertTrue(initialize.contains("event.preventDefault()"));
        assertTrue(initialize.contains("closeNavigationGroups(group)"));
        assertTrue(initialize.contains("const summary = openGroup.querySelector(':scope > summary')"));
        assertTrue(initialize.contains("summary?.focus()"));
        assertTrue(initialize.contains("setNavigationOpen(false, navigationUsesDrawer())"));
        assertTrue(css.contains("body.navigation-open .navigation-toggle"));
        assertTrue(activate.contains("navigationUsesDrawer() ? activeGroup : null"));
        assertFalse(activate.contains("if (active) group.open = true"));
    }

    @Test
    void keepsHealthVisibleStyledAndAlignedWithTheHeaderActions() throws Exception
    {
        String html = readText(INDEX_HTML);
        String source = readText(APP_JAVASCRIPT);
        String css = readText(APP_CSS);
        String indicator = block(source, "  updateIndicator()");

        assertTrue(html.contains("class=\"receiver-health-indicator receiver-health-loading icon-button\""));
        assertTrue(indicator.contains("indicator.classList.remove(`receiver-health-${status}`)"));
        assertTrue(indicator.contains("indicator.classList.add(`receiver-health-${className}`)"));
        assertFalse(indicator.contains("indicator.className"));
        assertTrue(css.contains(".receiver-health-indicator {\n  width: 34px;\n  height: 34px;"));
        assertTrue(css.contains("border-radius: 4px;"));
        assertTrue(css.contains(".theme-toggle {\n  width: 34px;\n  height: 34px;"));
        assertTrue(css.contains(".auth-action {\n  height: 34px;\n  min-height: 34px;"));
        assertFalse(css.contains(".receiver-health-indicator {\n    display: none;"));
    }

    @Test
    void makesOnlyCompactPlaybackPanelsExclusive() throws Exception
    {
        String source = readText(APP_JAVASCRIPT);
        String player = readText(WEB_CALL_PLAYER);
        String header = block(source, "function initializePlaybackHeader()");
        String placement = block(source, "function placePlaybackBar()");
        String controls = block(player, "  bindControls()");

        assertTrue(header.contains("querySelectorAll('details:not(.playback-control-menu)')"));
        assertTrue(header.contains("bar.classList.contains('scanner-expanded')"));
        assertTrue(header.contains("if (other !== panel) other.open = false"));
        assertTrue(header.contains("if (panel.open && !panel.contains(event.target)) panel.open = false"));
        assertTrue(header.contains("controlMenu.open = !navigationUsesDrawer()"));
        assertTrue(header.contains("controlMenu?.open && !controlMenu.contains(event.target)"));
        assertTrue(placement.contains("panel.open = Boolean(scannerHost)"));
        assertTrue(controls.contains("panel.closest('.playback-bar')?.classList.contains('scanner-expanded')"));
        assertTrue(controls.contains("if (panel.open && !expanded)"));
    }

    private static String readText(Path path) throws Exception
    {
        assertTrue(Files.isRegularFile(path), () -> "Missing " + path.toAbsolutePath());
        return Files.readString(path).replace("\r\n", "\n").replace('\r', '\n');
    }

    private static String fragment(String source, String startMarker, String endMarker)
    {
        int start = source.indexOf(startMarker);
        assertTrue(start >= 0, () -> "Missing " + startMarker);
        int end = source.indexOf(endMarker, start);
        assertTrue(end >= 0, () -> "Missing " + endMarker);
        return source.substring(start, end + endMarker.length());
    }

    private static String block(String source, String signature)
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
