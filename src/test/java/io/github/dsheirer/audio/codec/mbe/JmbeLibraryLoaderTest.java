package io.github.dsheirer.audio.codec.mbe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import javax.tools.ToolProvider;
import jmbe.iface.IAudioCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JmbeLibraryLoaderTest
{
    @TempDir
    Path mTemporaryDirectory;

    @Test
    void changedLibraryCreatesCodecFromNewClassLoader() throws Exception
    {
        Path firstLibrary = createLibrary("1.0.14", 14.0f);
        Path secondLibrary = createLibrary("1.0.15", 15.0f);

        try(JmbeLibraryLoader loader = new JmbeLibraryLoader(getClass().getClassLoader(), false))
        {
            IAudioCodec first = loader.getAudioCodec(firstLibrary, "TEST");
            IAudioCodec second = loader.getAudioCodec(secondLibrary, "TEST");

            assertNotNull(first);
            assertNotNull(second);
            assertEquals(14.0f, first.getAudio(new byte[0])[0]);
            assertEquals(15.0f, second.getAudio(new byte[0])[0]);
            assertNotSame(first.getClass().getClassLoader(), second.getClass().getClassLoader());
        }
    }

    @Test
    void rejectsLibraryWithoutVoiceQualityDiagnostics() throws Exception
    {
        Path oldLibrary = createLibrary("1.0.13", 13.0f);

        try(JmbeLibraryLoader loader = new JmbeLibraryLoader(getClass().getClassLoader(), false))
        {
            assertNull(loader.getAudioCodec(oldLibrary, "TEST"));
        }
    }

    private Path createLibrary(String version, float marker) throws Exception
    {
        Path sourceDirectory = Files.createDirectories(mTemporaryDirectory.resolve("source-" + version));
        Path classDirectory = Files.createDirectories(mTemporaryDirectory.resolve("classes-" + version));
        Path source = sourceDirectory.resolve("JMBEAudioLibrary.java");
        Files.writeString(source, source(version, marker));

        var compiler = ToolProvider.getSystemJavaCompiler();

        try(var fileManager = compiler.getStandardFileManager(null, null, null))
        {
            var units = fileManager.getJavaFileObjects(source);
            boolean compiled = compiler.getTask(null, fileManager, null,
                List.of("-classpath", System.getProperty("java.class.path"), "-d", classDirectory.toString()),
                null, units).call();
            assertTrue(compiled);
        }

        Path library = mTemporaryDirectory.resolve("jmbe-" + version + ".jar");

        try(JarOutputStream output = new JarOutputStream(Files.newOutputStream(library));
            Stream<Path> classes = Files.walk(classDirectory))
        {
            for(Path path: classes.filter(Files::isRegularFile).toList())
            {
                output.putNextEntry(new JarEntry(classDirectory.relativize(path).toString().replace('\\', '/')));
                Files.copy(path, output);
                output.closeEntry();
            }
        }

        return library;
    }

    private static String source(String version, float marker)
    {
        int build = Integer.parseInt(version.substring(version.lastIndexOf('.') + 1));
        return """
            package jmbe;
            import jmbe.iface.IAudioCodec;
            import jmbe.iface.IAudioCodecLibrary;
            import jmbe.iface.IAudioWithMetadata;
            public class JMBEAudioLibrary implements IAudioCodecLibrary {
                public String getVersion() { return "%s"; }
                public int getMajorVersion() { return 1; }
                public int getMinorVersion() { return 0; }
                public int getBuildVersion() { return %d; }
                public boolean supports(String codec) { return "TEST".equals(codec); }
                public IAudioCodec getAudioConverter(String codec) {
                    return new IAudioCodec() {
                        public String getCodecName() { return codec; }
                        public float[] getAudio(byte[] frame) { return new float[]{%sf}; }
                        public IAudioWithMetadata getAudioWithMetadata(byte[] frame) { return null; }
                        public void reset() {}
                    };
                }
            }
            """.formatted(version, build, marker);
    }
}
