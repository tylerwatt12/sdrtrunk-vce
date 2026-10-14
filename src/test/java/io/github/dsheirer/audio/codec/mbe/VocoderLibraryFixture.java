/* Copyright (C) 2026. Licensed under GPL-3.0-or-later. */
package io.github.dsheirer.audio.codec.mbe;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.ToolProvider;

/** A detached library proves worker routing without duplicating JMBE's signal-processing implementation. */
public final class VocoderLibraryFixture
{
    private VocoderLibraryFixture() {}
    public static Path create(Path directory) throws Exception
    {
        Path source = directory.resolve("JMBEAudioLibrary.java");
        Path classes = Files.createDirectories(directory.resolve("classes"));
        Files.writeString(source, """
            package jmbe;
            import java.util.Map;
            import java.util.List;
            import jmbe.iface.*;
            public class JMBEAudioLibrary implements IAudioCodecLibrary {
                public String getVersion() { return "1.0.19"; }
                public int getMajorVersion() { return 1; }
                public int getMinorVersion() { return 0; }
                public int getBuildVersion() { return 19; }
                public boolean supports(String codec) { return false; }
                public IAudioCodec getAudioConverter(String codec) { return null; }
                public Object getVocoderAudioProcessor() { return new Provider(); }
                public static class Provider {
                    public Map<String,Object> getModelInfo() {
                        return Map.of("id","embedded-test", "sha256","a".repeat(64),
                            "input_sample_rate",8000,"output_sample_rate",16000,
                            "maximum_input_samples",960000,"codecs",List.of("IMBE","AMBE_PLUS_2"));
                    }
                    public float[] process(float[] input,String codec,boolean enhance,boolean agc,long[] offsets,long[] durations) {
                        float[] output = new float[input.length * (enhance ? 2 : 1)];
                        for(int i=0;i<output.length;i++) output[i]=Math.max(-.95f,Math.min(.95f,
                            input[i/(enhance?2:1)]*(agc?2:1)));
                        return output;
                    }
                }
            }
            """);
        int status = ToolProvider.getSystemJavaCompiler().run(null, null, null, "-classpath",
            System.getProperty("java.class.path"), "-d", classes.toString(), source.toString());
        if(status != 0) throw new IllegalStateException("Could not build test vocoder library");
        Path jar = directory.resolve("jmbe-1.0.19.jar");
        try(var output = new JarOutputStream(Files.newOutputStream(jar)); var paths = Files.walk(classes))
        {
            for(Path path : paths.filter(Files::isRegularFile).toList())
            {
                output.putNextEntry(new JarEntry(classes.relativize(path).toString().replace('\\','/')));
                Files.copy(path, output); output.closeEntry();
            }
        }
        return jar;
    }
}
