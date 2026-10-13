/*
 * Copyright (C) 2026 Dennis Sheirer
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.dsheirer.identifier;

/** The evidence behind a resolved identity, independent of entity kind and source/target role. */
public enum IdentityResolutionEvidence
{
    DIRECT, CONFIRMED_ASSIGNMENT, UNRESOLVED, UNKNOWN
}
