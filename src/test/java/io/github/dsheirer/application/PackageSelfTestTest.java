/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.application;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PackageSelfTestTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void recognizesOnlyAnExplicitSelfTestRequest()
    {
        assertTrue(PackageSelfTest.isRequested(new String[] {PackageSelfTest.ARGUMENT}));
        assertFalse(PackageSelfTest.isRequested(new String[] {"--fresh", PackageSelfTest.ARGUMENT}));
        assertFalse(PackageSelfTest.isRequested(new String[] {"--fresh"}));
        assertFalse(PackageSelfTest.isRequested(null));
    }

    @Test
    void checksTheWindowsSdrplayLauncherSettings()
    {
        PackageSelfTest.verifyWindowsLauncher("Windows 11",
            "c:\\Program Files\\SDRplay\\API\\x64;C:\\Windows", "sdrplay_api.dll");
        assertThrows(IllegalStateException.class, () -> PackageSelfTest.verifyWindowsLauncher("Windows 11",
            "C:\\Windows", "sdrplay_api.dll"));
        assertThrows(IllegalStateException.class, () -> PackageSelfTest.verifyWindowsLauncher("Windows 11",
            "c:\\Program Files\\SDRplay\\API\\x64", "libsdrplay_api.so"));
        PackageSelfTest.verifyWindowsLauncher("Linux", "", "libsdrplay_api.so");
    }

    @Test
    void verifiesPackagedIdentityClassesAndWebAssets() throws Exception
    {
        Path assets = Files.createDirectories(mTemporaryFolder.resolve("stats-web"));
        Files.writeString(assets.resolve("index.html"), "<!doctype html><title>test</title>");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        PackageSelfTest.verify("nightly", "nightly", "123", assets,
            PackageSelfTest.class.getClassLoader(), new PrintStream(bytes, true, StandardCharsets.UTF_8));

        String output = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("PACKAGE SELF-TEST PASSED"));
        assertTrue(output.contains("version=nightly track=nightly build=123"));
        assertFalse(Files.exists(mTemporaryFolder.resolve("data")));
    }

    @Test
    void rejectsMissingAssetsAndInconsistentMetadata()
    {
        assertThrows(IllegalStateException.class, () -> PackageSelfTest.verify("nightly", "nightly", "1",
            mTemporaryFolder.resolve("missing"), PackageSelfTest.class.getClassLoader(), System.out));
        assertThrows(IllegalStateException.class, () -> PackageSelfTest.verify("nightly", "nightly", "0",
            mTemporaryFolder, PackageSelfTest.class.getClassLoader(), System.out));
        assertThrows(IllegalStateException.class, () -> PackageSelfTest.verify("local-dev", "none", "2",
            mTemporaryFolder, PackageSelfTest.class.getClassLoader(), System.out));
    }
}
