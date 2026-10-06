/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.gui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Source guard for the receiver lifecycle that must not require a desktop toolkit. */
class HeadlessReceiverLifecycleContractTest
{
    private static final Path APPLICATION = Path.of("src/main/java/io/github/dsheirer/gui/SDRTrunk.java");
    private static final Path WEB_SERVICE = Path.of("src/main/java/io/github/dsheirer/stats/StatsWebServerService.java");

    @Test
    void headlessStartupAndShutdownAreIndependentOfTheDesktopEventLoop() throws Exception
    {
        String source = Files.readString(APPLICATION).replace("\r\n", "\n");
        String headlessStartup = source.substring(source.indexOf("if(!mGuiAvailable)\n        {\n            mLog.info(\"starting main application headless\")"),
            source.indexOf("private void startPostLaunchExperience()"));
        String shutdown = source.substring(source.indexOf("private synchronized void processShutdown(boolean releaseDataRootLock)"),
            source.indexOf("private void releaseDataRootLock()"));

        assertTrue(source.contains("mGuiAvailable = !GraphicsEnvironment.isHeadless()"));
        assertTrue(headlessStartup.contains("SqlitePreferencesFactory.setShutdownCoordinator(this::processShutdown);\n" +
            "            startPostLaunchExperience();"));
        assertTrue(headlessStartup.contains("else\n        {\n            mLog.info(\"starting main application gui\")"));
        assertTrue(headlessStartup.lastIndexOf("SqlitePreferencesFactory.setShutdownCoordinator(this::processShutdown);") >
            headlessStartup.indexOf("initGUI();"));
        assertTrue(headlessStartup.lastIndexOf("SqlitePreferencesFactory.setShutdownCoordinator(this::processShutdown);") <
            headlessStartup.indexOf("EventQueue.invokeLater"));
        assertTrue(source.contains("mMainGui.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE)"));
        String guiShutdown = source.substring(source.indexOf("private void requestGuiShutdown()"),
            source.indexOf("private void processShutdown()"));
        assertTrue(guiShutdown.indexOf("processShutdown();") < guiShutdown.indexOf("System.exit(0);"));
        assertTrue(guiShutdown.contains("catch(RuntimeException exception)"));
        assertTrue(source.contains("public void windowClosing(WindowEvent e)\n        {\n            requestGuiShutdown();"));
        assertTrue(headlessStartup.indexOf("EventQueue.invokeLater") > headlessStartup.indexOf("else\n        {"));
        assertFalse(headlessStartup.substring(0, headlessStartup.indexOf("else\n        {")).contains("EventQueue.invokeLater"));
        int guiShutdownStart = shutdown.indexOf("if(mGuiAvailable)");
        int desktopUnregister = shutdown.indexOf("MyEventBus.getGlobalEventBus().unregister(this);");
        assertTrue(guiShutdownStart >= 0 && desktopUnregister > guiShutdownStart);
        assertTrue(shutdown.contains("if(mMapSnapshotService != null)\n        {\n            mMapSnapshotService.close();"));
        assertTrue(shutdown.indexOf("mStatsWebServerService.close()") <
            shutdown.indexOf("if(mGuiAvailable)"));
        assertTrue(shutdown.indexOf("ApplicationSettingsStore.flushPendingWritesNow()") >
            shutdown.indexOf("mStatsWebServerService.close()"));
        assertTrue(shutdown.indexOf("ApplicationSettingsStore.flushPendingWritesNow()") <
            shutdown.indexOf("channelProcessingManager.close()"));
        assertTrue(shutdown.indexOf("mStatsWebServerService.close()") <
            shutdown.indexOf("channelProcessingManager.close()"));
        assertTrue(shutdown.contains("mShutdownProcessed = false;"));
        String webService = Files.readString(WEB_SERVICE).replace("\r\n", "\n");
        String webClose = webService.substring(webService.indexOf("public synchronized void close()"),
            webService.indexOf("static final class MultiplexOutput"));
        assertTrue(webClose.indexOf("mTunerSettingsService.close()") < webClose.indexOf("mClosed = true"));
        assertFalse(source.contains("new MapService("));
        assertFalse(source.contains("new ControllerPanel("));
    }
}
