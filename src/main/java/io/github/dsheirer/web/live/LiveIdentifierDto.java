/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.web.live;

import io.github.dsheirer.identifier.Identifier;

/**
 * Renderer-neutral identifier carried by the transient live activity API.
 */
public record LiveIdentifierDto(String identifierClass, String form, String role, String value)
{
    static final int MAXIMUM_VALUE_CHARACTERS = 160;

    public static LiveIdentifierDto from(Identifier<?> identifier)
    {
        if(identifier == null)
        {
            return null;
        }

        Object rawValue = identifier.getValue();
        return new LiveIdentifierDto(label(identifier.getIdentifierClass()), label(identifier.getForm()),
            label(identifier.getRole()), LiveText.normalize(rawValue != null ? rawValue.toString() : "",
                MAXIMUM_VALUE_CHARACTERS));
    }

    private static String label(Object value)
    {
        return value != null ? value.toString() : "";
    }
}
