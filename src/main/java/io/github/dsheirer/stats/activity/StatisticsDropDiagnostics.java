/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.stats.activity;

import java.util.Map;

/** Bounded aggregate diagnostics; older cumulative drops remain explicitly unclassified. */
public record StatisticsDropDiagnostics(long writerQueueOverflow, long constraintRejection,
                                       long observationQueueOverflow, long unclassified,
                                       long lastWriterQueueOverflowMs, long lastConstraintRejectionMs,
                                       long lastObservationQueueOverflowMs, Map<String,Long> writerRecordCategories)
{
    public StatisticsDropDiagnostics
    {
        writerRecordCategories = Map.copyOf(writerRecordCategories);
    }

    public StatisticsDropDiagnostics(long writerQueueOverflow, long constraintRejection,
                                     long observationQueueOverflow, long unclassified,
                                     long lastWriterQueueOverflowMs, long lastConstraintRejectionMs,
                                     long lastObservationQueueOverflowMs)
    {
        this(writerQueueOverflow, constraintRejection, observationQueueOverflow, unclassified,
            lastWriterQueueOverflowMs, lastConstraintRejectionMs, lastObservationQueueOverflowMs, Map.of());
    }

    public static final StatisticsDropDiagnostics EMPTY = new StatisticsDropDiagnostics(0, 0, 0, 0, 0, 0, 0);
}
