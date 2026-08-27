/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */

package io.github.dsheirer.audio.call;

import com.fasterxml.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.identifier.encryption.EncryptionKeyIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.encryption.APCO25EncryptionKey;
import org.junit.jupiter.api.Test;

class CallEncryptionEvidenceTest
{
    @Test
    void matchingMessageIndicatorIsStrongPositiveAcrossDifferentSiteKeys()
    {
        String messageIndicator = "001122334455667788";
        CallEncryptionEvidence first = CallEncryptionEvidence.capture(encryptionKey(0x84, 0x1001),
            messageIndicator);
        CallEncryptionEvidence second = CallEncryptionEvidence.capture(encryptionKey(0x81, 0x2002),
            messageIndicator);
        CallEncryptionEvidence different = CallEncryptionEvidence.capture(encryptionKey(0x84, 0x1001),
            "881122334455667700");
        CallEncryptionEvidence missing = CallEncryptionEvidence.capture(encryptionKey(0x84, 0x1001), null);

        assertTrue(first.hasMatchingMessageIndicator(second));
        assertFalse(first.hasMatchingMessageIndicator(different));
        assertFalse(first.hasMatchingMessageIndicator(missing));
        assertNotEquals(first.messageIndicatorFingerprint(), different.messageIndicatorFingerprint());
    }

    @Test
    void diagnosticAndJsonProjectionNeverDiscloseRawOrHashedMessageIndicator() throws Exception
    {
        String messageIndicator = "001122334455667788";
        CallEncryptionEvidence evidence = CallEncryptionEvidence.capture(encryptionKey(0x84, 0xBEEF),
            messageIndicator);
        String rendered = evidence.toString();

        assertFalse(rendered.contains(messageIndicator));
        assertFalse(rendered.contains(Long.toString(evidence.messageIndicatorFingerprint())));
        assertTrue(rendered.contains("messageIndicator=present"));

        String json = new ObjectMapper().writeValueAsString(evidence);
        assertFalse(json.contains("messageIndicatorFingerprint"));
        assertFalse(json.contains(messageIndicator));
        assertFalse(json.contains(Long.toString(evidence.messageIndicatorFingerprint())));
    }

    private static EncryptionKeyIdentifier encryptionKey(int algorithmId, int keyId)
    {
        return EncryptionKeyIdentifier.create(APCO25EncryptionKey.create(algorithmId, keyId));
    }
}
