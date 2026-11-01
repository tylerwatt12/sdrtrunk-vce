/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 * ****************************************************************************
 */

package io.github.dsheirer.audio.call;

/**
 * Authoritative encryption state for one physical call leg.
 */
public enum CallEncryptionState
{
    UNKNOWN(0),
    CLEAR(1),
    ENCRYPTED(2);

    private final int mCode;

    CallEncryptionState(int code)
    {
        mCode = code;
    }

    public int code()
    {
        return mCode;
    }

    public static CallEncryptionState fromCode(int code)
    {
        return switch(code)
        {
            case 0 -> UNKNOWN;
            case 1 -> CLEAR;
            case 2 -> ENCRYPTED;
            default -> throw new IllegalArgumentException("Unknown call encryption state code: " + code);
        };
    }

    public boolean isKnown()
    {
        return this != UNKNOWN;
    }

    public boolean isClear()
    {
        return this == CLEAR;
    }

    public boolean isEncrypted()
    {
        return this == ENCRYPTED;
    }

    public static CallEncryptionState fromEncrypted(boolean encrypted)
    {
        return encrypted ? ENCRYPTED : CLEAR;
    }
}
