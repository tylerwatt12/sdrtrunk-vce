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

package io.github.dsheirer.database.upgrade;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.DosFileAttributes;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.FileOwnerAttributeView;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserDefinedFileAttributeView;
import java.nio.file.attribute.UserPrincipal;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Captures and reapplies the access-control attributes that the local filesystem exposes through standard Java file
 * attribute views. Basic timestamps are intentionally excluded because installing a migrated database is a real file
 * update.
 */
final class FileAccessAttributeSnapshot
{
    private static final int MAX_USER_ATTRIBUTE_BYTES = 1024 * 1024;
    private static final int MAX_TOTAL_USER_ATTRIBUTE_BYTES = 16 * MAX_USER_ATTRIBUTE_BYTES;
    private final PosixSnapshot mPosix;
    private final AclSnapshot mAcl;
    private final UserPrincipal mOwner;
    private final DosSnapshot mDos;
    private final Map<String, byte[]> mUserDefined;

    private FileAccessAttributeSnapshot(PosixSnapshot posix, AclSnapshot acl, UserPrincipal owner, DosSnapshot dos,
                                        Map<String, byte[]> userDefined)
    {
        mPosix = posix;
        mAcl = acl;
        mOwner = owner;
        mDos = dos;
        mUserDefined = userDefined;
    }

    static FileAccessAttributeSnapshot capture(Path path) throws IOException
    {
        Path normalized = requireOrdinaryPath(path);
        PosixSnapshot posix = null;
        PosixFileAttributeView posixView = view(normalized, PosixFileAttributeView.class);

        if(posixView != null)
        {
            PosixFileAttributes attributes = posixView.readAttributes();
            posix = new PosixSnapshot(attributes.owner(), attributes.group(), Set.copyOf(attributes.permissions()));
        }

        AclSnapshot acl = null;
        AclFileAttributeView aclView = view(normalized, AclFileAttributeView.class);

        if(aclView != null)
        {
            acl = new AclSnapshot(aclView.getOwner(), List.copyOf(aclView.getAcl()));
        }

        UserPrincipal owner = null;

        if(posix == null && acl == null)
        {
            FileOwnerAttributeView ownerView = view(normalized, FileOwnerAttributeView.class);

            if(ownerView != null)
            {
                owner = ownerView.getOwner();
            }
        }

        DosSnapshot dos = null;
        DosFileAttributeView dosView = view(normalized, DosFileAttributeView.class);

        if(dosView != null)
        {
            DosFileAttributes attributes = dosView.readAttributes();
            dos = new DosSnapshot(attributes.isArchive(), attributes.isHidden(), attributes.isReadOnly(),
                attributes.isSystem());
        }

        Map<String, byte[]> userDefined = new LinkedHashMap<>();
        UserDefinedFileAttributeView userView = view(normalized, UserDefinedFileAttributeView.class);

        if(userView != null)
        {
            int totalBytes = 0;

            for(String name: userView.list().stream().sorted().toList())
            {
                byte[] value = readUserAttribute(userView, name);
                totalBytes = Math.addExact(totalBytes, value.length);

                if(totalBytes > MAX_TOTAL_USER_ATTRIBUTE_BYTES)
                {
                    throw new IOException("User-defined file attributes exceed the safe migration limit.");
                }

                userDefined.put(name, value);
            }
        }

        return new FileAccessAttributeSnapshot(posix, acl, owner, dos, Map.copyOf(userDefined));
    }

    /**
     * Applies every captured attribute and reads it back before the caller replaces any live path.
     */
    void applyTo(Path path) throws IOException
    {
        Path normalized = requireOrdinaryPath(path);

        try
        {
            applyUserDefined(normalized);
            applyPosix(normalized);
            applyAcl(normalized);
            applyOwner(normalized);
            applyDos(normalized);
            verify(normalized);
        }
        catch(IOException e)
        {
            throw new IOException("Unable to preserve filesystem access attributes on " + normalized + ": " +
                e.getMessage(), e);
        }
        catch(UnsupportedOperationException | SecurityException e)
        {
            throw new IOException("Unable to preserve filesystem access attributes on " + normalized + ".", e);
        }
    }

    private void applyUserDefined(Path path) throws IOException
    {
        if(mUserDefined.isEmpty())
        {
            return;
        }

        UserDefinedFileAttributeView userView = requireView(path, UserDefinedFileAttributeView.class,
            "user-defined attributes");

        for(Map.Entry<String, byte[]> entry: mUserDefined.entrySet())
        {
            byte[] value = entry.getValue();
            int written = userView.write(entry.getKey(), ByteBuffer.wrap(value));

            if(written != value.length)
            {
                throw new IOException("The user-defined attribute " + entry.getKey() + " was not written fully.");
            }
        }
    }

    private void applyPosix(Path path) throws IOException
    {
        if(mPosix == null)
        {
            return;
        }

        PosixFileAttributeView posixView = requireView(path, PosixFileAttributeView.class, "POSIX permissions");
        posixView.setGroup(mPosix.group());
        posixView.setPermissions(mPosix.permissions());
        posixView.setOwner(mPosix.owner());
    }

