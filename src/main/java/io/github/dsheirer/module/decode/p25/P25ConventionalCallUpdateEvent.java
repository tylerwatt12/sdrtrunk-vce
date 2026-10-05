/*
 * Copyright (C) 2026 Dennis Sheirer
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, version 3 or later.
 */
package io.github.dsheirer.module.decode.p25;

/** Full immutable same-tracker snapshot; a missing start notification can be recovered without recounting updates. */
public record P25ConventionalCallUpdateEvent(P25CallStartEvent call, boolean complete)
{
}
