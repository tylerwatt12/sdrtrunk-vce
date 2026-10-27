/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.portable;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HexFormat;
import java.util.Locale;

/** Stable filesystem object identity across a rename, without reading file contents. */
public final class PortableFileIdentity
{
    private PortableFileIdentity() {}

    /** Returns null only when the filesystem provides no supported stable identity. */
    public static String read(Path path) throws IOException
    {
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if(attributes.isSymbolicLink()) throw new IOException("A VCE transfer cannot identify a symbolic link.");
        Object key = attributes.fileKey();
        if(key != null) return key.toString();
        if(!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) return null;
        try { return WindowsIdentity.read(path); }
        catch(IOException failure) { throw failure; }
        catch(Throwable failure) { throw new IOException("Windows could not identify the VCE file safely.", failure); }
    }

    /** Windows BasicFileAttributes.fileKey() is always null. Query the volume and 128-bit file ID directly. */
    private static final class WindowsIdentity
    {
        private static final SymbolLookup KERNEL = SymbolLookup.libraryLookup("Kernel32.dll", Arena.global());
        private static final Linker LINKER = Linker.nativeLinker();
        private static final MethodHandle OPEN = function("CreateFileW", FunctionDescriptor.of(ValueLayout.ADDRESS,
            ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        private static final MethodHandle INFO = function("GetFileInformationByHandleEx", FunctionDescriptor.of(
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        private static final MethodHandle CLOSE = function("CloseHandle", FunctionDescriptor.of(ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS));
        private static final long ERROR_OFFSET = Linker.Option.captureStateLayout().byteOffset(
            MemoryLayout.PathElement.groupElement("GetLastError"));

        private static MethodHandle function(String name, FunctionDescriptor descriptor)
        {
            return LINKER.downcallHandle(KERNEL.find(name).orElseThrow(), descriptor,
                Linker.Option.captureCallState("GetLastError"));
        }

        private static IOException failure(String operation, MemorySegment callState)
        {
            return new IOException("Windows could not " + operation + " the VCE file (error " +
                callState.get(ValueLayout.JAVA_INT, ERROR_OFFSET) + ").");
        }

        private static String read(Path path) throws Throwable
        {
            try(Arena arena = Arena.ofConfined())
            {
                String name = path.toAbsolutePath().normalize().toString();
                if(!name.startsWith("\\\\?\\")) name = name.startsWith("\\\\") ?
                    "\\\\?\\UNC\\" + name.substring(2) : "\\\\?\\" + name;
                byte[] utf16 = (name + '\0').getBytes(StandardCharsets.UTF_16LE);
                MemorySegment filename = arena.allocate(utf16.length, 2);
                filename.copyFrom(MemorySegment.ofArray(utf16));
                MemorySegment callState = arena.allocate(Linker.Option.captureStateLayout());
                // Share deletion so this short-lived metadata handle never obstructs a rename. Open the object
                // itself, including directories, rather than following a reparse point to a different object.
                MemorySegment handle = (MemorySegment)OPEN.invokeExact(callState, filename, 0, 7, MemorySegment.NULL, 3,
                    0x02200000, MemorySegment.NULL);
                if(handle.address() == -1L) throw failure("open", callState);
                try
                {
                    MemorySegment tag = arena.allocate(8, 4);
                    if((int)INFO.invokeExact(callState, handle, 9, tag, 8) == 0) throw failure("inspect", callState);
                    if((tag.get(ValueLayout.JAVA_INT, 0) & 0x400) != 0)
                        throw new IOException("A VCE transfer cannot identify a Windows reparse point.");
                    MemorySegment identity = arena.allocate(24, 8);
                    if((int)INFO.invokeExact(callState, handle, 18, identity, 24) == 0) throw failure("identify", callState);
                    return "windows:" + HexFormat.of().formatHex(identity.toArray(ValueLayout.JAVA_BYTE));
                }
                finally
                {
                    if((int)CLOSE.invokeExact(callState, handle) == 0) throw failure("close", callState);
                }
            }
        }
    }
}
