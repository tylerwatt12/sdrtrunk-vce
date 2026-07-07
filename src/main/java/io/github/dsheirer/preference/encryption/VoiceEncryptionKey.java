/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
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

package io.github.dsheirer.preference.encryption;

import io.github.dsheirer.audio.codec.mbe.decrypt.VoiceEncryptionContext;
import java.util.Locale;
import java.util.UUID;

/**
 * User configured voice encryption key.
 */
public class VoiceEncryptionKey
{
    private String mId;
    private String mLabel;
    private boolean mEnabled = true;
    private VoiceEncryptionProtocol mProtocol = VoiceEncryptionProtocol.APCO25;
    private int mAlgorithmId;
    private int mKeyId;
    private String mKeyHex;
    private String mScope;

    public VoiceEncryptionKey()
    {
        mId = UUID.randomUUID().toString();
    }

    public VoiceEncryptionKey(String id)
    {
        mId = id == null || id.isBlank() ? UUID.randomUUID().toString() : id;
    }

    public String getId()
    {
        return mId;
    }

    public void setId(String id)
    {
        mId = id == null || id.isBlank() ? UUID.randomUUID().toString() : id;
    }

    public String getLabel()
    {
        return mLabel;
    }

    public void setLabel(String label)
    {
        mLabel = label;
    }

    public boolean isEnabled()
    {
        return mEnabled;
    }

    public void setEnabled(boolean enabled)
    {
        mEnabled = enabled;
    }

    public VoiceEncryptionProtocol getProtocol()
    {
        return mProtocol;
    }

    public void setProtocol(VoiceEncryptionProtocol protocol)
    {
        mProtocol = protocol == null ? VoiceEncryptionProtocol.APCO25 : protocol;
    }

    public int getAlgorithmId()
    {
        return mAlgorithmId;
    }

    public void setAlgorithmId(int algorithmId)
    {
        mAlgorithmId = algorithmId;
    }

    public String getAlgorithmLabel()
    {
        return VoiceEncryptionAlgorithm.getLabel(getProtocol(), getAlgorithmId());
    }

    public int getKeyId()
    {
        return mKeyId;
    }

    public void setKeyId(int keyId)
    {
        mKeyId = keyId;
    }

    public String getKeyHex()
    {
        return mKeyHex;
    }

    public void setKeyHex(String keyHex)
    {
        mKeyHex = normalizeKeyHex(keyHex);
    }

    public int getKeyByteLength()
    {
        return mKeyHex == null ? 0 : mKeyHex.length() / 2;
    }

    public String getKeyStatus()
    {
        return getKeyByteLength() > 0 ? "Configured (" + getKeyByteLength() + " bytes)" : "Missing";
    }

    public String getScope()
    {
        return mScope;
    }

    public void setScope(String scope)
    {
        mScope = scope;
    }

    public boolean hasScope()
    {
        return mScope != null && !mScope.isBlank();
    }

    /**
     * Matches this configured key to a voice encryption context.
     *
     * A blank scope matches any call.  A non-blank scope is matched as a case-insensitive substring against the
     * context summary so that future protocol-specific system/talkgroup/channel values can be carried without changing
     * the persisted key format.
     */
    public boolean matches(VoiceEncryptionContext context)
    {
        if(context == null || !isEnabled() || getProtocol() != context.getProtocol() ||
            getAlgorithmId() != context.getAlgorithmId() || getKeyId() != context.getKeyId())
        {
            return false;
        }

        if(hasScope())
        {
            String summary = context.getScopeSummary();
            return summary != null && summary.toLowerCase(Locale.ROOT)
                .contains(getScope().toLowerCase(Locale.ROOT).trim());
        }

        return true;
    }

    public VoiceEncryptionKey copy()
    {
        VoiceEncryptionKey copy = new VoiceEncryptionKey(getId());
        copy.setLabel(getLabel());
        copy.setEnabled(isEnabled());
        copy.setProtocol(getProtocol());
        copy.setAlgorithmId(getAlgorithmId());
        copy.setKeyId(getKeyId());
        copy.setKeyHex(getKeyHex());
        copy.setScope(getScope());
        return copy;
    }

    public static String normalizeKeyHex(String keyHex)
    {
        if(keyHex == null)
        {
            return null;
        }

        return keyHex.replaceAll("[\\s:_-]", "").toUpperCase(Locale.ROOT);
    }

    public static boolean isValidHexKey(String keyHex)
    {
        String normalized = normalizeKeyHex(keyHex);
        return normalized != null && !normalized.isBlank() && normalized.length() % 2 == 0 &&
            normalized.matches("[0-9A-F]+");
    }

    @Override
    public String toString()
    {
        String label = getLabel();
        return label == null || label.isBlank() ? getProtocol() + " " + getAlgorithmLabel() + " KEY:" + getKeyId() :
            label;
    }
}
