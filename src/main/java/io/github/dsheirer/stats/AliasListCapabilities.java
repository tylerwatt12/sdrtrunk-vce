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

import java.util.Map;
import java.util.Set;

/** Server-owned capabilities shared by the public inventory and the Alias List editor. */
final class AliasListCapabilities
{
    private AliasListCapabilities()
    {
    }

    static void decorate(Map<String,Object> list)
    {
        String family = String.valueOf(list.get("family"));
        boolean assigned = "NBFM".equals(family);
        list.put("capabilities", Map.of(
            "observed_group_discovery", assigned || Set.of("P25", "DMR", "NXDN").contains(family),
            "configured_assignment", assigned,
            "call_totals", assigned));
    }
}
