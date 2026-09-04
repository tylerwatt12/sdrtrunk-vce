/*
 * *****************************************************************************
 * Copyright (C) 2014-2025 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 * ****************************************************************************
 */

package io.github.dsheirer.jmbe;

import io.github.dsheirer.jmbe.github.Asset;
import io.github.dsheirer.jmbe.github.GitHub;
import io.github.dsheirer.jmbe.github.Release;
import io.github.dsheirer.util.FileUtil;
import io.github.dsheirer.util.OSType;
import io.github.dsheirer.util.ZipUtility;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates the JMBE library by downloading the JMBE Creator CLI application from GitHub and executing it.
 */
public class JmbeCreator
{
    private static final Logger mLog = LoggerFactory.getLogger(JmbeCreator.class);
    public static final String GITHUB_JMBE_RELEASES_URL = "https://api.github.com/repos/tylerwatt12/jmbe/releases";
    public static final String CREATOR_SCRIPT_LINUX = "creator";
    public static final String CREATOR_SCRIPT_WINDOWS = "creator.bat";
    private static final String OS_LINUX = "linux";
    private static final String OS_OSX = "osx";
    private static final String OS_WINDOWS = "windows";
    private static final String ARCH_AARCH64 = "aarch64";
    private static final String ARCH_ARM32 = "arm32";
    private static final String ARCH_X86_32 = "_32";
    private static final String ARCH_X86_64 = "_64";

    private final AtomicBoolean mCancelled = new AtomicBoolean();
    private static final AtomicBoolean ACTIVE = new AtomicBoolean();
    private final AtomicBoolean mStarted = new AtomicBoolean();
    private volatile Process mProcess;
    private volatile Thread mWorker;
    private final Set<ProcessHandle> mChildren = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Release mRelease;
    private final Path mLibraryPath;

    /**
     * Creates an instance.
     * @param release for the version to create
     * @param library path where the library should be created
     */
    public JmbeCreator(Release release, Path library)
    {
        mRelease = release;
        mLibraryPath = library;
    }

    /**
     * Path to where the library should be created
     */
    public Path getLibraryPath()
    {
        return mLibraryPath;
    }

    /** UI-independent single-use blocking job. Call on a worker, never the UI thread. */
    public Path run(Consumer<String> progress) throws IOException, InterruptedException
    {
        if(!mStarted.compareAndSet(false, true)) throw new IllegalStateException("Create a new job for retry");
        if(!ACTIVE.compareAndSet(false, true)) throw new IllegalStateException("JMBE creation is already running");
        synchronized(this) { mWorker = Thread.currentThread(); }
        Path temporary = null;
        try
        {
            checkCancelled();
            Asset asset = creatorAsset();
            if(asset == null) throw new IOException("No JMBE creator is available for this OS and architecture.");
            Path target = mLibraryPath.toAbsolutePath().normalize();
            Files.createDirectories(target.getParent());
            temporary = Files.createTempDirectory(target.getParent(), ".jmbe-build-");
            progress.accept("Downloading JMBE creator...");
            Path creator = downloadCreator(asset, temporary);
            checkCancelled();
            if(creator == null) throw new IOException("JMBE download failed. Check your connection and retry.");
            progress.accept("Extracting creator...");
            Path unzipped = extractCreator(creator);
            checkCancelled();
            OSType os = OSType.getCurrentOSType();
            if(os.isLinux() || os.isOsx()) restoreCreatorExecutables(unzipped);
            Path script = FileUtil.findFile(unzipped, os.isWindows() ? CREATOR_SCRIPT_WINDOWS : CREATOR_SCRIPT_LINUX);
            if(script == null) throw new IOException("The downloaded creator has no launch script.");
            Path staged = temporary.resolve("library.jar");
            String tag = mRelease.getTagName() == null ? "v" + mRelease.getVersion() : mRelease.getTagName();
            progress.accept("Building JMBE...");
            mProcess = launchCreator(script, staged, tag);
            checkCancelled();
            try(var reader = new BufferedReader(new InputStreamReader(mProcess.getInputStream())))
            {
                char[] buffer = new char[1024];
                int count;
                while((count = reader.read(buffer)) != -1)
                {
                    checkCancelled();
                    progress.accept(new String(buffer, 0, count));
                }
            }
            int code = mProcess.waitFor();
            checkCancelled();
            if(code != 0) throw new IOException("JMBE creator exited with code " + code + ". Retry or choose an existing library.");
            progress.accept("Validating library...");
            JmbeLibraryMetadata.verify(staged, mRelease.getVersion());
            checkCancelled();
            progress.accept("Installing verified library...");
            Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return target;
        }
        finally
        {
            boolean interrupted = Thread.interrupted();
            try
            {
                stopProcess();
                //Cancellation is acknowledged only after child work has stopped, before cleaning its files.
                for(ProcessHandle child: mChildren) child.onExit().join();
                if(mProcess != null) mProcess.onExit().join();
                if(temporary != null)
                {
                    progress.accept("Cleaning temporary build files...");
                    try { FileUtils.deleteDirectory(temporary.toFile()); }
                    catch(IOException e) { progress.accept("Temporary build cleanup failed."); }
                }
            }
            finally
            {
                synchronized(this) { mWorker = null; }
                ACTIVE.set(false);
                if(interrupted) Thread.currentThread().interrupt();
            }
        }
    }

