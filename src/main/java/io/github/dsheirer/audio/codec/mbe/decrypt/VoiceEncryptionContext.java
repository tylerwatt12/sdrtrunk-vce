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

package io.github.dsheirer.audio.codec.mbe.decrypt;

import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.encryption.EncryptionKeyIdentifier;
import io.github.dsheirer.preference.encryption.VoiceEncryptionProtocol;
import java.util.stream.Collectors;

/**
 * Voice encryption state captured from signaling and call identifiers.
 */
public class VoiceEncryptionContext
{
    private final VoiceEncryptionProtocol mProtocol;
    private final int mAlgorithmId;
    private final Integer mFeatureIdentifier;
    private final int mKeyId;
    private final String mMessageIndicator;
    private final int mTimeslot;
    private final String mScopeSummary;

    public VoiceEncryptionContext(VoiceEncryptionProtocol protocol, int algorithmId, int keyId,
                                  String messageIndicator, int timeslot, IdentifierCollection identifiers)
    {
        this(protocol, algorithmId, keyId, messageIndicator, timeslot, identifiers, null);
    }

    public VoiceEncryptionContext(VoiceEncryptionProtocol protocol, int algorithmId, int keyId,
                                  String messageIndicator, int timeslot, IdentifierCollection identifiers,
                                  Integer featureIdentifier)
    {
        mProtocol = protocol;
        mAlgorithmId = algorithmId;
        mFeatureIdentifier = featureIdentifier;
        mKeyId = keyId;
        mMessageIndicator = messageIndicator;
        mTimeslot = timeslot;
        mScopeSummary = createScopeSummary(identifiers);
    }

    public VoiceEncryptionProtocol getProtocol()
    {
        return mProtocol;
    }

    public int getAlgorithmId()
    {
        return mAlgorithmId;
    }

    /**
     * Optional feature identifier supplied by protocol signaling.
     */
    public Integer getFeatureIdentifier()
    {
        return mFeatureIdentifier;
    }

    public int getKeyId()
    {
        return mKeyId;
    }

    public String getMessageIndicator()
    {
        return mMessageIndicator;
    }

    public int getTimeslot()
    {
        return mTimeslot;
    }

    public String getScopeSummary()
    {
        return mScopeSummary;
    }

    public static VoiceEncryptionContext create(VoiceEncryptionProtocol protocol,
                                                EncryptionKeyIdentifier encryptionKeyIdentifier,
                                                String messageIndicator, int timeslot,
                                                IdentifierCollection identifiers)
    {
        if(encryptionKeyIdentifier == null || encryptionKeyIdentifier.getValue() == null)
        {
            return null;
        }

        return new VoiceEncryptionContext(protocol, encryptionKeyIdentifier.getValue().getAlgorithm(),
            encryptionKeyIdentifier.getValue().getKey(), messageIndicator, timeslot, identifiers);
    }

    private String createScopeSummary(IdentifierCollection identifiers)
    {
        if(identifiers == null || identifiers.isEmpty())
        {
            return "";
        }

        return identifiers.getIdentifiers().stream().map(Identifier::toString).collect(Collectors.joining(" "));
    }
}
