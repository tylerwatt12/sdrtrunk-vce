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

package io.github.dsheirer.gui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The diagnostic service is shared by the resolver and the web monitor. It must outlive the coordinator's final
 * duplicate decisions, but it must not subscribe to output confirmations or create a file-debug path.
 */
class LogicalCallDiagnosticLifecycleContractTest
{
    private static final Path RUNTIME = Path.of("src/main/java/io/github/dsheirer/receiver/ReceiverRuntime.java");

    @Test
    void duplicateHistoryClosesAfterCoordinatorDrainsWithoutFileOrOutputDebugWiring() throws Exception
    {
        String application = Files.readString(RUNTIME);
        int serviceCreation = application.indexOf("new LogicalCallDiagnosticService(");
        int coordinatorCreation = application.indexOf("new AudioCallCoordinator(");
        int siteMetadataDrain = application.indexOf("processing.awaitSiteMetadataDrain(");
        int coordinatorStop = application.indexOf("mCalls.disposeAndAwait(");
        int streamingStop = application.indexOf("mStreaming::stop");
        int recordingStop = application.indexOf("mRecording::stop");
        int statisticsStop = application.indexOf("mActivity.disposeAndAwait(");
        int serviceClose = application.indexOf("mCallDiagnostics::close");

        assertTrue(serviceCreation >= 0 && serviceCreation < coordinatorCreation);
        assertTrue(application.contains("new LogicalCallDiagnosticService()"));
        assertTrue(application.contains("mActivity::receiveRecordedCall"));
        assertTrue(application.contains("mActivity::receiveStreamedCall"));
        assertFalse(application.contains("offerDiagnosticOutput"));
        assertTrue(siteMetadataDrain >= 0 && siteMetadataDrain < coordinatorStop);
        assertTrue(coordinatorStop >= 0 && coordinatorStop < streamingStop && coordinatorStop < recordingStop);
        assertTrue(streamingStop < statisticsStop && streamingStop < serviceClose);
        assertTrue(recordingStop < statisticsStop && recordingStop < serviceClose);
        assertTrue(statisticsStop < serviceClose);
    }
}
