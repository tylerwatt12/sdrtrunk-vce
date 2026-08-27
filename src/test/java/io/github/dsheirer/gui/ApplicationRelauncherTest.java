/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */

package io.github.dsheirer.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class ApplicationRelauncherTest
{
    @Test
    void relaunchCommandRetainsRuntimeOptionsAndDropsProcessUniqueAgents()
    {
        List<String> command = ApplicationRelauncher.buildCommand(Path.of("runtime/bin/java"), List.of(
            "-Xmx2g",
            "--add-modules=jdk.incubator.vector",
            "-Dsdrtrunk.vce.data.root=/portable/data",
            "-agentlib:jdwp=transport=dt_socket,address=5005",
            "-javaagent:/tmp/test-agent.jar"),
            "/runtime/lib/*", "io.github.dsheirer.gui.SDRTrunk");

        assertTrue(Path.of(command.getFirst()).isAbsolute());
        assertTrue(command.contains("-Xmx2g"));
        assertTrue(command.contains("--add-modules=jdk.incubator.vector"));
        assertTrue(command.contains("-Dsdrtrunk.vce.data.root=/portable/data"));
        assertFalse(command.stream().anyMatch(argument -> argument.startsWith("-agentlib:")));
        assertFalse(command.stream().anyMatch(argument -> argument.startsWith("-javaagent:")));
        assertEquals(List.of("-classpath", "/runtime/lib/*", "io.github.dsheirer.gui.SDRTrunk"),
            command.subList(command.size() - 3, command.size()));
    }
}
