/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.portable;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the production Win32 branch with simulated native results, without requiring a Windows host. */
@Tag("platform-filesystem")
class WindowsFileIdentityTest
{
    private static final String LEGACY = "windows-ntfs64:12345678:0123456789abcdef";

    @Test
    void preservesModernTokensWithoutQueryingLegacyCapabilities() throws Throwable
    {
        FakeWindows windows = new FakeWindows();
        windows.identityError = 0;
        assertEquals("windows:0102030405060708090a0b0c0d0e0f101112131415161718", read(windows));
        assertEquals(List.of("open", "inspect", "identify", "close"), windows.calls);
    }

    @Test
    void fallsBackOnTheSameHandleOnlyAfterLocalNtfsQualification() throws Throwable
    {
        FakeWindows windows = new FakeWindows();
        assertEquals(LEGACY, read(windows));
        assertEquals(List.of("open", "inspect", "identify", "volume", "path", "legacy", "close"), windows.calls);
    }

    @Test
    void legacyTokensExcludePathsAndMutableMetadataButDistinguishVolumeAndFile() throws Throwable
    {
        FakeWindows windows = new FakeWindows();
        assertEquals(LEGACY, read(windows));
        windows.metadata = 42;
        assertEquals(LEGACY, PortableFileIdentity.readWindows(Path.of("renamed"), windows.functions()));
        windows.serial++;
        assertNotEquals(LEGACY, read(windows));
        windows.serial--;
        windows.indexHigh++;
        assertNotEquals(LEGACY, read(windows));
        windows.indexHigh--;
        windows.indexLow++;
        assertNotEquals(LEGACY, read(windows));
    }

    @Test
    void doesNotFallbackForOtherIdentityOrInspectionErrors() throws Exception
    {
        for(int error: new int[]{1, 5, 6, 32, 50})
        {
            FakeWindows windows = new FakeWindows();
            windows.identityError = error;
            assertEquals("Windows could not identify the VCE file (error " + error + ").", failure(windows).getMessage());
            assertEquals(List.of("open", "inspect", "identify", "close"), windows.calls);
        }
        FakeWindows windows = new FakeWindows();
        windows.inspectError = 87;
        assertEquals("Windows could not inspect the VCE file (error 87).", failure(windows).getMessage());
        assertEquals(List.of("open", "inspect", "close"), windows.calls);
    }

    @Test
    void refusesOtherFilesystemsAndNonLocalOrInvalidVolumePaths() throws Exception
    {
        for(String filesystem: new String[]{"FAT32", "ReFS", "", "NTFS-like"})
        {
            FakeWindows windows = new FakeWindows();
            windows.filesystem = filesystem;
            failure(windows);
            assertFalse(windows.calls.contains("legacy"));
            assertEquals("close", windows.calls.getLast());
        }
        for(String path: new String[]{"\\\\?\\UNC\\server\\share\\file", "\\\\?\\C:\\file",
            "\\\\?\\Volume{not-a-guid}\\file", "\\\\?\\Volume{01234567-89ab-cdef-0123-456789abcdef}file"})
        {
            FakeWindows windows = new FakeWindows();
            windows.path = path;
            failure(windows);
            assertFalse(windows.calls.contains("legacy"));
            assertEquals("close", windows.calls.getLast());
        }
    }

    @Test
    void refusesFailedQualificationAndTruncatedPathsWithoutUsingLegacyIdentity() throws Exception
    {
        for(String operation: new String[]{"volume", "path", "truncated"})
        {
            FakeWindows windows = new FakeWindows();
            if(operation.equals("volume")) windows.volumeError = 5;
            else if(operation.equals("path")) windows.pathError = 3;
            else windows.truncatedPath = true;
            failure(windows);
            assertFalse(windows.calls.contains("legacy"));
            assertEquals("close", windows.calls.getLast());
        }
    }

    @Test
    void refusesFailedOrUnusableLegacyIdentityAndAlwaysClosesTheHandle() throws Exception
    {
        for(String condition: new String[]{"error", "zero", "reparse"})
        {
            FakeWindows windows = new FakeWindows();
            if(condition.equals("error")) windows.legacyError = 5;
            else if(condition.equals("zero")) { windows.indexHigh = 0; windows.indexLow = 0; }
            else windows.legacyAttributes = 0x400;
            failure(windows);
            assertEquals(List.of("open", "inspect", "identify", "volume", "path", "legacy", "close"), windows.calls);
        }
    }

    @Test
    void rejectsReparsePointsBeforeAnyIdentityQueryAndDoesNotCloseAnInvalidHandle() throws Exception
    {
        FakeWindows windows = new FakeWindows();
        windows.attributes = 0x400;
        assertTrue(failure(windows).getMessage().contains("reparse point"));
        assertEquals(List.of("open", "inspect", "close"), windows.calls);
        windows = new FakeWindows();
        windows.openError = 5;
        assertEquals("Windows could not open the VCE file (error 5).", failure(windows).getMessage());
        assertEquals(List.of("open"), windows.calls);
    }

    private static String read(FakeWindows windows) throws Throwable
    {
        return PortableFileIdentity.readWindows(Path.of("synthetic-file"), windows.functions());
    }

