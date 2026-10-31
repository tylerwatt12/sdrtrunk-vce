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
import java.util.UUID;

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
        try { return readWindows(path, WindowsIdentity.FUNCTIONS); }
        catch(IOException failure) { throw failure; }
        catch(Throwable failure) { throw new IOException("Windows could not identify the VCE file safely.", failure); }
    }

    /** Native operations and captured error storage, also usable with simulated platform results. */
    record WindowsFunctions(MethodHandle open, MethodHandle info, MethodHandle close, MethodHandle legacyInfo,
                            MethodHandle volumeInfo, MethodHandle finalPath, MemoryLayout callStateLayout, long errorOffset)
    {
        private IOException failure(String operation, MemorySegment callState)
        {
            return new IOException("Windows could not " + operation + " the VCE file (error " +
                callState.get(ValueLayout.JAVA_INT, errorOffset) + ").");
        }
    }

    /** Windows BasicFileAttributes.fileKey() is always null. Query identity on the reparse-checked handle. */
    static String readWindows(Path path, WindowsFunctions functions) throws Throwable
    {
        try(Arena arena = Arena.ofConfined())
        {
            String name = path.toAbsolutePath().normalize().toString();
            if(!name.startsWith("\\\\?\\")) name = name.startsWith("\\\\") ?
                "\\\\?\\UNC\\" + name.substring(2) : "\\\\?\\" + name;
            byte[] utf16 = (name + '\0').getBytes(StandardCharsets.UTF_16LE);
            MemorySegment filename = arena.allocate(utf16.length, 2);
            filename.copyFrom(MemorySegment.ofArray(utf16));
            MemorySegment callState = arena.allocate(functions.callStateLayout);
            // Share deletion and open the object itself, including directories, without following reparse points.
            MemorySegment handle = (MemorySegment)functions.open.invokeExact(callState, filename, 0, 7,
                MemorySegment.NULL, 3, 0x02200000, MemorySegment.NULL);
            if(handle.address() == -1L) throw functions.failure("open", callState);
            try
            {
                MemorySegment tag = arena.allocate(8, 4);
                if((int)functions.info.invokeExact(callState, handle, 9, tag, 8) == 0)
                    throw functions.failure("inspect", callState);
                if((tag.get(ValueLayout.JAVA_INT, 0) & 0x400) != 0)
                    throw new IOException("A VCE transfer cannot identify a Windows reparse point.");
                MemorySegment identity = arena.allocate(24, 8);
                if((int)functions.info.invokeExact(callState, handle, 18, identity, 24) != 0)
                    return "windows:" + HexFormat.of().formatHex(identity.toArray(ValueLayout.JAVA_BYTE));
                IOException failure = functions.failure("identify", callState);
                // Windows 7 rejects FileIdInfo with ERROR_INVALID_PARAMETER. Keep all other failures fatal.
                if(callState.get(ValueLayout.JAVA_INT, functions.errorOffset) != 87) throw failure;
                return readLegacyNtfsIdentity(arena, functions, callState, handle, failure);
            }
            finally
            {
                if((int)functions.close.invokeExact(callState, handle) == 0)
                    throw functions.failure("close", callState);
            }
        }
    }

    private static String readLegacyNtfsIdentity(Arena arena, WindowsFunctions functions, MemorySegment callState,
                                                 MemorySegment handle, IOException unsupported) throws Throwable
    {
        // FAT IDs can change on rename; ReFS requires 128 bits. Qualify the opened volume, not its pathname.
        MemorySegment filesystem = arena.allocate(32, 2);
        if((int)functions.volumeInfo.invokeExact(callState, handle, MemorySegment.NULL, 0, MemorySegment.NULL,
            MemorySegment.NULL, MemorySegment.NULL, filesystem, 16) == 0)
            throw functions.failure("inspect", callState);
        if(!new String(filesystem.toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_16LE).startsWith("NTFS\0"))
            throw unsupported;
        // A volume GUID path is available for local Mount Manager volumes, never network shares (including mapped drives).
        MemorySegment finalName = arena.allocate(32768 * 2, 2);
        int length = (int)functions.finalPath.invokeExact(callState, handle, finalName, 32768, 9);
        if(length == 0) throw functions.failure("inspect", callState);
        if(length < 49 || length >= 32768) throw unsupported;
        String volume = new String(finalName.asSlice(0, 98).toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_16LE);
        if(!volume.startsWith("\\\\?\\Volume{") || !volume.endsWith("}\\")) throw unsupported;
        try
        {
            String guid = volume.substring(11, 47);
            if(!UUID.fromString(guid).toString().equalsIgnoreCase(guid)) throw unsupported;
        }
        catch(IllegalArgumentException failure) { throw unsupported; }
        MemorySegment information = arena.allocate(52, 4); // BY_HANDLE_FILE_INFORMATION
        if((int)functions.legacyInfo.invokeExact(callState, handle, information) == 0)
            throw functions.failure("identify", callState);
        if((information.get(ValueLayout.JAVA_INT, 0) & 0x400) != 0) throw unsupported;
        long index = ((long)information.get(ValueLayout.JAVA_INT, 44) << 32) |
            Integer.toUnsignedLong(information.get(ValueLayout.JAVA_INT, 48));
        if(index == 0) throw unsupported;
        // Keep modern tokens unchanged and distinguish the legacy scheme in persisted upgrade/recovery records.
        return "windows-ntfs64:" + HexFormat.of().toHexDigits(information.get(ValueLayout.JAVA_INT, 28)) + ":" +
            HexFormat.of().toHexDigits(index);
    }

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
        private static final MethodHandle LEGACY_INFO = function("GetFileInformationByHandle", FunctionDescriptor.of(
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        private static final MethodHandle VOLUME_INFO = function("GetVolumeInformationByHandleW", FunctionDescriptor.of(
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        private static final MethodHandle FINAL_PATH = function("GetFinalPathNameByHandleW", FunctionDescriptor.of(
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        private static final long ERROR_OFFSET = Linker.Option.captureStateLayout().byteOffset(
            MemoryLayout.PathElement.groupElement("GetLastError"));
        private static final WindowsFunctions FUNCTIONS = new WindowsFunctions(OPEN, INFO, CLOSE, LEGACY_INFO,
            VOLUME_INFO, FINAL_PATH, Linker.Option.captureStateLayout(), ERROR_OFFSET);

        private static MethodHandle function(String name, FunctionDescriptor descriptor)
        {
            return LINKER.downcallHandle(KERNEL.find(name).orElseThrow(), descriptor,
                Linker.Option.captureCallState("GetLastError"));
        }

    }
}
