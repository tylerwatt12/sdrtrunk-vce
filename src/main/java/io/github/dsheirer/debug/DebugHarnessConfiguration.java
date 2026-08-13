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
package io.github.dsheirer.debug;

/**
 * Restart-scoped debug harness settings.  The harness is deliberately absent unless explicitly enabled and always
 * binds to the loopback interface, regardless of any normal web-server preference.
 */
public record DebugHarnessConfiguration(boolean enabled, boolean controlsAllowed, int port)
{
    public static final String ENABLED_PROPERTY = "sdrtrunk.debugHarness.enabled";
    public static final String CONTROLS_PROPERTY = "sdrtrunk.debugHarness.allowControls";
    public static final String PORT_PROPERTY = "sdrtrunk.debugHarness.port";
    public static final int DEFAULT_PORT = 8091;

    public DebugHarnessConfiguration
    {
        if(port < 1024 || port > 65535)
        {
            throw new IllegalArgumentException("Debug harness port must be between 1024 and 65535");
        }

        if(controlsAllowed && !enabled)
        {
            throw new IllegalArgumentException("Debug harness controls require the harness to be enabled");
        }
    }

    public static DebugHarnessConfiguration fromSystemProperties()
    {
        boolean enabled = parseBoolean(ENABLED_PROPERTY, false);
        boolean controls = parseBoolean(CONTROLS_PROPERTY, false);
        int port = parsePort(System.getProperty(PORT_PROPERTY));
        return new DebugHarnessConfiguration(enabled, controls, port);
    }

    static int parsePort(String value)
    {
        if(value == null || value.isBlank())
        {
            return DEFAULT_PORT;
        }

        try
        {
            return Integer.parseInt(value.trim());
        }
        catch(NumberFormatException e)
        {
            throw new IllegalArgumentException("Invalid debug harness port: " + value, e);
        }
    }

    private static boolean parseBoolean(String property, boolean defaultValue)
    {
        String value = System.getProperty(property);

        if(value == null || value.isBlank())
        {
            return defaultValue;
        }

        if("true".equalsIgnoreCase(value.trim()))
        {
            return true;
        }

        if("false".equalsIgnoreCase(value.trim()))
        {
            return false;
        }

        throw new IllegalArgumentException("Invalid boolean for -D" + property + ": " + value);
    }
}
