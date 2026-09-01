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

import io.github.dsheirer.portable.PortableApplicationPaths;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Starts a clean copy of the current application after a restart-required desktop operation. */
final class ApplicationRelauncher
{
    private static final String APPLICATION_MODULE = "sdr.trunk";
    private static final String JPACKAGE_APP_PATH_PROPERTY = "jpackage.app-path";
    private static final String MODULE_MAIN_PROPERTY = "jdk.module.main";
    private static final String MODULE_PATH_PROPERTY = "jdk.module.path";
    private static final String DATA_ROOT_ARGUMENT_PREFIX =
        "-D" + PortableApplicationPaths.DATA_ROOT_PROPERTY + "=";

    private ApplicationRelauncher()
    {
    }

    static RelaunchPlan plan() throws IOException
    {
        return plan(PortableApplicationPaths.getDataRoot());
    }

    static RelaunchPlan plan(Path expectedDataRoot) throws IOException
    {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        String executableName = windows ? "java.exe" : "java";
        Path javaExecutable = Path.of(System.getProperty("java.home"), "bin", executableName);
        Path packagedLauncher = propertyPath(JPACKAGE_APP_PATH_PROPERTY);
        RelaunchPlan plan = buildPlan(packagedLauncher, javaExecutable,
            ManagementFactory.getRuntimeMXBean().getInputArguments(),
            new RuntimeLaunchMetadata(System.getProperty(MODULE_MAIN_PROPERTY),
                System.getProperty(MODULE_PATH_PROPERTY), System.getProperty("java.class.path")),
            SDRTrunk.class.getName(), windows, expectedDataRoot,
            nonBlank(System.getProperty(PortableApplicationPaths.DATA_ROOT_PROPERTY)) != null);

        Path executable = Path.of(plan.command().getFirst());
        if(!Files.isRegularFile(executable))
        {
            throw new IOException((plan.mode() == LaunchMode.PACKAGED ?
                "The packaged SDRTrunk launcher is unavailable: " :
                "The packaged Java executable was not found: ") + executable);
        }

        return plan;
    }

    static Process relaunch(RelaunchPlan plan) throws IOException
    {
        if(!plan.automatic())
        {
            throw new IOException(plan.manualReason());
        }

        ProcessBuilder builder = new ProcessBuilder(plan.command());
        builder.redirectInput(ProcessBuilder.Redirect.PIPE);
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        return builder.start();
    }

    static RelaunchPlan buildPlan(Path packagedLauncher, Path javaExecutable, List<String> inputArguments,
                                  RuntimeLaunchMetadata runtime, String mainClass, boolean windows)
        throws IOException
    {
        return buildPlan(packagedLauncher, javaExecutable, inputArguments, runtime, mainClass, windows, null, false);
    }

