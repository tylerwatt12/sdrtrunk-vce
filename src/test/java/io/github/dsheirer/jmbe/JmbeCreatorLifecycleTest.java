package io.github.dsheirer.jmbe;

import com.google.gson.JsonObject;
import io.github.dsheirer.jmbe.github.Asset;
import io.github.dsheirer.jmbe.github.Release;
import io.github.dsheirer.jmbe.github.Version;
import java.io.*;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.jar.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class JmbeCreatorLifecycleTest
{
    @TempDir Path directory;

    @Test void failuresPreserveWorkingLibraryAndRetryCreatesANewJob() throws Exception
    {
        Path target=directory.resolve("jmbe.jar"); writeJar(target);
        byte[] original=Files.readAllBytes(target);
        for(String failure:List.of("download","extract","build","validate"))
        {
            Fake creator=new Fake(target,failure);
            assertThrows(IOException.class,()->creator.run(s->{}));
            assertArrayEquals(original,Files.readAllBytes(target),failure);
            try(var files=Files.list(directory)) { assertEquals(1,files.count()); }
        }
        List<String> stages=new ArrayList<>();
        Fake retry=new Fake(target,"");
        assertEquals(target,retry.run(stages::add));
        assertTrue(JmbeLibraryMetadata.isSupported(target));
        for(String stage:List.of("Downloading","Extracting","Building","Validating","Installing","Cleaning"))
            assertTrue(stages.stream().anyMatch(s->s.startsWith(stage)),stage);
        assertThrows(IllegalStateException.class,()->retry.run(s->{}));
    }

    @Test void cancellationStopsChildAndPreservesWorkingLibrary() throws Exception
    {
        Path target=directory.resolve("jmbe.jar"); writeJar(target); byte[] original=Files.readAllBytes(target);
        Fake creator=new Fake(target,"wait");
        try(var executor=Executors.newSingleThreadExecutor())
        {
            var future=executor.submit(()-> { try { creator.run(s->{}); return false; } catch(InterruptedException e) { return true; } });
            assertTrue(creator.launched.await(5,TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class,()->new Fake(target,"").run(s->{}));
            creator.cancel();
            assertTrue(future.get(5,TimeUnit.SECONDS));
            assertFalse(creator.child.isAlive());
            assertArrayEquals(original,Files.readAllBytes(target));
        }
        finally { creator.cancel(); }
    }

    private static void writeJar(Path path) throws IOException
    {
        Manifest manifest=new Manifest(); manifest.getMainAttributes().putValue("Manifest-Version","1.0"); manifest.getMainAttributes().putValue("Version","1.0.14");
        try(var jar=new JarOutputStream(Files.newOutputStream(path),manifest))
        { jar.putNextEntry(new JarEntry("jmbe/JMBEAudioLibrary.class")); jar.write(0); jar.closeEntry(); }
    }
    private static Release release()
    {
        JsonObject object=new JsonObject(); object.addProperty("tag_name","v1.0.14");
        return new Release(Version.fromString("1.0.14"),object);
    }
    private static class Fake extends JmbeCreator
    {
        final String failure; final CountDownLatch launched=new CountDownLatch(1); Child child;
        Fake(Path target,String failure) { super(release(),target); this.failure=failure; }
        @Override Asset creatorAsset() { return new Asset(new JsonObject()); }
        @Override Path downloadCreator(Asset asset,Path target) throws IOException
        {
            if(failure.equals("download")) throw new IOException("download failed");
            Files.writeString(target.resolve(CREATOR_SCRIPT_LINUX),"fake");
            Files.writeString(target.resolve(CREATOR_SCRIPT_WINDOWS),"fake");
            return target;
        }
        @Override Path extractCreator(Path archive) throws IOException
        { if(failure.equals("extract")) throw new IOException("extraction failed"); return archive; }
        @Override Process launchCreator(Path script,Path staged,String tag) throws IOException
        {
            if(failure.equals("build")) throw new IOException("launch failed");
            if(failure.equals("validate")) Files.writeString(staged,"invalid jar"); else writeJar(staged);
            child=new Child(failure.equals("wait")); launched.countDown(); return child;
        }
    }
    private static class Child extends Process
    {
        volatile boolean alive; final CompletableFuture<Process> exit=new CompletableFuture<>();
        Child(boolean wait) { alive=wait; if(!wait) exit.complete(this); }
        public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
        public InputStream getErrorStream() { return InputStream.nullInputStream(); }
        public InputStream getInputStream()
        {
            return new InputStream() { public int read() throws IOException {
                while(alive) { try { Thread.sleep(10); } catch(InterruptedException e) { Thread.currentThread().interrupt(); return -1; } } return -1;
            }};
        }
        public int waitFor() throws InterruptedException { while(alive) Thread.sleep(10); return 0; }
        public int exitValue() { if(alive) throw new IllegalThreadStateException(); return 0; }
        public void destroy() { alive=false; exit.complete(this); }
        public Process destroyForcibly() { destroy(); return this; }
        public boolean isAlive() { return alive; }
        public Stream<ProcessHandle> descendants() { return Stream.empty(); }
        public CompletableFuture<Process> onExit() { return exit; }
    }
}
