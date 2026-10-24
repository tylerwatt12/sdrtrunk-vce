/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.web.auth;

import static org.junit.jupiter.api.Assertions.*;
import io.github.dsheirer.database.SdrTrunkDatabase;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WebTourDismissalServiceTest
{
    @TempDir Path directory;

    @Test void mergesAcrossBrowsersAndRestartWithoutChangingPersonalPreferenceRevisions() throws Exception
    {
        Path database = directory.resolve("history.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        WebAccessService access = new WebAccessService(database);
        WebAccessAccount admin = access.provisionOrResetPrimaryAdmin("example-admin-password".toCharArray());
        WebAccessAccount listener = access.createUser("listener", "example-listener-password".toCharArray());
        WebTourDismissalService first = new WebTourDismissalService(database), second = new WebTourDismissalService(database);
        assertTrue(first.get(admin).dismissed().isEmpty());
        long preferenceRevision = new WebUserPreferencesService(database).get(admin).revision();
        first.merge(admin, snapshot(Map.of("welcome", 1, "admin", 3)));
        assertEquals(Map.of("welcome", 1, "admin", 3, "recordings", 2),
            second.merge(admin, snapshot(Map.of("welcome", 1, "admin", 1, "recordings", 2))).dismissed());
        assertEquals(Map.of("welcome", 1, "admin", 3, "recordings", 2),
            first.merge(admin, snapshot(Map.of())).dismissed(), "An empty browser history never clears saved dismissals.");
        assertTrue(first.get(listener).dismissed().isEmpty());
        second.merge(listener, snapshot(Map.of("welcome", 2)));
        assertEquals(1, first.get(admin).dismissed().get("welcome"));
        assertEquals(preferenceRevision, new WebUserPreferencesService(database).get(admin).revision());
        WebAccessAccount recovered = access.provisionOrResetPrimaryAdmin("example-new-admin-password".toCharArray());
        WebAccessAccount resetListener = access.resetUserPassword("listener", "example-new-listener-password".toCharArray());
        WebTourDismissalService restarted = new WebTourDismissalService(database);
        assertEquals(first.get(admin), restarted.get(recovered));
        assertEquals(Map.of("welcome", 2), restarted.get(resetListener).dismissed());
        access.deleteUser("listener");
        try(var connection = SdrTrunkDatabase.open(database); var statement = connection.createStatement();
            var rows = statement.executeQuery("SELECT count(*) FROM web_user_tour_dismissal WHERE user_id=" + listener.id()))
        { assertTrue(rows.next()); assertEquals(0, rows.getInt(1)); }
        assertTrue(restarted.get(access.createUser("listener", "example-recreated-password".toCharArray())).dismissed().isEmpty());
        assertThrows(java.sql.SQLException.class, () -> first.merge(listener, snapshot(Map.of("admin", 9))));
    }

    @Test void concurrentAccountsAndTabsKeepTheUnionAndHighestVersion() throws Exception
    {
        Path database = directory.resolve("parallel.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        WebAccessAccount account = new WebAccessService(database).provisionOrResetPrimaryAdmin("example-admin-password".toCharArray());
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        try(var executor = Executors.newFixedThreadPool(2))
        {
            var older = executor.submit(() -> {
                ready.countDown(); assertTrue(start.await(5, TimeUnit.SECONDS));
                return new WebTourDismissalService(database).merge(account, snapshot(Map.of("admin", 1, "aliases", 2)));
            });
            var newer = executor.submit(() -> {
                ready.countDown(); assertTrue(start.await(5, TimeUnit.SECONDS));
                return new WebTourDismissalService(database).merge(account, snapshot(Map.of("admin", 5, "scan-lists", 3)));
            });
            assertTrue(ready.await(5, TimeUnit.SECONDS)); start.countDown();
            older.get(10, TimeUnit.SECONDS); newer.get(10, TimeUnit.SECONDS);
        }
        assertEquals(Map.of("admin", 5, "aliases", 2, "scan-lists", 3), new WebTourDismissalService(database).get(account).dismissed());
    }

    @Test void accountLimitIsAtomicAndSqliteRejectsMalformedIdsVersionsAndMissingOwners() throws Exception
    {
        Path database = directory.resolve("limits.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        WebAccessAccount account = new WebAccessService(database).provisionOrResetPrimaryAdmin("example-admin-password".toCharArray());
        WebTourDismissalService service = new WebTourDismissalService(database);
        Map<String,Integer> full = new LinkedHashMap<>();
        for(int i=0; i<64; i++) full.put("guide-" + i, 1);
        service.merge(account, snapshot(full));
        assertThrows(IllegalArgumentException.class, () -> service.merge(account,
            snapshot(Map.of("guide-0", 3, "another-guide", 1))));
        assertEquals(full, service.get(account).dismissed(), "A rejected merge cannot partly raise another version.");
        assertEquals(1_000_000, service.merge(account, snapshot(Map.of("guide-0", 1_000_000))).dismissed().get("guide-0"));
        for(String id: new String[]{"", "UPPER", " space", "a/b", "a.b", "_private", "a".repeat(65)})
            assertThrows(IllegalArgumentException.class, () -> snapshot(Map.of(id, 1)));
        for(int version: new int[]{-1, 0, 1_000_001})
            assertThrows(IllegalArgumentException.class, () -> snapshot(Map.of("admin", version)));
        try(var connection = SdrTrunkDatabase.open(database); var statement = connection.prepareStatement(
            "INSERT INTO web_user_tour_dismissal(user_id,guide_id,guide_version) VALUES(?,?,?)"))
        {
            statement.setLong(1, account.id()); statement.setString(2, "bad/id"); statement.setInt(3, 1);
            assertThrows(java.sql.SQLException.class, statement::executeUpdate);
            statement.setString(2, "valid"); statement.setInt(3, 0);
            assertThrows(java.sql.SQLException.class, statement::executeUpdate);
            statement.setLong(1, account.id() + 999); statement.setInt(3, 1);
            assertThrows(java.sql.SQLException.class, statement::executeUpdate);
        }
    }

    private static WebTourDismissalService.Snapshot snapshot(Map<String,Integer> dismissed)
    { return new WebTourDismissalService.Snapshot(1, dismissed); }
}
