/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.gui.bugreport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class BugReportRedactorTest
{
    private final BugReportRedactor mRedactor = new BugReportRedactor();
    private final ObjectMapper mObjectMapper = new ObjectMapper();

    @Test
    void redactsCredentialFieldsRecursively() throws Exception
    {
        JsonNode source = mObjectMapper.readTree("""
            {
              "host": "radio.example",
              "password": "do-not-upload",
              "nested": {
                "apiKey": "also-secret",
                "serialNumber": "ABC123456",
                "user.authorization": "radio-reference-password"
              },
              "items": [{"access_token": "token-value"}]
            }
            """);
        JsonNode redacted = mRedactor.redact(source);

        assertEquals("radio.example", redacted.get("host").textValue());
        assertEquals(BugReportRedactor.REDACTED, redacted.get("password").textValue());
        assertEquals(BugReportRedactor.REDACTED, redacted.at("/nested/apiKey").textValue());
        assertEquals("ABC123456", redacted.at("/nested/serialNumber").textValue());
        assertEquals(BugReportRedactor.REDACTED,
            redacted.at("/nested/user.authorization").textValue());
        assertEquals(BugReportRedactor.REDACTED, redacted.at("/items/0/access_token").textValue());
    }

    @Test
    void redactsCredentialsFromLogsAndUserText()
    {
        String source = "password=do-not-upload Authorization: Bearer abc.def.ghi " +
            "https://example.test/upload?api_key=query-secret&feed=12 serial=ABC123456";
        String redacted = mRedactor.redactText(source);

        assertFalse(redacted.contains("do-not-upload"));
        assertFalse(redacted.contains("abc.def.ghi"));
        assertFalse(redacted.contains("query-secret"));
        assertTrue(redacted.contains("serial=ABC123456"));
    }
}
