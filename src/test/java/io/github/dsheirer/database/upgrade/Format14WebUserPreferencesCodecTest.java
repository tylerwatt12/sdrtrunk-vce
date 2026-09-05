package io.github.dsheirer.database.upgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dsheirer.web.settings.WebUserPreferencesCodec;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class Format14WebUserPreferencesCodecTest
{
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void renamesOnlyThePlaybackTargetFields() throws Exception
    {
        String source = source();
        JsonNode migrated = MAPPER.readTree(Format14WebUserPreferencesCodec.migrate(source));

        assertEquals(6, migrated.path("version").asInt());
        assertTrue(migrated.path("playback").path("target_grouping").asBoolean());
        assertEquals(4, migrated.path("playback").path("target_burst_limit").asInt());
        assertFalse(migrated.path("playback").has("conversation_grouping"));
        assertFalse(migrated.path("playback").has("conversation_burst_limit"));
        WebUserPreferencesCodec.decode(migrated.toString());
    }

    @Test
    void rejectsMissingMixedAndMalformedSourceDocuments() throws Exception
    {
        String source = source();
        assertThrows(IOException.class, () -> Format14WebUserPreferencesCodec.migrate(
            source.replace(",\"conversation_grouping\":true", "")));
        assertThrows(IOException.class, () -> Format14WebUserPreferencesCodec.migrate(
            source.replace("\"conversation_grouping\":true",
                "\"conversation_grouping\":true,\"target_grouping\":true")));
        assertThrows(IOException.class, () -> Format14WebUserPreferencesCodec.migrate(
            source.replace("\"conversation_burst_limit\":4", "\"conversation_burst_limit\":21")));
    }

    private static String source() throws Exception
    {
        return Format12WebUserPreferencesCodec.migrateFromFormat11(
            Format9WebUserPreferencesCodec.migrateFromFormat8(
                Format8WebUserPreferencesCodec.migrateFromFormat7(
                    Format7WebUserPreferencesCodec.migrateFromFormat6(
                        Format6WebUserPreferencesCodec.defaults(true, true, true, "percentage", 200))),
                false, false));
    }
}
