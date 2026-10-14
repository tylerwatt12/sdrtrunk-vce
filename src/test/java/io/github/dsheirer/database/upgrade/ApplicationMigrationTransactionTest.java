/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class ApplicationMigrationTransactionTest
{
    @Test void explicitTransactionProofIsConfinedToItsConnectionThreadAndLifetime() throws Exception
    {
        for(String completion: List.of("COMMIT", "ROLLBACK"))
        {
            try(var connection = DriverManager.getConnection("jdbc:sqlite::memory:");
                var other = DriverManager.getConnection("jdbc:sqlite::memory:");
                var statement = connection.createStatement())
            {
                assertThrows(SQLException.class, () -> ApplicationMigrationTransaction.requireActive(connection));
                try(var transaction = ApplicationMigrationTransaction.begin(connection, statement))
                {
                    assertTrue(connection.getAutoCommit(), "SQL BEGIN does not change Xerial's JDBC flag");
                    assertDoesNotThrow(() -> ApplicationMigrationTransaction.requireActive(connection));
                    assertThrows(SQLException.class, () -> ApplicationMigrationTransaction.requireActive(other));
                    CompletableFuture.runAsync(() -> assertThrows(SQLException.class,
                        () -> ApplicationMigrationTransaction.requireActive(connection))).get();
                    statement.execute(completion);
                }
                assertThrows(SQLException.class, () -> ApplicationMigrationTransaction.requireActive(connection));
            }
        }
    }

    @Test void failedBeginDoesNotRegisterAnUnownedTransaction() throws Exception
    {
        try(var connection = DriverManager.getConnection("jdbc:sqlite::memory:");
            var statement = connection.createStatement())
        {
            statement.execute("BEGIN IMMEDIATE");
            assertThrows(SQLException.class, () -> ApplicationMigrationTransaction.begin(connection, statement));
            assertThrows(SQLException.class, () -> ApplicationMigrationTransaction.requireActive(connection));
            statement.execute("ROLLBACK");
        }
    }
}
