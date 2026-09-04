package io.github.dsheirer.gui.setup;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dsheirer.database.SdrTrunkDatabase;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.EnumMap;

/** One bounded configuration record, not a job log or a second schema history. */
public final class SetupProgress
{
    public static final String KEY = "setup_wizard";
    private static final ObjectMapper JSON = new ObjectMapper()
        .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    public enum State { PENDING, CARRIED_OVER, COMPLETE, NEEDS_ATTENTION, RUNNING, DEFERRED }
    private final EnumMap<SetupStep, State> states = new EnumMap<>(SetupStep.class);
    private boolean complete;
    private final boolean imported;

    public SetupProgress(boolean complete, boolean imported)
    {
        this.complete = complete;
        this.imported = imported;
        for(SetupStep step: SetupStep.values()) states.put(step, complete ? State.COMPLETE : State.PENDING);
    }

    public boolean isComplete() { return complete; }
    public void setComplete(boolean value) { complete = value; }
    public boolean isImported() { return imported; }
    public State get(SetupStep step) { return states.get(step); }
    public void set(SetupStep step, State state) { states.put(step, state); }
    public boolean isDone(SetupStep step)
    {
        return get(step) == State.COMPLETE || get(step) == State.CARRIED_OVER;
    }
    public SetupStep next(SetupStep current)
    {
        for(int i = current.ordinal() + 1; i < SetupStep.values().length; i++)
        {
            SetupStep candidate = SetupStep.values()[i];
            if(candidate == SetupStep.REVIEW || !isDone(candidate) && get(candidate) != State.DEFERRED) return candidate;
        }
        return SetupStep.REVIEW;
    }

    public SetupStep resumeAt()
    {
        for(SetupStep step: SetupStep.values())
            if(!isDone(step) && get(step) != State.DEFERRED) return step;
        return SetupStep.REVIEW;
    }

    public String encode()
    {
        var root = JSON.createObjectNode();
        root.put("complete", complete);
        root.put("imported", imported);
        var steps = root.putObject("steps");
        states.forEach((step, state) -> steps.put(step.name(), state.name()));
        return root.toString();
    }

    public static SetupProgress decode(String encoded) throws SQLException
    {
        try
        {
            if(encoded == null || encoded.length() > 4096) throw new IllegalArgumentException();
            JsonNode root = JSON.readTree(encoded);
            if(!root.isObject() || root.size() != 3 || !root.path("complete").isBoolean() ||
                !root.path("imported").isBoolean() || !root.path("steps").isObject() ||
                root.path("steps").size() != SetupStep.values().length) throw new IllegalArgumentException();
            SetupProgress result = new SetupProgress(root.get("complete").asBoolean(), root.get("imported").asBoolean());
            for(SetupStep step: SetupStep.values())
            {
                State state = State.valueOf(root.get("steps").path(step.name()).asText());
                result.set(step, state == State.RUNNING ? State.PENDING : state);
            }
            return result;
        }
        catch(Exception e) { throw new SQLException("Invalid setup progress; the saved profile was not changed.", e); }
    }

    public static SetupProgress read(Connection connection) throws SQLException
    {
        try(var query = connection.prepareStatement("SELECT settings_json FROM application_settings WHERE key=?"))
        {
            query.setString(1, KEY);
            try(var rows = query.executeQuery())
            {
                if(!rows.next()) throw new SQLException("Missing setup progress. Run the Application Migrator.");
                return decode(rows.getString(1));
            }
        }
    }
    public static SetupProgress read(Path database) throws java.io.IOException, SQLException
    {
        try(var connection = SdrTrunkDatabase.open(database)) { return read(connection); }
    }
    public void save(Path database) throws java.io.IOException, SQLException
    {
        try(var connection = SdrTrunkDatabase.open(database)) { write(connection, this); }
    }
    public static void write(Connection connection, SetupProgress progress) throws SQLException
    {
        try(var update = connection.prepareStatement("""
            INSERT INTO application_settings(key,settings_json,updated_at_ms) VALUES(?,?,?)
            ON CONFLICT(key) DO UPDATE SET settings_json=excluded.settings_json,updated_at_ms=excluded.updated_at_ms
            """))
        {
            update.setString(1, KEY);
            update.setString(2, progress.encode());
            update.setLong(3, System.currentTimeMillis());
            update.executeUpdate();
        }
    }
}
