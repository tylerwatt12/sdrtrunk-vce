/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.gui.bugreport;

import java.awt.image.BufferedImage;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * User-supplied problem description and screenshots captured or selected after consent.
 */
public record BugReportRequest(String summary, String description, String reproductionSteps,
                               ZonedDateTime consentedAt, BufferedImage applicationScreenshot,
                               List<BufferedImage> additionalScreenshots)
{
    public BugReportRequest
    {
        additionalScreenshots = List.copyOf(additionalScreenshots);
    }
}
