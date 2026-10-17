/* Copyright (C) 2026 Dennis Sheirer. Licensed under GPL-3.0-or-later. */
package io.github.dsheirer.database.configuration;

import static io.github.dsheirer.test.BroadcastRouteTestSupport.route;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasConfigurationSnapshot;
import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.alias.NewAliasBehavior;
import io.github.dsheirer.alias.UnmatchedTalkgroupPolicy;
import io.github.dsheirer.alias.id.radio.P25Subscriber;
import io.github.dsheirer.alias.id.radio.Radio;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.audio.broadcast.BroadcastFormat;
import io.github.dsheirer.audio.broadcast.broadcastify.BroadcastifyCallConfiguration;
import io.github.dsheirer.configuration.ConfigurationSnapshot;
import io.github.dsheirer.database.SdrTrunkDatabase;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.protocol.Protocol;
import io.github.dsheirer.scanlist.ScanList;
import io.github.dsheirer.scanlist.ScanListConfiguration;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigurationRepositoryDifferentialTest
{
    private static final List<String> TABLES = List.of("alias_list", "alias", "alias_broadcast_channel",
        "alias_p25_subscriber_identity", "p25_subscriber_identity", "alias_list_unmatched_talkgroup_stream",
        "alias_list_new_alias_stream", "scan_list", "alias_scan_list_membership",
        "alias_list_unmatched_talkgroup_scan_list_membership", "alias_list_new_alias_scan_list_membership",
        "alias_activity_summary", "configuration_channel", "configuration_broadcast_stream", "sqlite_sequence");
    private static final List<String> COUNTERS = List.of("logical_call_count", "recorded_logical_call_count",
        "stream_submitted_logical_call_count", "encrypted_logical_call_count", "grant_observation_count",
        "join_observation_count", "emergency_observation_count", "register_observation_count",
        "logout_observation_count", "denial_observation_count", "data_observation_count",
        "other_signaling_observation_count", "signaling_observation_count");
    @TempDir Path mTemporaryFolder;
    private int mTrigger;

    @Test
    void identicalSnapshotDoesNotWriteAnyConfigurationOrSummaryRow() throws Exception
    {
        Fixture fixture = fixture("identical");
        Map<String,List<List<Object>>> before = rows(fixture.database());
        for(String table: TABLES)
        {
            //sqlite_sequence is protected through its owner inserts; SQLite forbids triggers on system tables.
            if(!table.equals("sqlite_sequence")) forbidAll(fixture.database(), table);
        }
        fixture.repository().commitAliasConfiguration(fixture.snapshot());
        assertEquals(before, rows(fixture.database()));
    }

    @Test
    void appearanceEditUpdatesOnlySelectedParentAndPreservesEveryChildAndCounter() throws Exception
    {
        Fixture fixture = fixture("appearance");
        AliasConfigurationSnapshot candidate = fixture.repository().loadAliasConfiguration();
        Alias changed = candidate.aliases().getFirst();
        history(fixture.database(), changed.getId());
        Map<String,List<List<Object>>> before = rows(fixture.database());
        for(String table: TABLES)
        {
            if(!table.equals("alias") && !table.equals("sqlite_sequence")) forbidAll(fixture.database(), table);
        }
        forbid(fixture.database(), "alias", "INSERT", null);
        forbid(fixture.database(), "alias", "DELETE", null);
        forbid(fixture.database(), "alias", "UPDATE", "OLD.id<>" + changed.getId());
        changed.setName("Renamed dispatch");
        changed.setDescription("Updated description");
        changed.setRecordable(true);
        AliasConfigurationSnapshot committed = fixture.repository().commitAliasConfiguration(candidate);
        assertEquals("Renamed dispatch", committed.aliases().getFirst().getName());
        Map<String,List<List<Object>>> after = rows(fixture.database());
        before.remove("alias");
        after.remove("alias");
        assertEquals(before, after);
        assertHistory(fixture.database(), changed.getId(), true);
    }

    @Test
    void routeChangesRetainUnchangedEdgesWithoutUpdatingAliasOrSummary() throws Exception
    {
        Fixture fixture = fixture("routes");
        AliasConfigurationSnapshot candidate = fixture.repository().loadAliasConfiguration();
        Alias changed = candidate.aliases().getFirst();
        long existingRoute = number(fixture.database(),
            "SELECT id FROM alias_broadcast_channel WHERE alias_id=" + changed.getId());
        forbidAll(fixture.database(), "alias");
        forbidAll(fixture.database(), "alias_activity_summary");
        forbidAll(fixture.database(), "alias_p25_subscriber_identity");
        forbid(fixture.database(), "alias_broadcast_channel", "DELETE", "OLD.id=" + existingRoute);
        changed.addBroadcastChannel(route(fixture.secondStream()));
        AliasConfigurationSnapshot committed = fixture.repository().commitAliasConfiguration(candidate);
        assertEquals(2, committed.aliases().getFirst().getBroadcastChannels().size());
        assertEquals(existingRoute, number(fixture.database(), "SELECT id FROM alias_broadcast_channel " +
            "WHERE alias_id=" + changed.getId() + " AND broadcast_configuration_id='" +
            fixture.firstStream().getConfigurationId() + "'"));
        assertEquals(candidate.scanLists().aliasMemberships(), committed.scanLists().aliasMemberships());
    }

    @Test
    void canonicalAssociationChangesResetHistoryAndReuseSharedIdentityWithoutParentRewrite() throws Exception
    {
        Fixture fixture = fixture("subscriber");
        AliasConfigurationSnapshot candidate = fixture.repository().loadAliasConfiguration();
        Alias changed = candidate.aliases().get(1);
        history(fixture.database(), changed.getId());
        execute(fixture.database(), "INSERT INTO p25_subscriber_identity(home_wacn,home_system_id,subscriber_id) " +
            "VALUES (1,2,9)");
        long sharedIdentity = number(fixture.database(),
            "SELECT id FROM p25_subscriber_identity WHERE subscriber_id=9");
        forbidAll(fixture.database(), "alias");
        forbidAll(fixture.database(), "p25_subscriber_identity");
        changed.setMatchIdentifier(new P25Subscriber(1, 2, 9));
        AliasConfigurationSnapshot committed = fixture.repository().commitAliasConfiguration(candidate);
        assertEquals(9, ((P25Subscriber)committed.aliases().get(1).getMatchIdentifier()).getSubscriberId());
        assertEquals(sharedIdentity, number(fixture.database(), "SELECT p25_subscriber_identity_id " +
            "FROM alias_p25_subscriber_identity WHERE alias_id=" + changed.getId()));
        assertHistory(fixture.database(), changed.getId(), false);
        assertEquals(2, number(fixture.database(), "SELECT count(*) FROM p25_subscriber_identity"));
    }

    @Test
    void matcherTypeChangesClearOldPayloadAndCanonicalAssociation() throws Exception
    {
        Fixture fixture = fixture("matcher");
        AliasConfigurationSnapshot candidate = fixture.repository().loadAliasConfiguration();
        Alias changed = candidate.aliases().get(1);
        history(fixture.database(), changed.getId());
        changed.setMatchIdentifier(new Radio(Protocol.APCO25, 42));
        AliasConfigurationSnapshot committed = fixture.repository().commitAliasConfiguration(candidate);
        assertEquals(42, ((Radio)committed.aliases().get(1).getMatchIdentifier()).getValue());
        assertEquals(0, number(fixture.database(), "SELECT count(*) FROM alias_p25_subscriber_identity " +
            "WHERE alias_id=" + changed.getId()));
        assertEquals(1, number(fixture.database(), "SELECT count(*) FROM p25_subscriber_identity"));
        assertHistory(fixture.database(), changed.getId(), false);
        assertEquals(candidate.scanLists().aliasMemberships(), committed.scanLists().aliasMemberships());
    }

    @Test
    void movingAliasOutOfDeletedListPreservesItsHistoryAndMemberships() throws Exception
    {
        Fixture fixture = fixture("move");
        AliasConfigurationSnapshot current = fixture.repository().loadAliasConfiguration();
        Alias moved = current.aliases().getFirst();
        history(fixture.database(), moved.getId());
        AliasListDefinition destination = current.definitions().get(1);
        moved.setAliasListDefinition(destination);
        ScanListConfiguration scanLists = new ScanListConfiguration(current.scanLists().scanLists(),
            current.scanLists().aliasMemberships(), Map.of(destination.getId(), Set.of(
                current.scanLists().defaultScanList().getId())), Map.of());
        AliasConfigurationSnapshot committed = fixture.repository().commitAliasConfiguration(
            new AliasConfigurationSnapshot(List.of(destination), current.aliases(), scanLists));
        assertEquals(1, committed.definitions().size());
        assertEquals(destination.getId(), committed.aliases().getFirst().getAliasListId());
        assertEquals(destination.getId(), number(fixture.database(),
            "SELECT alias_list_id FROM alias_activity_summary WHERE alias_id=" + moved.getId()));
        assertHistory(fixture.database(), moved.getId(), true);
        assertEquals(current.scanLists().aliasMemberships(), committed.scanLists().aliasMemberships());
    }

    @Test
    void defaultSwitchAndNameSwapRetainScanIdentitiesAndAllMembershipEdges() throws Exception
    {
        Fixture fixture = fixture("default");
        AliasConfigurationSnapshot current = fixture.repository().loadAliasConfiguration();
        ScanList oldDefault = current.scanLists().defaultScanList();
        ScanList other = current.scanLists().scanLists().stream().filter(list -> !list.isDefault()).findFirst()
            .orElseThrow();
        ScanList demoted = oldDefault.withDefinition(0, other.getName(), null, true, false);
        ScanList promoted = other.withDefinition(1, oldDefault.getName(), null, true, true);
        for(String table: List.of("alias", "alias_activity_summary", "alias_scan_list_membership",
            "alias_list_unmatched_talkgroup_scan_list_membership", "alias_list_new_alias_scan_list_membership"))
            forbidAll(fixture.database(), table);
        forbid(fixture.database(), "scan_list", "INSERT", null);
        forbid(fixture.database(), "scan_list", "DELETE", null);
        ScanListConfiguration scanLists = new ScanListConfiguration(List.of(demoted, promoted),
            current.scanLists().aliasMemberships(), current.scanLists().unmatchedAliasListMemberships(),
            current.scanLists().newAliasListMemberships());
        AliasConfigurationSnapshot committed = fixture.repository().commitAliasConfiguration(
            new AliasConfigurationSnapshot(current.definitions(), current.aliases(), scanLists));
        assertEquals(other.getId(), committed.scanLists().defaultScanList().getId());
        assertEquals(scanLists.scanLists(), committed.scanLists().scanLists());
        assertEquals(current.scanLists().aliasMemberships(), committed.scanLists().aliasMemberships());
        assertEquals(current.scanLists().unmatchedAliasListMemberships(),
            committed.scanLists().unmatchedAliasListMemberships());
        assertEquals(current.scanLists().newAliasListMemberships(), committed.scanLists().newAliasListMemberships());
    }

    @Test
    void explicitMembershipRemovalChangesOnlyTheRequestedEdges() throws Exception
    {
        Fixture fixture = fixture("membership");
        AliasConfigurationSnapshot current = fixture.repository().loadAliasConfiguration();
        long changedId = current.aliases().getFirst().getId();
        Map<Long,Set<Long>> memberships = new LinkedHashMap<>(current.scanLists().aliasMemberships());
        memberships.put(changedId, Set.of(current.scanLists().defaultScanList().getId()));
        for(String table: List.of("alias", "alias_list", "alias_activity_summary", "scan_list",
            "alias_list_unmatched_talkgroup_scan_list_membership", "alias_list_new_alias_scan_list_membership"))
            forbidAll(fixture.database(), table);
        forbid(fixture.database(), "alias_scan_list_membership", "INSERT", null);
        forbid(fixture.database(), "alias_scan_list_membership", "DELETE", "OLD.alias_id<>" + changedId);
        ScanListConfiguration scanLists = new ScanListConfiguration(current.scanLists().scanLists(), memberships,
            current.scanLists().unmatchedAliasListMemberships(), current.scanLists().newAliasListMemberships());
        AliasConfigurationSnapshot committed = fixture.repository().commitAliasConfiguration(
            new AliasConfigurationSnapshot(current.definitions(), current.aliases(), scanLists));
        assertEquals(memberships, committed.scanLists().aliasMemberships());
    }

    @Test
    void lateRouteFailureAndCanonicalReadFailureRollBackAllEarlierDifferentialWrites() throws Exception
    {
        for(boolean readFailure: List.of(false, true))
        {
            Fixture fixture = fixture("rollback-" + readFailure);
            AliasConfigurationSnapshot candidate = fixture.repository().loadAliasConfiguration();
            Alias changed = candidate.aliases().getFirst();
            history(fixture.database(), changed.getId());
            Map<String,List<List<Object>>> before = rows(fixture.database());
            if(readFailure)
            {
                execute(fixture.database(), "CREATE TRIGGER corrupt_after_update AFTER UPDATE ON alias " +
                    "WHEN NEW.id=" + changed.getId() + " BEGIN UPDATE alias SET protocol='DMR' WHERE id=NEW.id; END");
            }
            else
            {
                execute(fixture.database(), "CREATE TRIGGER reject_route BEFORE INSERT ON alias_broadcast_channel " +
                    "WHEN NEW.alias_id=" + changed.getId() + " BEGIN SELECT RAISE(ABORT,'forced child failure'); END");
            }
            changed.setName("Changed before failure");
            changed.setMatchIdentifier(new Talkgroup(Protocol.APCO25, 999));
            changed.addBroadcastChannel(route(fixture.secondStream()));
            assertThrows(SQLException.class, () -> fixture.repository().commitAliasConfiguration(candidate));
            assertEquals(before, rows(fixture.database()));
            assertEquals("Dispatch", fixture.repository().loadAliasConfiguration().aliases().getFirst().getName());
        }
    }

    @Test
    void omittedAliasDeletesOnlyItsOwnedRowsAndFullReplacementKeepsItsMeaning() throws Exception
    {
        for(boolean fullReplacement: List.of(false, true))
        {
            Fixture fixture = fixture("delete-" + fullReplacement);
            AliasConfigurationSnapshot current = fixture.repository().loadAliasConfiguration();
            long removed = current.aliases().get(1).getId();
            long retained = current.aliases().getFirst().getId();
            Map<Long,Set<Long>> memberships = new LinkedHashMap<>(current.scanLists().aliasMemberships());
            memberships.remove(removed);
            AliasConfigurationSnapshot desired = new AliasConfigurationSnapshot(current.definitions(),
                List.of(current.aliases().getFirst()), new ScanListConfiguration(current.scanLists().scanLists(),
                    memberships, current.scanLists().unmatchedAliasListMemberships(),
                    current.scanLists().newAliasListMemberships()));
            if(fullReplacement)
            {
                ConfigurationSnapshot whole = fixture.repository().load();
                fixture.repository().replace(new ConfigurationSnapshot(desired.definitions(), desired.aliases(),
                    desired.scanLists(), whole.channels(), whole.broadcastConfigurations()));
            }
            else fixture.repository().commitAliasConfiguration(desired);
            assertEquals(retained, fixture.repository().loadAliasConfiguration().aliases().getFirst().getId());
            for(String table: List.of("alias_activity_summary", "alias_p25_subscriber_identity",
                "alias_broadcast_channel", "alias_scan_list_membership"))
                assertEquals(0, number(fixture.database(), "SELECT count(*) FROM " + table + " WHERE alias_id=" + removed));
            assertEquals(1, number(fixture.database(), "SELECT count(*) FROM p25_subscriber_identity"));
            try(Connection connection = SdrTrunkDatabase.open(fixture.database()); Statement statement = connection.createStatement();
                ResultSet violations = statement.executeQuery("PRAGMA foreign_key_check"))
            {
                assertTrue(!violations.next());
            }
        }
    }

    private Fixture fixture(String name) throws Exception
    {
        Path database = mTemporaryFolder.resolve(name + ".sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        ConfigurationRepository repository = new ConfigurationRepository(database);
        BroadcastifyCallConfiguration firstStream = new BroadcastifyCallConfiguration(BroadcastFormat.MP3);
        firstStream.setName("First stream");
        BroadcastifyCallConfiguration secondStream = new BroadcastifyCallConfiguration(BroadcastFormat.MP3);
        secondStream.setName("Second stream");
        AliasListDefinition first = new AliasListDefinition("First P25", AliasListFamily.P25);
        AliasListDefinition second = new AliasListDefinition("Second P25", AliasListFamily.P25);
        first.setUnmatchedTalkgroupPolicy(new UnmatchedTalkgroupPolicy(false, List.of(route(firstStream))));
        first.setNewAliasBehavior(new NewAliasBehavior(true, List.of(route(secondStream))));
        Alias dispatch = new Alias("Dispatch");
        dispatch.setAliasListDefinition(first);
        dispatch.setMatchIdentifier(new Talkgroup(Protocol.APCO25, 100));
        dispatch.addBroadcastChannel(route(firstStream));
        Alias subscriber = new Alias("Subscriber");
        subscriber.setAliasListDefinition(second);
        subscriber.setMatchIdentifier(new P25Subscriber(1, 2, 3));
        subscriber.addBroadcastChannel(route(secondStream));
        ScanListConfiguration factory = repository.loadAliasConfiguration().scanLists();
        ScanList extra = new ScanList("Extra");
        repository.synchronizeChannelAndBroadcastConfiguration(List.of(), List.of(firstStream, secondStream));
        AliasConfigurationSnapshot state = repository.commitAliasConfiguration(new AliasConfigurationSnapshot(
            List.of(first, second), List.of(dispatch, subscriber),
            new ScanListConfiguration(List.of(factory.defaultScanList(), extra), Map.of(), Map.of(), Map.of())));
        long defaultId = state.scanLists().defaultScanList().getId();
        long extraId = state.scanLists().scanLists().stream().filter(list -> !list.isDefault()).findFirst().orElseThrow().getId();
        ScanListConfiguration memberships = new ScanListConfiguration(state.scanLists().scanLists(),
            Map.of(state.aliases().getFirst().getId(), Set.of(defaultId, extraId), state.aliases().get(1).getId(), Set.of(extraId)),
            Map.of(state.definitions().getFirst().getId(), Set.of(defaultId)),
            Map.of(state.definitions().getFirst().getId(), Set.of(extraId)));
        AliasConfigurationSnapshot committed = repository.commitAliasConfiguration(
            new AliasConfigurationSnapshot(state.definitions(), state.aliases(), memberships));
        return new Fixture(database, repository, committed, firstStream, secondStream);
    }

    private void forbidAll(Path database, String table) throws Exception
    {
        for(String operation: List.of("INSERT", "UPDATE", "DELETE")) forbid(database, table, operation, null);
    }

    private void forbid(Path database, String table, String operation, String condition) throws Exception
    {
        execute(database, "CREATE TRIGGER forbid_" + mTrigger++ + " BEFORE " + operation + " ON " + table +
            (condition == null ? "" : " WHEN " + condition) + " BEGIN SELECT RAISE(ABORT,'unexpected row write'); END");
    }

    private static void history(Path database, long id) throws Exception
    {
        List<String> assignments = new ArrayList<>();
        COUNTERS.forEach(counter -> assignments.add(counter + "=7"));
        execute(database, "UPDATE alias_activity_summary SET " + String.join(",", assignments) +
            ",metrics_state='observed',first_evidence_ms=1000,last_evidence_ms=2000,last_reset_ms=500,updated_at_ms=600 WHERE alias_id=" + id);
    }

    private static void assertHistory(Path database, long id, boolean preserved) throws Exception
    {
        try(Connection connection = SdrTrunkDatabase.open(database); Statement statement = connection.createStatement();
            ResultSet row = statement.executeQuery("SELECT * FROM alias_activity_summary WHERE alias_id=" + id))
        {
            assertTrue(row.next());
            assertEquals(preserved ? "observed" : "not_collected", row.getString("metrics_state"));
            for(String counter: COUNTERS)
            {
                if(preserved) assertEquals(7L, row.getLong(counter));
                else assertNull(row.getObject(counter));
            }
            if(preserved)
            {
                assertEquals(500L, row.getLong("last_reset_ms"));
                assertEquals(1000L, row.getLong("first_evidence_ms"));
                assertEquals(2000L, row.getLong("last_evidence_ms"));
            }
            else
            {
                assertTrue(row.getLong("last_reset_ms") > 500);
                assertNull(row.getObject("first_evidence_ms"));
                assertNull(row.getObject("last_evidence_ms"));
            }
        }
    }

    private static Map<String,List<List<Object>>> rows(Path database) throws Exception
    {
        Map<String,List<List<Object>>> result = new LinkedHashMap<>();
        try(Connection connection = SdrTrunkDatabase.open(database); Statement statement = connection.createStatement())
        {
            for(String table: TABLES)
            {
                List<List<Object>> contents = new ArrayList<>();
                try(ResultSet rows = statement.executeQuery("SELECT * FROM " + table + " ORDER BY 1,2"))
                {
                    while(rows.next())
                    {
                        List<Object> row = new ArrayList<>();
                        for(int column = 1; column <= rows.getMetaData().getColumnCount(); column++) row.add(rows.getObject(column));
                        contents.add(row);
                    }
                }
                result.put(table, contents);
            }
        }
        return result;
    }

    private static long number(Path database, String sql) throws Exception
    {
        try(Connection connection = SdrTrunkDatabase.open(database); Statement statement = connection.createStatement();
            ResultSet row = statement.executeQuery(sql))
        {
            assertTrue(row.next());
            return row.getLong(1);
        }
    }

    private static void execute(Path database, String sql) throws Exception
    {
        try(Connection connection = SdrTrunkDatabase.open(database); Statement statement = connection.createStatement())
        {
            statement.executeUpdate(sql);
        }
    }

    private record Fixture(Path database, ConfigurationRepository repository, AliasConfigurationSnapshot snapshot,
                           BroadcastifyCallConfiguration firstStream, BroadcastifyCallConfiguration secondStream) {}
}
