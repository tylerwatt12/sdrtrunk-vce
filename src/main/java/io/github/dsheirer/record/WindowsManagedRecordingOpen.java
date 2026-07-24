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
package io.github.dsheirer.record;

import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinBase.FILE_ATTRIBUTE_TAG_INFO;
import com.sun.jna.platform.win32.WinDef.DWORD;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Windows containment strategy used when the platform provider cannot supply a secure directory stream.
 *
 * <p>Each real directory from the recording root to the media parent is opened without delete sharing and with
 * reparse-point following disabled. Holding those handles prevents a verified directory from being renamed or
 * replaced until the final no-follow file channel is open.</p>
 */
final class WindowsManagedRecordingOpen
{
    private static final int DIRECTORY_SHARE_MODE = WinNT.FILE_SHARE_READ | WinNT.FILE_SHARE_WRITE;
    private static final int DIRECTORY_OPEN_FLAGS =
        WinNT.FILE_FLAG_BACKUP_SEMANTICS | WinNT.FILE_FLAG_OPEN_REPARSE_POINT;

    private WindowsManagedRecordingOpen()
    {
    }

    static Optional<SeekableByteChannel> open(Path realRoot, Path candidate, Path relative,
                                               long expectedByteSize, RecordFormat expectedFormat)
        throws IOException
    {
        List<HANDLE> directoryHandles = new ArrayList<>(relative.getNameCount());
        SeekableByteChannel channel = null;

        try
        {
            Path directory = realRoot;

            if(!lockRealDirectory(directory, directoryHandles))
            {
                return Optional.empty();
            }

            for(int index = 0; index < relative.getNameCount() - 1; index++)
            {
                directory = directory.resolve(relative.getName(index));

                if(!lockRealDirectory(directory, directoryHandles))
                {
                    return Optional.empty();
                }
            }

            Optional<ManagedRecordingPath> inspected = ManagedRecordingPath.inspect(realRoot, candidate);

            if(inspected.isEmpty() || !inspected.get().relativePath().equals(relative) ||
                inspected.get().format() != expectedFormat)
            {
                return Optional.empty();
            }

            channel = Files.newByteChannel(candidate,
                Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));

            if(channel.size() != expectedByteSize)
            {
                channel.close();
                return Optional.empty();
            }

            SeekableByteChannel opened = channel;
            channel = null;
            return Optional.of(opened);
        }
        catch(IOException | RuntimeException exception)
        {
            if(channel != null)
            {
                try
                {
                    channel.close();
                }
                catch(IOException closeException)
                {
                    exception.addSuppressed(closeException);
                }
            }

            return Optional.empty();
        }
        finally
        {
            for(int index = directoryHandles.size() - 1; index >= 0; index--)
            {
                Kernel32.INSTANCE.CloseHandle(directoryHandles.get(index));
            }
        }
    }

    private static boolean lockRealDirectory(Path directory, List<HANDLE> handles)
    {
        HANDLE handle = Kernel32.INSTANCE.CreateFile(directory.toString(), WinNT.FILE_READ_ATTRIBUTES,
            DIRECTORY_SHARE_MODE, null, WinNT.OPEN_EXISTING, DIRECTORY_OPEN_FLAGS, null);

        if(handle == null || WinBase.INVALID_HANDLE_VALUE.equals(handle))
        {
            return false;
        }

        FILE_ATTRIBUTE_TAG_INFO information = new FILE_ATTRIBUTE_TAG_INFO();
        boolean read = Kernel32.INSTANCE.GetFileInformationByHandleEx(handle, WinBase.FileAttributeTagInfo,
            information.getPointer(), new DWORD(information.size()));
        information.read();
        boolean directoryAttribute = (information.FileAttributes & WinNT.FILE_ATTRIBUTE_DIRECTORY) != 0;
        boolean reparsePoint = (information.FileAttributes & WinNT.FILE_ATTRIBUTE_REPARSE_POINT) != 0;

        if(!read || !directoryAttribute || reparsePoint)
        {
            Kernel32.INSTANCE.CloseHandle(handle);
            return false;
        }

        handles.add(handle);
        return true;
    }
}
