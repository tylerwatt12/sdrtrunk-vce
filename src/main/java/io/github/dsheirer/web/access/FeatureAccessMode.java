/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.web.access;

/**
 * Public visibility for a web feature.  ADMIN_ONLY means the feature remains enabled but requires an authenticated
 * administrator.
 */
public enum FeatureAccessMode
{
    PUBLIC,
    ADMIN_ONLY
}
