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

package io.github.dsheirer.stats;

/**
 * Canonical route names for the first supported Stats Web API.
 *
 * <p>Every machine-consumable receiver endpoint lives below one versioned root.  Static web assets are the only
 * intentionally unversioned HTTP surface.</p>
 */
public final class StatsApiV1
{
    public static final String ROOT = "/api/v1";
    public static final String STATUS = ROOT + "/status";
    public static final String DASHBOARD = ROOT + "/dashboard";
    public static final String QUALITY = ROOT + "/quality";
    public static final String ALIAS_LISTS = ROOT + "/alias-lists";
    public static final String ALIASES = ROOT + "/aliases";
    public static final String SCAN_LISTS = ROOT + "/scan-lists";
    public static final String SYSTEMS = ROOT + "/systems";
    public static final String SITES = ROOT + "/sites";
    public static final String ACTIVITY = ROOT + "/activity";
    public static final String CONVENTIONAL_CONTEXTS = ROOT + "/conventional-contexts";
    public static final String EXPORTS = ROOT + "/exports";
    public static final String TUNER_DIAGNOSTICS = ROOT + "/diagnostics/tuners";
    public static final String LIVE_MULTIPLEX = ROOT + "/live/multiplex";
    public static final String LIVE_MULTIPLEX_CONTROL = ROOT + "/live/multiplex/control";
    public static final String CALLS = ROOT + "/calls";

    private StatsApiV1()
    {
    }
}
