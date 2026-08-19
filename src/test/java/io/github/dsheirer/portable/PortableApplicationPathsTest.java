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

package io.github.dsheirer.portable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.database.SdrTrunkDatabaseBootstrap;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PortableApplicationPathsTest
{
    @TempDir
    Path mTemporaryFolder;

    @AfterEach
    void cleanup()
    {
        System.clearProperty(PortableApplicationPaths.DATA_ROOT_PROPERTY);
        PortableApplicationPaths.resetForTest();
    }

    @Test
    void copiesModulesAndStoresRelativePaths() throws Exception
    {
        System.setProperty(PortableApplicationPaths.DATA_ROOT_PROPERTY, mTemporaryFolder.resolve("data").toString());
        PortableApplicationPaths.resetForTest();
        Path source = mTemporaryFolder.resolve("module.jar");
        Files.writeString(source, "test");

        Path installed = PortableApplicationPaths.copyIntoDataDirectory(source, "modules");
        String stored = PortableApplicationPaths.toPortablePath(installed);

        assertEquals(Path.of("modules", "module.jar").toString(), stored);
        assertEquals(installed, PortableApplicationPaths.resolvePortablePath(stored));
    }

    @Test
    void explicitFreshBootstrapCreatesCompletePortableDatabases() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("portable-data");
        System.setProperty(PortableApplicationPaths.DATA_ROOT_PROPERTY, dataRoot.toString());
        PortableApplicationPaths.resetForTest();
        Path passwordFile = mTemporaryFolder.resolve("admin-password.txt");
        Files.writeString(passwordFile, "portable admin password\n");

        assertTrue(SdrTrunkDatabaseBootstrap.run(new String[]{"--fresh", "--admin-password-file",
            passwordFile.toString()}).startApplication());
        assertTrue(Files.isRegularFile(dataRoot.resolve("database/sdrtrunk.sqlite")));
        assertTrue(Files.isRegularFile(dataRoot.resolve("vault/encryption-key-vault.sqlite")));
    }

    @Test
    void defaultDataRootIsInsideInstall()
    {
        PortableApplicationPaths.resetForTest();

        assertEquals(PortableApplicationPaths.getInstallRoot().resolve("data"),
            PortableApplicationPaths.getDataRoot());
    }
}
