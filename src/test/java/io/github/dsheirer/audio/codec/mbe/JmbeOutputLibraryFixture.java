/* Copyright (C) 2026. Licensed under GPL-3.0-or-later. */
package io.github.dsheirer.audio.codec.mbe;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.ToolProvider;

/** Optional output capability with an ordinary version: callers must use the returned format, not version detection. */
public final class JmbeOutputLibraryFixture
{
    private JmbeOutputLibraryFixture() {}
    public static Path create(Path directory) throws Exception
    {
        Files.createDirectories(directory);
        Path source = directory.resolve("JMBEAudioLibrary.java");
        Path classes = Files.createDirectories(directory.resolve("classes"));
        Files.writeString(source, """
            package jmbe;
            import java.util.Map;
            import jmbe.iface.*;
            public class JMBEAudioLibrary implements IAudioCodecLibrary {
                public String getVersion() { return "1.0.17"; }
                public int getMajorVersion() { return 1; }
                public int getMinorVersion() { return 0; }
                public int getBuildVersion() { return 17; }
                public boolean supports(String codec) { return false; }
                public IAudioCodec getAudioConverter(String codec) { return null; }
                public Object getAudioOutputProcessor() { return new Provider(); }
                public static class Provider {
                    public Map<String,Object> process(float[] input,int rate,String codec) {
                        if(rate!=8000) throw new IllegalArgumentException();
                        float[] output = new float[input.length * 2];
                        for(int i=0;i<output.length;i++) output[i]=input[i/2];
                        return Map.of("samples",output,"sample_rate",16000);
                    }
                }
            }
            """);
        int status = ToolProvider.getSystemJavaCompiler().run(null, null, null, "-classpath",
            System.getProperty("java.class.path"), "-d", classes.toString(), source.toString());
        if(status != 0) throw new IllegalStateException("Could not build test output library");
        Path jar = directory.resolve("jmbe-1.0.17.jar");
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