    private static IOException failure(FakeWindows windows) throws Exception
    {
        return assertThrows(IOException.class, () -> read(windows));
    }

    private static final class FakeWindows
    {
        private static final MemorySegment HANDLE = MemorySegment.ofAddress(0x1234);
        private final List<String> calls = new ArrayList<>();
        private int openError, inspectError, volumeError, pathError, legacyError;
        private int identityError = 87, attributes, legacyAttributes, metadata;
        private int serial = 0x12345678, indexHigh = 0x01234567, indexLow = 0x89abcdef;
        private String filesystem = "NTFS";
        private String path = "\\\\?\\Volume{01234567-89ab-cdef-0123-456789abcdef}\\synthetic-file";
        private boolean truncatedPath;

        private PortableFileIdentity.WindowsFunctions functions() throws ReflectiveOperationException
        {
            return new PortableFileIdentity.WindowsFunctions(
                function("open", MemorySegment.class, MemorySegment.class, MemorySegment.class, int.class, int.class,
                    MemorySegment.class, int.class, int.class, MemorySegment.class),
                function("info", int.class, MemorySegment.class, MemorySegment.class, int.class, MemorySegment.class, int.class),
                function("close", int.class, MemorySegment.class, MemorySegment.class),
                function("legacy", int.class, MemorySegment.class, MemorySegment.class, MemorySegment.class),
                function("volume", int.class, MemorySegment.class, MemorySegment.class, MemorySegment.class, int.class,
                    MemorySegment.class, MemorySegment.class, MemorySegment.class, MemorySegment.class, int.class),
                function("path", int.class, MemorySegment.class, MemorySegment.class, MemorySegment.class, int.class, int.class),
                ValueLayout.JAVA_INT, 0);
        }

        private MethodHandle function(String name, Class<?> result, Class<?>... parameters) throws ReflectiveOperationException
        {
            return MethodHandles.lookup().findVirtual(FakeWindows.class, name, MethodType.methodType(result, parameters)).bindTo(this);
        }

        private MemorySegment open(MemorySegment state, MemorySegment filename, int access, int share,
                                   MemorySegment security, int creation, int flags, MemorySegment template)
        {
            calls.add("open");
            assertEquals(0, access); assertEquals(7, share); assertEquals(3, creation); assertEquals(0x02200000, flags);
            assertEquals(MemorySegment.NULL, security); assertEquals(MemorySegment.NULL, template);
            result(state, openError);
            return openError == 0 ? HANDLE : MemorySegment.ofAddress(-1L);
        }

        private int info(MemorySegment state, MemorySegment handle, int kind, MemorySegment output, int bytes)
        {
            assertEquals(HANDLE, handle);
            output.fill((byte)0);
            if(kind == 9)
            {
                calls.add("inspect"); assertEquals(8, bytes); output.set(ValueLayout.JAVA_INT, 0, attributes);
                return result(state, inspectError);
            }
            calls.add("identify"); assertEquals(18, kind); assertEquals(24, bytes);
            for(int index = 0; index < 24; index++) output.set(ValueLayout.JAVA_BYTE, index, (byte)(index + 1));
            return result(state, identityError);
        }

        private int volume(MemorySegment state, MemorySegment handle, MemorySegment label, int labelSize,
                           MemorySegment serial, MemorySegment maximum, MemorySegment flags, MemorySegment filesystemName, int chars)
        {
            calls.add("volume"); assertEquals(HANDLE, handle); assertTrue(chars >= filesystem.length() + 1);
            filesystemName.fill((byte)0);
            filesystemName.asSlice(0, filesystem.length() * 2L).copyFrom(MemorySegment.ofArray(filesystem.getBytes(StandardCharsets.UTF_16LE)));
            return result(state, volumeError);
        }

        private int path(MemorySegment state, MemorySegment handle, MemorySegment output, int chars, int flags)
        {
            calls.add("path"); assertEquals(HANDLE, handle); assertEquals(9, flags);
            output.fill((byte)0);
            output.asSlice(0, path.length() * 2L).copyFrom(MemorySegment.ofArray(path.getBytes(StandardCharsets.UTF_16LE)));
            result(state, pathError);
            return pathError != 0 ? 0 : truncatedPath ? chars : path.length();
        }

        private int legacy(MemorySegment state, MemorySegment handle, MemorySegment output)
        {
            calls.add("legacy"); assertEquals(HANDLE, handle); assertEquals(52, output.byteSize());
            output.fill((byte)0); output.set(ValueLayout.JAVA_INT, 0, legacyAttributes);
            output.set(ValueLayout.JAVA_INT, 4, metadata); output.set(ValueLayout.JAVA_INT, 32, metadata);
            output.set(ValueLayout.JAVA_INT, 28, serial); output.set(ValueLayout.JAVA_INT, 44, indexHigh);
            output.set(ValueLayout.JAVA_INT, 48, indexLow);
            return result(state, legacyError);
        }

        private int close(MemorySegment state, MemorySegment handle)
        {
            calls.add("close"); assertEquals(HANDLE, handle); return result(state, 0);
        }

        private int result(MemorySegment state, int error)
        {
            state.set(ValueLayout.JAVA_INT, 0, error); return error == 0 ? 1 : 0;
        }
    }
}
