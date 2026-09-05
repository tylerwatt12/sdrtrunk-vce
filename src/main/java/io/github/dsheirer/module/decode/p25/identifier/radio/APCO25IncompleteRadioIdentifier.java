/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.module.decode.p25.identifier.radio;

import io.github.dsheirer.identifier.IncompleteIdentifier;
import io.github.dsheirer.identifier.Role;

/**
 * P25 working radio address whose complete home identity is not yet known.
 */
public final class APCO25IncompleteRadioIdentifier extends APCO25RadioIdentifier implements IncompleteIdentifier
{
    private APCO25IncompleteRadioIdentifier(int workingAddress, Role role)
    {
        super(workingAddress, role);
    }

    public static APCO25IncompleteRadioIdentifier createTo(int workingAddress)
    {
        return new APCO25IncompleteRadioIdentifier(workingAddress, Role.TO);
    }
}
