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

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The diagnostic service is shared by the resolver and the web monitor. Its lifetime must include the final
 * recording and streaming output confirmations, even though it no longer owns a JavaFX window.
 */
class LogicalCallDiagnosticLifecycleContractTest
{
    private static final Path APPLICATION = Path.of("src/main/java/io/github/dsheirer/gui/SDRTrunk.java");

    @Test
    void diagnosticServiceReceivesOutputsAndClosesAfterProducersDrain() throws Exception
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
        assertTrue(application.contains("LogicalCallDiagnosticOutputType.RECORDED"));
        assertTrue(application.contains("LogicalCallDiagnosticOutputType.STREAM_SUBMITTED"));
        assertTrue(siteMetadataDrain >= 0 && siteMetadataDrain < coordinatorStop);
        assertTrue(coordinatorStop >= 0 && coordinatorStop < streamingStop && coordinatorStop < recordingStop);
        assertTrue(streamingStop < statisticsStop && streamingStop < serviceClose);
        assertTrue(recordingStop < statisticsStop && recordingStop < serviceClose);
        assertTrue(statisticsStop < serviceClose);
    }
}
