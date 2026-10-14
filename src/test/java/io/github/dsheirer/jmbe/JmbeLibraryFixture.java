package io.github.dsheirer.jmbe;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import javax.tools.ToolProvider;

/** A loadable digital-voice fixture; no real voice decoding or downloaded tools. */
public final class JmbeLibraryFixture
{
    private JmbeLibraryFixture() {}

    public static Path create(Path directory, String version) throws Exception
    {
        Files.createDirectories(directory);
        Path source = directory.resolve("JMBEAudioLibrary.java");
        String[] parts = version.split("\\.");
        Files.writeString(source, """
            package jmbe;
            import jmbe.iface.*;
            public class JMBEAudioLibrary implements IAudioCodecLibrary {
                public String getVersion() { return "%s"; }
                public int getMajorVersion() { return %s; }
                public int getMinorVersion() { return %s; }
                public int getBuildVersion() { return %s; }
                public boolean supports(String name) { return true; }
                public IAudioCodec getAudioConverter(String name) { return new Codec(); }
                public static class Codec implements IAudioCodec {
                    public void setVoiceQualityMetadataEnabled(boolean enabled) {}
                    public String getCodecName() { return "test"; }
                    public float[] getAudio(byte[] frame) { return new float[0]; }
                    public IAudioWithMetadata getAudioWithMetadata(byte[] frame) { return null; }
                    public void reset() {}
                }
            }
            """.formatted(version, parts[0], parts[1], parts[2]));
        Path classes = Files.createDirectories(directory.resolve("classes"));
        if(ToolProvider.getSystemJavaCompiler().run(null, null, null, "-cp", System.getProperty("java.class.path"),
            "-d", classes.toString(), source.toString()) != 0)
        {
            throw new IllegalStateException("JMBE fixture compilation failed");
        }
        Path library = directory.resolve("jmbe-" + version + ".jar");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue("Version", version);
        try(var output = new JarOutputStream(Files.newOutputStream(library), manifest);
            var paths = Files.walk(classes))
        {
            for(Path path: paths.filter(Files::isRegularFile).toList())
            {
                output.putNextEntry(new JarEntry(classes.relativize(path).toString().replace('\\', '/')));
                Files.copy(path, output);
                output.closeEntry();
            }
        }
        return library;
    }
}