    Asset creatorAsset() { return getJMBECreatorAsset(mRelease); }
    Path downloadCreator(Asset asset, Path destination) throws IOException { return GitHub.downloadArtifact(asset.getDownloadUrl(), destination); }
    Path extractCreator(Path archive) throws IOException { return ZipUtility.unzip(archive); }
    Process launchCreator(Path script, Path staged, String tag) throws IOException
    {
        return new ProcessBuilder(script.toString(), staged.toString(), tag).redirectErrorStream(true).start();
    }

    public void cancel()
    {
        mCancelled.set(true);
        stopProcess();
        //Do not interrupt a cached executor thread after this job has relinquished it.
        synchronized(this) { if(mWorker != null) mWorker.interrupt(); }
    }

    private void checkCancelled() throws InterruptedException
    {
        if(mCancelled.get() || Thread.currentThread().isInterrupted())
            throw new InterruptedException("JMBE creation cancelled");
    }

    private void stopProcess()
    {
        Process process = mProcess;
        if(process != null && process.isAlive())
        {
            process.descendants().forEach(child -> { mChildren.add(child); child.destroyForcibly(); });
            process.destroyForcibly();
        }
    }

    /**
     * Java's ZIP reader does not restore Unix execute bits. Creator distributions need their bin launchers and the
     * JDK process helper to be executable.
     */
    static void restoreCreatorExecutables(Path root) throws IOException
    {
        try(Stream<Path> paths = Files.walk(root))
        {
            for(Path path: paths.filter(Files::isRegularFile).filter(JmbeCreator::isCreatorExecutable).toList())
            {
                Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path);
                permissions.add(PosixFilePermission.OWNER_EXECUTE);
                permissions.add(PosixFilePermission.GROUP_EXECUTE);
                permissions.add(PosixFilePermission.OTHERS_EXECUTE);
                Files.setPosixFilePermissions(path, permissions);
            }
        }
    }

    private static boolean isCreatorExecutable(Path path)
    {
        Path parent = path.getParent();
        return "jspawnhelper".equals(path.getFileName().toString()) ||
            parent != null && parent.getFileName() != null && "bin".equals(parent.getFileName().toString());
    }


    /**
     * Attempts to find the correct JMBE creator for this operating system and architecture for the specified release
     * @param release to find
     * @return JMBE creator asset or null.
     */
    public static Asset getJMBECreatorAsset(Release release)
    {
        OSType osType = OSType.getCurrentOSType();

        if(release != null)
        {
            for(Asset asset: release.getAssets())
            {
                if(isCorrectAsset(asset, osType))
                {
                    return asset;
                }
            }
        }

        mLog.error("Unable to find JMBE Creator asset for OS ["  + System.getProperty("os.name") +
                "] arch [" + System.getProperty("os.arch") +
                "] version [" + System.getProperty("os.version") + "]");

        return null;
    }

    /**
     * Indicates if the asset is correct for the host operating system and architecture
     * @param asset to check
     * @param osType for the current host (OS & architecture)
     * @return true if the asset is correct for this host.
     */
    public static boolean isCorrectAsset(Asset asset, OSType osType)
    {
        if(isJMBECreator(asset))
        {
            String name = asset.getName();

            switch(osType)
            {
                case LINUX_AARCH_64:
                    return name.contains(OS_LINUX) && name.contains(ARCH_AARCH64);
                case LINUX_ARM_32:
                    return name.contains(OS_LINUX) && name.contains(ARCH_ARM32);
                case LINUX_X86_32:
                    return name.contains(OS_LINUX) && name.contains(ARCH_X86_32);
                case LINUX_X86_64:
                    return name.contains(OS_LINUX) && name.contains(ARCH_X86_64);
                case OSX_AARCH_64:
                    return name.contains(OS_OSX) && name.contains(ARCH_AARCH64);
                case OSX_X86_64:
                    return name.contains(OS_OSX) && name.contains(ARCH_X86_64);
                case WINDOWS_AARCH_64:
                    return name.contains(OS_WINDOWS) && name.contains(ARCH_AARCH64);
                case WINDOWS_X86_32:
                    return name.contains(OS_WINDOWS) && name.contains(ARCH_X86_32);
                case WINDOWS_X86_64:
                    return name.contains(OS_WINDOWS) && name.contains(ARCH_X86_64);
                case UNKNOWN:
                default:
                    return false;
            }
        }

        return false;
    }

    /**
     * Indicates if the GitHub asset has a non-null asset name and is a JMBE Creator asset
     */
    private static boolean isJMBECreator(Asset asset)
    {
        return asset.getName() != null && asset.getName().startsWith("jmbe-creator");
    }

}
