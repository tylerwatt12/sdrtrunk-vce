/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.database.upgrade;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/** Shared in-process Application Migrator support for orchestration tests. */
public final class ApplicationMigrationServiceTestSupport
{
    private ApplicationMigrationServiceTestSupport()
    {
    }

    public static ApplicationMigrationService createInProcess()
    {
        return new ApplicationMigrationService(ApplicationMigrationServiceTestSupport::runMigratorInProcess);
    }

    static String runMigratorInProcess(Path stagedDatabase, Path sourceDataRoot, Path targetDataRoot)
        throws IOException
    {
        String[] arguments = sourceDataRoot == null ? new String[] {stagedDatabase.toString()} :
            new String[] {stagedDatabase.toString(), sourceDataRoot.toString(), targetDataRoot.toString()};
        ByteArrayOutputStream captured = new ByteArrayOutputStream();

        try(PrintStream output = new PrintStream(captured, true, StandardCharsets.UTF_8))
        {
            int exitCode = ApplicationDatabaseMigrator.run(arguments, output, output);
            output.flush();
            String report = captured.toString(StandardCharsets.UTF_8).trim();

            if(exitCode != ApplicationDatabaseMigrator.EXIT_SUCCESS)
            {
                throw new IOException("The in-process Application Migrator failed with exit code " + exitCode +
                    (report.isBlank() ? "." : ":\n" + report));
            }

            return report;
        }
    }
}