    static RelaunchPlan buildPlan(Path packagedLauncher, Path javaExecutable, List<String> inputArguments,
                                  RuntimeLaunchMetadata runtime, String mainClass, boolean windows,
                                  Path expectedDataRoot, boolean explicitDataRootOverride)
        throws IOException
    {
        Path exactDataRoot = expectedDataRoot == null ? null : expectedDataRoot.toAbsolutePath().normalize();
        if(packagedLauncher != null)
        {
            if(exactDataRoot != null && explicitDataRootOverride)
            {
                return new RelaunchPlan(List.of(packagedLauncher.toAbsolutePath().normalize().toString()),
                    LaunchMode.PACKAGED, false,
                    "The current process uses an explicit portable data folder. The packaged launcher cannot " +
                        "prove that exact folder will be reused, so start SDRTrunk with its normal launcher and " +
                        "the same data-root setting.", exactDataRoot);
            }

            return new RelaunchPlan(List.of(packagedLauncher.toAbsolutePath().normalize().toString()),
                LaunchMode.PACKAGED, true, null, exactDataRoot);
        }

        List<String> command = javaCommand(javaExecutable, inputArguments);
        if(exactDataRoot != null)
        {
            command.removeIf(argument -> argument.startsWith(DATA_ROOT_ARGUMENT_PREFIX));
            command.add(DATA_ROOT_ARGUMENT_PREFIX + exactDataRoot);
        }
        String mainModule = nonBlank(runtime.mainModule());
        String modulePath = nonBlank(runtime.modulePath());
        LaunchMode mode;

        if((mainModule == null) != (modulePath == null))
        {
            throw new IOException("The Java module launch metadata is incomplete for restarting SDRTrunk: " +
                MODULE_MAIN_PROPERTY + " and " + MODULE_PATH_PROPERTY + " must both be available.");
        }

        if(mainModule != null)
        {
            if(!APPLICATION_MODULE.equals(mainModule))
            {
                throw new IOException("The running application module is '" + mainModule + "'; expected '" +
                    APPLICATION_MODULE + "' for restarting SDRTrunk.");
            }

            addNativeAccessIfMissing(command,
                "--enable-native-access=" + APPLICATION_MODULE + ",org.xerial.sqlitejdbc");
            command.add("--module-path");
            command.add(modulePath);
            command.add("-m");
            command.add(APPLICATION_MODULE + "/" + mainClass);
            mode = LaunchMode.MODULE;
        }
        else
        {
            String classPath = nonBlank(runtime.classPath());
            if(classPath == null)
            {
                throw new IOException("The Java class path is unavailable for restarting SDRTrunk.");
            }

            addNativeAccessIfMissing(command, "--enable-native-access=ALL-UNNAMED");
            command.add("-classpath");
            command.add(classPath);
            command.add(mainClass);
            mode = LaunchMode.CLASSPATH;
        }

        //A raw Java child bypasses the visible-console/scheduled-task wrapper used by portable Windows installs.
        //Without an explicit packaged launcher, exit cleanly and let the operator or supervisor restart it.
        boolean automatic = !windows;
        String manualReason = automatic ? null :
            "This Windows launch is supervised or wrapper-based, so automatic raw-Java restart was refused. " +
                "Start SDRTrunk with its normal launcher.";
        return new RelaunchPlan(List.copyOf(command), mode, automatic, manualReason, exactDataRoot);
    }

    private static List<String> javaCommand(Path javaExecutable, List<String> inputArguments)
    {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable.toAbsolutePath().normalize().toString());

        boolean skipLaunchValue = false;
        for(String argument: inputArguments)
        {
            if(skipLaunchValue)
            {
                skipLaunchValue = false;
                continue;
            }

            //Debugger and instrumentation agents commonly own process-unique resources and cannot be inherited.
            if("--module-path".equals(argument) || "-p".equals(argument) || "--module".equals(argument) ||
                "-m".equals(argument))
            {
                skipLaunchValue = true;
            }
            else if(!argument.startsWith("-agentlib:") && !argument.startsWith("-javaagent:") &&
                !argument.startsWith("--module-path=") && !argument.startsWith("-p=") &&
                !argument.startsWith("--module=") && !argument.startsWith("-m="))
            {
                command.add(argument);
            }
        }

        return command;
    }

    private static void addNativeAccessIfMissing(List<String> command, String nativeAccess)
    {
        if(command.stream().noneMatch(argument -> argument.startsWith("--enable-native-access")))
        {
            command.add(nativeAccess);
        }
    }

    private static Path propertyPath(String name)
    {
        String value = System.getProperty(name);
        return value == null || value.isBlank() ? null : Path.of(value);
    }

    private static String nonBlank(String value)
    {
        return value == null || value.isBlank() ? null : value;
    }

    enum LaunchMode
    {
        PACKAGED,
        MODULE,
        CLASSPATH
    }

    record RuntimeLaunchMetadata(String mainModule, String modulePath, String classPath)
    {
    }

    record RelaunchPlan(List<String> command, LaunchMode mode, boolean automatic, String manualReason,
                        Path expectedDataRoot)
    {
        RelaunchPlan
        {
            command = List.copyOf(command);
            expectedDataRoot = expectedDataRoot == null ? null : expectedDataRoot.toAbsolutePath().normalize();
        }
    }
}
