package io.github.dsheirer.audio.codec.mbe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void rejectsOlderLibraryVersion() throws Exception
    {
        Path oldLibrary = createLibrary("1.0.13", 13.0f);

        try(JmbeLibraryLoader loader = new JmbeLibraryLoader(getClass().getClassLoader(), false))
        {
            assertNull(loader.getAudioCodec(oldLibrary, "TEST"));
        }
    }

    @Test
    void compatibilityCheckRejectsMissingCapabilitiesWithoutReplacingTheActiveLibrary() throws Exception
    {
        Path working = createLibrary("1.0.14", 14.0f);
        Path incompatible = createLibrary("1.0.15", 15.0f, false);
        IAudioCodec codec = JmbeLibraryLoader.getInstance().getAudioCodec(working, "TEST");
        try
        {
            JmbeLibraryLoader.validateLibrary(working);
            assertThrows(java.io.IOException.class, () -> JmbeLibraryLoader.validateLibrary(incompatible));
            IAudioCodec unchanged = JmbeLibraryLoader.getInstance().getAudioCodec(working, "TEST");
            assertSame(codec.getClass().getClassLoader(), unchanged.getClass().getClassLoader());
            assertEquals(14.0f, unchanged.getAudio(new byte[0])[0]);
        }
        finally { JmbeLibraryLoader.getInstance().close(); }
    }

    @Test
    void rejectsLibraryWithoutVoiceQualityCapability() throws Exception
    {
        Path incompleteLibrary = createLibrary("1.0.14", 14.0f, false);

        try(JmbeLibraryLoader loader = new JmbeLibraryLoader(getClass().getClassLoader(), false))
        {
            assertNull(loader.getAudioCodec(incompleteLibrary, "TEST"));
        }
    }

    @Test
    void acceptsLibraryWithoutOptionalToneAudioCapability() throws Exception
    {
        Path library = createLibrary("1.0.14", 14.0f, true, false);

        try(JmbeLibraryLoader loader = new JmbeLibraryLoader(getClass().getClassLoader(), false))
        {
            assertNotNull(loader.getAudioCodec(library, "TEST", false));
        }
    }

    @Test
    void configuresOptionalToneAudioCapability() throws Exception
    {
        Path library = createLibrary("1.0.15", 15.0f, true, true);

        try(JmbeLibraryLoader loader = new JmbeLibraryLoader(getClass().getClassLoader(), false))
        {
            IAudioCodec enabled = loader.getAudioCodec(library, "TEST", true);
            IAudioCodec muted = loader.getAudioCodec(library, "TEST", false);

            assertEquals(15.0f, enabled.getAudio(new byte[0])[0]);
            assertEquals(-15.0f, muted.getAudio(new byte[0])[0]);
        }
    }

    private Path createLibrary(String version, float marker) throws Exception
    {
        return createLibrary(version, marker, true);
    }

    private Path createLibrary(String version, float marker, boolean includeVoiceQualityCapability) throws Exception
    {
        return createLibrary(version, marker, includeVoiceQualityCapability, false);
    }

    private Path createLibrary(String version, float marker, boolean includeVoiceQualityCapability,
                               boolean includeToneAudioCapability) throws Exception
    {
        return createLibrary(mTemporaryDirectory, version, marker, includeVoiceQualityCapability,
            includeToneAudioCapability, false);
    }

    static Path createLibrary(Path directory, String version, float marker, boolean includeVoiceQualityCapability,
                              boolean includeToneAudioCapability, boolean includeCorrectedFingerprint) throws Exception
    {
        Path sourceDirectory = Files.createDirectories(directory.resolve("source-" + version));
        Path classDirectory = Files.createDirectories(directory.resolve("classes-" + version));
        Path source = sourceDirectory.resolve("JMBEAudioLibrary.java");
        Files.writeString(source, source(version, marker, includeVoiceQualityCapability, includeToneAudioCapability,
            includeCorrectedFingerprint));

        var compiler = ToolProvider.getSystemJavaCompiler();

        try(var fileManager = compiler.getStandardFileManager(null, null, null))
        {
            var units = fileManager.getJavaFileObjects(source);
            boolean compiled = compiler.getTask(null, fileManager, null,
                List.of("-classpath", System.getProperty("java.class.path"), "-d", classDirectory.toString()),
                null, units).call();
            assertTrue(compiled);
        }

        Path library = directory.resolve("jmbe-" + version + ".jar");

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

    private static String source(String version, float marker, boolean includeVoiceQualityCapability,
                                 boolean includeToneAudioCapability, boolean includeCorrectedFingerprint)
    {
        int build = Integer.parseInt(version.substring(version.lastIndexOf('.') + 1));
        String voiceQualityCapability = includeVoiceQualityCapability ?
            "public void setVoiceQualityMetadataEnabled(boolean enabled) { qualityMetadataEnabled = enabled; }" : "";
        String toneAudioCapability = includeToneAudioCapability ?
            "public void setToneAudioEnabled(boolean enabled) { toneAudioEnabled = enabled; }" : "";
        String correctedFingerprintCapability = includeCorrectedFingerprint ? """
            public void setCorrectedVoiceFrameFingerprintEnabled(boolean enabled) {
                correctedFingerprintEnabled = enabled;
                fingerprintEnableCalls++;
            }
            public long getCorrectedVoiceFrameFingerprintV1() { return correctedFingerprint; }
            public int getFingerprintEnableCalls() { return fingerprintEnableCalls; }
            """ : "";
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
                public boolean supports(String codec) {
                    return "TEST".equals(codec) || "IMBE".equals(codec) || "AMBE 3600 x 2450".equals(codec);
                }
                public IAudioCodec getAudioConverter(String codec) {
                    return new TestCodec(codec);
                }
                public static class TestCodec implements IAudioCodec {
                    private final String codec;
                    private boolean qualityMetadataEnabled;
                    private boolean toneAudioEnabled = true;
                    private boolean correctedFingerprintEnabled;
                    private int fingerprintEnableCalls;
                    private long correctedFingerprint;
                    public TestCodec(String codec) { this.codec = codec; }
                    %s
                    %s
                    %s
                    public String getCodecName() { return codec; }
                    public float[] getAudio(byte[] frame) {
                        correctedFingerprint = correctedFingerprintEnabled ?
                            1L + (frame.length == 0 ? 0 : (frame[0] & 0xFF)) : 0L;
                        return new float[]{qualityMetadataEnabled && toneAudioEnabled ? %sf : -%sf};
                    }
                    public IAudioWithMetadata getAudioWithMetadata(byte[] frame) { return null; }
                    public void reset() { correctedFingerprint = 0L; }
                }
            }
            """.formatted(version, build, voiceQualityCapability, toneAudioCapability,
                correctedFingerprintCapability, marker, marker);
    }
}