    private void applyAcl(Path path) throws IOException
    {
        if(mAcl == null)
        {
            return;
        }

        AclFileAttributeView aclView = requireView(path, AclFileAttributeView.class, "access-control list");
        aclView.setAcl(mAcl.entries());
        aclView.setOwner(mAcl.owner());
    }

    private void applyOwner(Path path) throws IOException
    {
        if(mOwner == null)
        {
            return;
        }

        requireView(path, FileOwnerAttributeView.class, "file owner").setOwner(mOwner);
    }

    private void applyDos(Path path) throws IOException
    {
        if(mDos == null)
        {
            return;
        }

        DosFileAttributeView dosView = requireView(path, DosFileAttributeView.class, "DOS flags");
        //Read-only is last so it cannot prevent the other captured attributes from being restored.
        dosView.setArchive(mDos.archive());
        dosView.setHidden(mDos.hidden());
        dosView.setSystem(mDos.system());
        dosView.setReadOnly(mDos.readOnly());
    }

    private void verify(Path path) throws IOException
    {
        if(mPosix != null)
        {
            PosixFileAttributes actual = requireView(path, PosixFileAttributeView.class, "POSIX permissions")
                .readAttributes();

            if(!Objects.equals(mPosix.owner(), actual.owner()) ||
                !Objects.equals(mPosix.group(), actual.group()) ||
                !mPosix.permissions().equals(actual.permissions()))
            {
                throw new IOException("POSIX permissions, owner, or group did not match after they were applied.");
            }
        }

        if(mAcl != null)
        {
            AclFileAttributeView aclView = requireView(path, AclFileAttributeView.class, "access-control list");

            if(!Objects.equals(mAcl.owner(), aclView.getOwner()) || !mAcl.entries().equals(aclView.getAcl()))
            {
                throw new IOException("The access-control list or owner did not match after it was applied.");
            }
        }

        if(mOwner != null)
        {
            UserPrincipal actual = requireView(path, FileOwnerAttributeView.class, "file owner").getOwner();

            if(!Objects.equals(mOwner, actual))
            {
                throw new IOException("The file owner did not match after it was applied.");
            }
        }

        if(mDos != null)
        {
            DosFileAttributes actual = requireView(path, DosFileAttributeView.class, "DOS flags").readAttributes();

            if(mDos.archive() != actual.isArchive() || mDos.hidden() != actual.isHidden() ||
                mDos.readOnly() != actual.isReadOnly() || mDos.system() != actual.isSystem())
            {
                throw new IOException("The DOS flags did not match after they were applied.");
            }
        }

        if(!mUserDefined.isEmpty())
        {
            UserDefinedFileAttributeView userView = requireView(path, UserDefinedFileAttributeView.class,
                "user-defined attributes");

            for(Map.Entry<String, byte[]> entry: mUserDefined.entrySet())
            {
                if(!Arrays.equals(entry.getValue(), readUserAttribute(userView, entry.getKey())))
                {
                    throw new IOException("The user-defined attribute " + entry.getKey() +
                        " did not match after it was applied.");
                }
            }
        }
    }

    private static byte[] readUserAttribute(UserDefinedFileAttributeView view, String name) throws IOException
    {
        int expected = view.size(name);

        if(expected < 0 || expected > MAX_USER_ATTRIBUTE_BYTES)
        {
            throw new IOException("The user-defined attribute " + name + " exceeds the safe migration limit.");
        }

        ByteBuffer buffer = ByteBuffer.allocate(expected);
        int read = view.read(name, buffer);

        if(read != expected)
        {
            throw new IOException("The user-defined attribute " + name + " changed while it was being read.");
        }

        buffer.flip();
        byte[] value = new byte[buffer.remaining()];
        buffer.get(value);
        return value;
    }

    private static Path requireOrdinaryPath(Path path) throws IOException
    {
        Path normalized = path.toAbsolutePath().normalize();

        if(Files.isSymbolicLink(normalized))
        {
            throw new IOException("Refusing to read or apply access attributes through a symbolic link: " +
                normalized);
        }

        if(!Files.exists(normalized, LinkOption.NOFOLLOW_LINKS))
        {
            throw new IOException("Filesystem path does not exist: " + normalized);
        }

        return normalized;
    }

    private static <V extends FileAttributeView> V view(Path path, Class<V> type)
    {
        return Files.getFileAttributeView(path, type, LinkOption.NOFOLLOW_LINKS);
    }

    private static <V extends FileAttributeView> V requireView(Path path, Class<V> type, String label)
        throws IOException
    {
        V view = view(path, type);

        if(view == null)
        {
            throw new IOException("The target filesystem no longer exposes " + label + ".");
        }

        return view;
    }

    private record PosixSnapshot(UserPrincipal owner, GroupPrincipal group, Set<PosixFilePermission> permissions)
    {
    }

    private record AclSnapshot(UserPrincipal owner, List<AclEntry> entries)
    {
    }

    private record DosSnapshot(boolean archive, boolean hidden, boolean readOnly, boolean system)
    {
    }
}
