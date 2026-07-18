/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.gui.bugreport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import org.junit.jupiter.api.Test;

class BugReportUploaderTest
{
    @Test
    void parsesSuccessfulPortalResponse() throws Exception
    {
        BugReportSubmission submission = new BugReportUploader().parseSubmission("""
            {
              "report_code": "VCE-8F3K-2M7Q-9R5C",
              "received_at_utc": "2026-07-18T02:00:00Z",
              "retention_until_utc": "2026-08-17T02:00:00Z"
            }
            """);

        assertEquals("VCE-8F3K-2M7Q-9R5C", submission.reportCode());
        assertEquals("2026-08-17T02:00:00Z", submission.retentionUntilUtc());
    }

    @Test
    void rejectsMissingOrMalformedReportCode()
    {
        assertThrows(IOException.class,
            () -> new BugReportUploader().parseSubmission("{\"report_code\":\"guessable\"}"));
    }
}
