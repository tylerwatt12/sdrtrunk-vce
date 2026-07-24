/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */
package io.github.dsheirer.record;

import java.nio.file.Path;

/**
 * Non-blocking ownership handoff between the managed recording writer/reconciler and the retention catalog.
 */
public interface RecordedCallCatalogHandoff
{
    /**
     * Indicates whether the catalog can currently accept bounded ownership handoffs.
     */
    boolean isAccepting();

    /**
     * Offers one newly published managed recording to the catalog without waiting.
     */
    boolean submit(RecordedCallArtifact artifact);

    /**
     * Offers one managed recording discovered by bounded filesystem reconciliation without waiting.
     */
    boolean submitRecovery(Path path);
}
