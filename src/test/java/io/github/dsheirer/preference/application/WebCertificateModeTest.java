/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.preference.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class WebCertificateModeTest
{
    @Test
    void defaultsMissingAndUnknownValuesToAutomatic()
    {
        assertEquals(WebCertificateMode.AUTOMATIC, WebCertificateMode.fromStoredValue(null));
        assertEquals(WebCertificateMode.AUTOMATIC, WebCertificateMode.fromStoredValue(""));
        assertEquals(WebCertificateMode.AUTOMATIC, WebCertificateMode.fromStoredValue("future-mode"));
    }

    @Test
    void parsesStoredValuesWithoutCaseSensitivity()
    {
        assertEquals(WebCertificateMode.AUTOMATIC, WebCertificateMode.fromStoredValue(" automatic "));
        assertEquals(WebCertificateMode.CUSTOM, WebCertificateMode.fromStoredValue("custom"));
    }
}
