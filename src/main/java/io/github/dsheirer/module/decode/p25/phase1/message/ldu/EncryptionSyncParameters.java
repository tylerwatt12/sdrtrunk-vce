/*
 * *****************************************************************************
 * Copyright (C) 2014-2024 Dennis Sheirer
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

package io.github.dsheirer.module.decode.p25.phase1.message.ldu;

import io.github.dsheirer.audio.codec.mbe.IEncryptionSyncParameters;
import io.github.dsheirer.bits.BinaryMessage;
import io.github.dsheirer.bits.IntField;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.encryption.EncryptionKeyIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.encryption.APCO25EncryptionKey;
import io.github.dsheirer.module.decode.p25.reference.Encryption;
import java.util.ArrayList;
import java.util.List;

/**
 * Encryption Sync Parameters from Logical Link Data Unit 2 voice frame.
 */
public class EncryptionSyncParameters implements IEncryptionSyncParameters
{
    private static final String EMPTY_MESSAGE_INDICATOR = "000000000000000000";
    private static final IntField MESSAGE_INDICATOR_1 = IntField.length8(0);
    private static final IntField MESSAGE_INDICATOR_2 = IntField.length8(8);
    private static final IntField MESSAGE_INDICATOR_3 = IntField.length8(16);
    private static final IntField MESSAGE_INDICATOR_4 = IntField.length8(24);
    private static final IntField MESSAGE_INDICATOR_5 = IntField.length8(32);
    private static final IntField MESSAGE_INDICATOR_6 = IntField.length8(40);
    private static final IntField MESSAGE_INDICATOR_7 = IntField.length8(48);
    private static final IntField MESSAGE_INDICATOR_8 = IntField.length8(56);
    private static final IntField MESSAGE_INDICATOR_9 = IntField.length8(64);
    private static final IntField ALGORITHM_ID = IntField.length8(72);
    private static final IntField KEY_ID = IntField.length16(80);

    private BinaryMessage mMessage;
    private boolean mValid = true;
    private String mMessageIndicator;
    private EncryptionKeyIdentifier mEncryptionKey;
    private List<Identifier> mIdentifiers;

    /**
     * Constructs a Link Control Word from the binary message sequence.
     *
     * @param message
     */
    public EncryptionSyncParameters(BinaryMessage message)
    {
        mMessage = message;
    }

    @Override
    public String toString()
    {
        StringBuilder sb = new StringBuilder();

        if(isValid())
        {
            if(isEncryptedAudio())
            {
                sb.append(getEncryptionKey());
                sb.append(" MSG INDICATOR:").append(getMessageIndicator());
            }
            else
            {
                sb.append("UNENCRYPTED       ");
            }
        }
        else
        {
            sb.append("***CRC-FAIL***");
        }

        return sb.toString();
    }

    private BinaryMessage getMessage()
    {
        return mMessage;
    }

    /**
     * Indicates if this message is valid or not.
     */
    public boolean isValid()
    {
        return mValid;
    }

    /**
     * Flags this message as valid or invalid
     */
    public void setValid(boolean valid)
    {
        mValid = valid;
    }

    public String getMessageIndicator()
    {
        if(mMessageIndicator == null)
        {
            StringBuilder sb = new StringBuilder();
            sb.append(getMessage().getHex(MESSAGE_INDICATOR_1, 2));
            sb.append(getMessage().getHex(MESSAGE_INDICATOR_2, 2));
            sb.append(getMessage().getHex(MESSAGE_INDICATOR_3, 2));
            sb.append(getMessage().getHex(MESSAGE_INDICATOR_4, 2));
            sb.append(getMessage().getHex(MESSAGE_INDICATOR_5, 2));
            sb.append(getMessage().getHex(MESSAGE_INDICATOR_6, 2));
            sb.append(getMessage().getHex(MESSAGE_INDICATOR_7, 2));
            sb.append(getMessage().getHex(MESSAGE_INDICATOR_8, 2));
            sb.append(getMessage().getHex(MESSAGE_INDICATOR_9, 2));
            mMessageIndicator = sb.toString();
        }

        return mMessageIndicator;
    }

    public EncryptionKeyIdentifier getEncryptionKey()
    {
        if(mEncryptionKey == null)
        {
            int algorithm = getMessage().getInt(ALGORITHM_ID);
            int key = getMessage().getInt(KEY_ID);

            //Detect when algorithm, key and MI are all zeros and override algorithm to set as unencrypted.
            if(algorithm == 0 && key == 0 && getMessageIndicator().contains(EMPTY_MESSAGE_INDICATOR))
            {
                algorithm = Encryption.UNENCRYPTED.getValue(); //0x80
            }

            mEncryptionKey = EncryptionKeyIdentifier.create(APCO25EncryptionKey.create(algorithm, key));
        }

        return mEncryptionKey;
    }

    /**
     * Indicates if the audio stream is encrypted
     */
    public boolean isEncryptedAudio()
    {
        return getEncryptionKey().getValue().isEncrypted();
    }

    public List<Identifier> getIdentifiers()
    {
        if(mIdentifiers == null)
        {
            mIdentifiers = new ArrayList<>();

            if(isEncryptedAudio())
            {
                mIdentifiers.add(getEncryptionKey());
            }
        }

        return mIdentifiers;
    }
}
