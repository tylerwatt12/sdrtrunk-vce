/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */

package io.github.dsheirer.gui;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Starts a clean copy of the current application after a restart-required desktop operation. */
final class ApplicationRelauncher
{
    private ApplicationRelauncher()
    {
    }

    static Process relaunch() throws IOException
    {
        String executableName = System.getProperty("os.name", "").toLowerCase().contains("win") ?
            "java.exe" : "java";
        Path javaExecutable = Path.of(System.getProperty("java.home"), "bin", executableName);
        List<String> command = buildCommand(javaExecutable,
            ManagementFactory.getRuntimeMXBean().getInputArguments(),
            System.getProperty("java.class.path"), SDRTrunk.class.getName());
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectInput(ProcessBuilder.Redirect.PIPE);
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        return builder.start();
    }

    static List<String> buildCommand(Path javaExecutable, List<String> inputArguments, String classPath,
                                     String mainClass)
    {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable.toAbsolutePath().normalize().toString());

        for(String argument: inputArguments)
        {
            //A debugger or instrumentation agent commonly owns a process-unique port and cannot be inherited safely.
            if(!argument.startsWith("-agentlib:") && !argument.startsWith("-javaagent:"))
            {
                command.add(argument);
            }
        }

        command.add("-classpath");
        command.add(classPath);
        command.add(mainClass);
        return List.copyOf(command);
    }
}
