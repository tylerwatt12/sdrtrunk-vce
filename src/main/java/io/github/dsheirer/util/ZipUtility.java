package io.github.dsheirer.util;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * This utility compresses a list of files to standard ZIP format file.
 * It is able to compress all sub files and sub directories, recursively.
 *
 * @author www.codejava.net
 */
public class ZipUtility
{
    /**
     * A constants for buffer size used to read/write data
     */
    private static final int BUFFER_SIZE = 4096;

    /**
     * Compresses a list of files to a destination zip file
     *
     * @param listFiles A collection of files and directories
     * @param destZipFile The path of the destination zip file
     * @throws IOException
     */
    public void zip(List<File> listFiles, String destZipFile) throws IOException
    {
        try(ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(destZipFile)))
        {
            for(File file : listFiles)
            {
                if(file.isDirectory())
                {
                    zipDirectory(file, file.getName(), zos);
                }
                else
                {
                    zipFile(file, zos);
                }
            }
            zos.flush();
        }
    }

    /**
     * Compresses files represented in an array of paths
     *
     * @param files a String array containing file paths
     * @param destZipFile The path of the destination zip file
     * @throws IOException
     */
    public void zip(String[] files, String destZipFile) throws IOException
    {
        List<File> listFiles = new ArrayList<>();

        for(int i = 0; i < files.length; i++)
        {
            listFiles.add(new File(files[i]));
        }

        zip(listFiles, destZipFile);
    }

    /**
     * Adds a directory to the current zip output stream
     *
     * @param folder the directory to be  added
     * @param parentFolder the path of parent directory
     * @param zos the current zip output stream
     * @throws IOException
     */
    private void zipDirectory(File folder, String parentFolder, ZipOutputStream zos) throws IOException
    {
        if(folder == null || folder.listFiles() == null)
        {
            return;
        }

        for(File file : folder.listFiles())
        {
            if(file.exists())
            {
                if(file.isDirectory())
                {
                    zipDirectory(file, parentFolder + "/" + file.getName(), zos);
                    continue;
                }

                zos.putNextEntry(new ZipEntry(parentFolder + "/" + file.getName()));
                try(BufferedInputStream bis = new BufferedInputStream(new FileInputStream(file)))
                {
                    byte[] bytesIn = new byte[BUFFER_SIZE];
                    int read = 0;
                    while((read = bis.read(bytesIn)) != -1)
                    {
                        zos.write(bytesIn, 0, read);
                    }
                }
                zos.closeEntry();
            }
        }
    }

    /**
     * Adds a file to the current zip output stream
     *
     * @param file the file to be added
     * @param zos the current zip output stream
     * @throws IOException
     */
    private void zipFile(File file, ZipOutputStream zos) throws IOException
    {
        zos.putNextEntry(new ZipEntry(file.getName()));
        try(BufferedInputStream bis = new BufferedInputStream(new FileInputStream(file)))
        {
            byte[] bytesIn = new byte[BUFFER_SIZE];
            int read = 0;
            while((read = bis.read(bytesIn)) != -1)
            {
                zos.write(bytesIn, 0, read);
            }
        }
        zos.closeEntry();
    }

    /**
     * Unzips the specified file into the directory where it's located
     *
     * @param zipFile to unzip
     * @return path to where the file was unzipped.
     * @throws IOException if there are errors unzipping
     */
    public static Path unzip(Path zipFile) throws IOException
    {
        Path parent = zipFile.toAbsolutePath().normalize().getParent();

        if(parent == null)
        {
            throw new IOException("ZIP file has no parent directory: " + zipFile);
        }

        try(ZipInputStream zipInputStream = new ZipInputStream(
            new BufferedInputStream(Files.newInputStream(zipFile))))
        {
            ZipEntry entry;

            while((entry = zipInputStream.getNextEntry()) != null)
            {
                Path target = parent.resolve(entry.getName()).normalize();

                if(!target.startsWith(parent))
                {
                    throw new IOException("ZIP entry is outside the destination directory: " + entry.getName());
                }

                if(entry.isDirectory())
                {
                    Files.createDirectories(target);
                }
                else
                {
                    Path targetParent = target.getParent();

                    if(targetParent != null)
                    {
                        Files.createDirectories(targetParent);
                    }

                    Files.copy(zipInputStream, target, StandardCopyOption.REPLACE_EXISTING);
                }

                zipInputStream.closeEntry();
            }
        }

        return parent;
    }
}
