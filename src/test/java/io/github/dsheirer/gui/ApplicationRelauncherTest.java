/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */

package io.github.dsheirer.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class ApplicationRelauncherTest
{
    private static final String MAIN_CLASS = "io.github.dsheirer.gui.SDRTrunk";

    @Test
    void packagedLauncherIsPreferredWithoutRawJavaMetadata() throws Exception
    {
        Path launcher = Path.of("package/bin/sdrtrunk-vce");
        ApplicationRelauncher.RelaunchPlan plan = ApplicationRelauncher.buildPlan(launcher,
            Path.of("runtime/bin/java"), List.of("-Xmx2g"),
            new ApplicationRelauncher.RuntimeLaunchMetadata(null, null, null), MAIN_CLASS, true);

        assertEquals(ApplicationRelauncher.LaunchMode.PACKAGED, plan.mode());
        assertEquals(List.of(launcher.toAbsolutePath().normalize().toString()), plan.command());
        assertTrue(plan.automatic());
    }

    @Test
    void jpmsCommandRetainsRuntimeOptionsAndUsesAlternateMain() throws Exception
    {
        ApplicationRelauncher.RelaunchPlan plan = ApplicationRelauncher.buildPlan(null,
            Path.of("runtime/bin/java"), List.of(
                "-Xmx2g",
                "--enable-native-access=sdr.trunk,org.xerial.sqlitejdbc",
                "-Dsdrtrunk.vce.data.root=/portable/data",
                "-agentlib:jdwp=transport=dt_socket,address=5005",
                "-javaagent:/tmp/test-agent.jar"),
            new ApplicationRelauncher.RuntimeLaunchMetadata("sdr.trunk", "/runtime/app", "ignored"),
            MAIN_CLASS, false);

        List<String> command = plan.command();
        assertEquals(ApplicationRelauncher.LaunchMode.MODULE, plan.mode());
        assertTrue(plan.automatic());
        assertTrue(Path.of(command.getFirst()).isAbsolute());
        assertTrue(command.contains("-Xmx2g"));
        assertTrue(command.contains("-Dsdrtrunk.vce.data.root=/portable/data"));
        assertFalse(command.stream().anyMatch(argument -> argument.startsWith("-agentlib:")));
        assertFalse(command.stream().anyMatch(argument -> argument.startsWith("-javaagent:")));
        assertEquals(1, command.stream().filter(argument -> argument.startsWith("--enable-native-access")).count());
        assertEquals(List.of("--module-path", "/runtime/app", "-m", "sdr.trunk/" + MAIN_CLASS),
            command.subList(command.size() - 4, command.size()));
    }

    @Test
    void classpathFallbackIsRefusedForAutomaticWindowsRestart() throws Exception
    {
        ApplicationRelauncher.RelaunchPlan plan = ApplicationRelauncher.buildPlan(null,
            Path.of("runtime/bin/java.exe"), List.of("-Xmx2g"),
            new ApplicationRelauncher.RuntimeLaunchMetadata(null, null, "/runtime/lib/*"), MAIN_CLASS, true);

        assertEquals(ApplicationRelauncher.LaunchMode.CLASSPATH, plan.mode());
        assertFalse(plan.automatic());
        assertTrue(plan.manualReason().contains("normal launcher"));
        assertEquals(List.of("-classpath", "/runtime/lib/*", MAIN_CLASS),
            plan.command().subList(plan.command().size() - 3, plan.command().size()));
        assertThrows(IOException.class, () -> ApplicationRelauncher.relaunch(plan));
    }

    @Test
    void rejectsIncompleteOrUnexpectedModuleMetadata()
    {
        IOException incomplete = assertThrows(IOException.class, () -> ApplicationRelauncher.buildPlan(null,
            Path.of("runtime/bin/java"), List.of(),
            new ApplicationRelauncher.RuntimeLaunchMetadata("sdr.trunk", " ", "ignored"), MAIN_CLASS, false));
        IOException unexpected = assertThrows(IOException.class, () -> ApplicationRelauncher.buildPlan(null,
            Path.of("runtime/bin/java"), List.of(),
            new ApplicationRelauncher.RuntimeLaunchMetadata("other.module", "/runtime/app", "ignored"),
            MAIN_CLASS, false));

        assertTrue(incomplete.getMessage().contains("jdk.module.main and jdk.module.path"));
        assertTrue(unexpected.getMessage().contains("expected 'sdr.trunk'"));
    }

    @Test
    void rawJavaRelaunchPinsTheExactPortableDataRoot() throws Exception
    {
        Path exactRoot = Path.of("profiles/alpha-11").toAbsolutePath().normalize();
        ApplicationRelauncher.RelaunchPlan plan = ApplicationRelauncher.buildPlan(null,
            Path.of("runtime/bin/java"), List.of("-Dsdrtrunk.vce.data.root=/wrong/profile", "-Xmx2g"),
            new ApplicationRelauncher.RuntimeLaunchMetadata("sdr.trunk", "/runtime/app", "ignored"),
            MAIN_CLASS, false, exactRoot, true);

        assertTrue(plan.automatic());
        assertEquals(exactRoot, plan.expectedDataRoot());
        assertEquals(1, plan.command().stream()
            .filter(argument -> argument.startsWith("-Dsdrtrunk.vce.data.root="))
            .count());
        assertTrue(plan.command().contains("-Dsdrtrunk.vce.data.root=" + exactRoot));
    }

    @Test
    void packagedRelaunchWithExplicitDataRootRequiresManualRestart() throws Exception
    {
        Path launcher = Path.of("package/bin/sdrtrunk-vce");
        Path exactRoot = Path.of("profiles/alpha-11").toAbsolutePath().normalize();
        ApplicationRelauncher.RelaunchPlan plan = ApplicationRelauncher.buildPlan(launcher,
            Path.of("runtime/bin/java"), List.of(),
            new ApplicationRelauncher.RuntimeLaunchMetadata(null, null, null), MAIN_CLASS, false,
            exactRoot, true);

        assertFalse(plan.automatic());
        assertEquals(exactRoot, plan.expectedDataRoot());
        assertTrue(plan.manualReason().contains("same data-root setting"));
        assertThrows(IOException.class, () -> ApplicationRelauncher.relaunch(plan));
    }
}
