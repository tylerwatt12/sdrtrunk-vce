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
    private static final Path APPLICATION = Path.of("src/main/java/io/github/dsheirer/gui/SDRTrunk.java");

    @Test
    void duplicateHistoryClosesAfterCoordinatorDrainsWithoutFileOrOutputDebugWiring() throws Exception
    {
        String application = Files.readString(APPLICATION);
        int serviceCreation = application.indexOf("new LogicalCallDiagnosticService(");
        int coordinatorCreation = application.indexOf("new AudioCallCoordinator(");
        int siteMetadataDrain = application.indexOf("channelProcessingManager.awaitSiteMetadataDrain(");
        int coordinatorStop = application.indexOf("mAudioCallCoordinator.disposeAndAwait(");
        int streamingStop = application.indexOf("mAudioStreamingManager.stop();");
        int recordingStop = application.indexOf("mAudioRecordingManager.stop();");
        int statisticsStop = application.indexOf("mReceiverActivityService.disposeAndAwait(");
        int serviceClose = application.indexOf("mLogicalCallDiagnosticService.close();");

        assertTrue(serviceCreation >= 0 && serviceCreation < coordinatorCreation);
        assertTrue(application.contains("new LogicalCallDiagnosticService()"));
        assertTrue(application.contains("mReceiverActivityService::receiveRecordedCall"));
        assertTrue(application.contains("mReceiverActivityService::receiveStreamedCall"));
        assertFalse(application.contains("offerDiagnosticOutput"));
        assertTrue(siteMetadataDrain >= 0 && siteMetadataDrain < coordinatorStop);
        assertTrue(coordinatorStop >= 0 && coordinatorStop < streamingStop && coordinatorStop < recordingStop);
        assertTrue(streamingStop < statisticsStop && streamingStop < serviceClose);
        assertTrue(recordingStop < statisticsStop && recordingStop < serviceClose);
        assertTrue(statisticsStop < serviceClose);
    }
}
