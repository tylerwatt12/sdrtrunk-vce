package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.*;

import com.mpatric.mp3agic.Mp3File;
import com.mpatric.mp3agic.ID3v24Tag;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.sound.sampled.AudioFormat;
import net.sourceforge.lame.lowlevel.LameEncoder;
import net.sourceforge.lame.mp3.GetAudio;
import net.sourceforge.lame.mp3.FrameSkip;
import net.sourceforge.lame.mp3.Lame;
import net.sourceforge.lame.mp3.MPEGMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecordingMp3ExporterTest
{
    @TempDir Path directory;

    @Test void joinsDifferentSampleRatesIntoOnePlayableConstantRateStream() throws Exception
    {
        Path first = directory.resolve("first.mp3"), second = directory.resolve("second.mp3");
        writeTone(first, 8000, 300);
        writeTone(second, 22050, 900, true);
        byte[] original = Files.readAllBytes(first);
        Path output = directory.resolve("incident.mp3");
        try(var stream = Files.newOutputStream(output);
            var exporter = new RecordingMp3Exporter(stream, () -> {}))
        {
            exporter.append(first); exporter.append(second); exporter.finish();
        }
        var mp3 = new Mp3File(output.toString());
        assertEquals(44100, mp3.getSampleRate());
        assertEquals(64, mp3.getBitrate());
        assertFalse(mp3.isVbr());
        assertTrue(mp3.getLengthInMilliseconds() > 1800 && mp3.getLengthInMilliseconds() < 2400,
            "Duration represents both calls, without their elapsed incident gap");
        List<Float> samples = decode(output);
        assertTrue(crossings(samples, 44100 / 4, 44100 / 5) < 160, "First call retains its lower tone");
        assertTrue(crossings(samples, 44100 * 5 / 4, 44100 / 5) > 280, "Second call retains its higher tone");
        assertArrayEquals(original, Files.readAllBytes(first));
    }

    @Test void cancellationInterruptsEncoding() throws Exception
    {
        Path first = directory.resolve("first.mp3"); writeTone(first, 16000, 300);
        try(var stream = Files.newOutputStream(directory.resolve("cancelled.mp3"));
            var exporter = new RecordingMp3Exporter(stream, () -> { throw new java.util.concurrent.CancellationException(); }))
        {
            assertThrows(java.util.concurrent.CancellationException.class, () -> exporter.append(first));
        }
    }

    static void writeTone(Path path, int rate, int frequency) throws Exception
    {
        writeTone(path, rate, frequency, false);
    }

    static void writeTone(Path path, int rate, int frequency, boolean variable) throws Exception
    {
        var encoder = new LameEncoder(new AudioFormat(rate, 16, 1, true, false),
            rate == 8000 ? 16 : 32, MPEGMode.MONO, Lame.QUALITY_MIDDLE, variable);
        byte[] pcm = new byte[rate * 2];
        for(int i = 0; i < rate; i++)
        {
            int sample = (int)(12000 * Math.sin(2 * Math.PI * frequency * i / rate));
            pcm[2 * i] = (byte)sample; pcm[2 * i + 1] = (byte)(sample >> 8);
        }
        byte[] encoded = new byte[encoder.getMP3BufferSize()];
        try(var output = Files.newOutputStream(path))
        {
            var tag = new ID3v24Tag(); tag.setTitle("Synthetic incident call"); output.write(tag.toBytes());
            for(int offset = 0; offset < pcm.length; offset += 2048)
            {
                int count = encoder.encodeBuffer(pcm, offset, Math.min(2048, pcm.length - offset), encoded);
                output.write(encoded, 0, count);
            }
            int count = encoder.encodeFinish(encoded); output.write(encoded, 0, count);
        }
        finally { encoder.close(); }
    }

    static List<Float> decode(Path path)
    {
        Lame decoder = new Lame();
        List<Float> samples = new ArrayList<>();
        try
        {
            decoder.getParser().silent = 10;
            decoder.getFlags().setWriteId3tagAutomatic(false);
            decoder.initParams();
            decoder.getParser().setInputFormat(GetAudio.SoundFileFormat.sf_mp3);
            decoder.getAudio().initInFile(decoder.getFlags(), path.toString(), new FrameSkip());
            float[][] pcm = new float[2][1152];
            int count;
            while((count = decoder.getAudio().get_audio16(decoder.getFlags(), pcm)) > 0)
                for(int i = 0; i < count; i++) samples.add(pcm[0][i]);
        }
        finally { decoder.getAudio().close_infile(); decoder.close(); }
        return samples;
    }

    static int crossings(List<Float> samples, int start, int count)
    {
        int crossings = 0;
        for(int i = start + 1; i < Math.min(samples.size(), start + count); i++)
            if((samples.get(i - 1) >= 0) != (samples.get(i) >= 0)) crossings++;
        return crossings;
    }
}
