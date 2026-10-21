/* Copyright (C) 2026 Dennis Sheirer */
package io.github.dsheirer.gui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Architectural guard; lifetime behavior is exercised by ReceiverRuntimeTest. */
class HeadlessReceiverLifecycleContractTest
{
    @Test
    void receiverRuntimeDoesNotImportDesktopToolkits() throws Exception
    {
        Path runtime = Path.of("src/main/java/io/github/dsheirer/receiver/ReceiverRuntime.java");
        String source = Files.readString(runtime);
        assertFalse(Pattern.compile("(?m)^import (javafx\\.|javax\\.swing\\.|java\\.awt\\.|io\\.github\\.dsheirer\\.gui\\.)")
            .matcher(source).find());
    }

    @Test
    void receiverOwnsTheOnlyShutdownHookAndClosesCodecLibrariesAfterOutputDrains() throws Exception
    {
        Path root = Path.of("src/main/java/io/github/dsheirer");
        Path runtime = root.resolve("receiver/ReceiverRuntime.java");
        try(var paths = Files.walk(root))
        {
            assertEquals(java.util.List.of(runtime), paths.filter(path -> path.toString().endsWith(".java"))
                .filter(path -> {
                    try { return Files.readString(path).contains(".addShutdownHook("); }
                    catch(java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
                }).toList());
        }
        String source = Files.readString(runtime);
        int codecClose = source.indexOf("JmbeLibraryLoader.getInstance()::close");
        assertTrue(codecClose > 0);
        for(String drain: java.util.List.of("mCalls.disposeAndAwait(", "mStreaming::stop", "mRecording::stop"))
        {
            int position = source.indexOf(drain);
            assertTrue(position >= 0 && position < codecClose, drain);
        }
    }
}
