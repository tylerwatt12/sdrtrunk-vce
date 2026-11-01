package io.github.dsheirer.stats;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import javax.sound.sampled.AudioFormat;
import net.sourceforge.lame.lowlevel.LameEncoder;
import net.sourceforge.lame.mp3.FrameSkip;
import net.sourceforge.lame.mp3.GetAudio;
import net.sourceforge.lame.mp3.Lame;
import net.sourceforge.lame.mp3.MPEGMode;

/** Offline, bounded PCM decoding into one constant-rate MP3 stream. Never used by receiver callbacks. */
final class RecordingMp3Exporter implements AutoCloseable
{
    private static final int OUTPUT_RATE = 44100;
    private final LameEncoder mEncoder = new LameEncoder(new AudioFormat(OUTPUT_RATE, 16, 1, true, false),
        64, MPEGMode.MONO, Lame.QUALITY_MIDDLE, false);
    private final byte[] mPcm = new byte[8192];
    private final byte[] mEncoded = new byte[mEncoder.getMP3BufferSize()];
    private final OutputStream mOutput;
    private final Runnable mCheck;
    private int mPosition;
    private long mSamples;

    RecordingMp3Exporter(OutputStream output, Runnable check)
    {
        mOutput = output;
        mCheck = check;
    }

    void append(Path file) throws IOException
    {
        Lame decoder = new Lame();
        boolean opened = false;
        try
        {
            decoder.getParser().silent = 10;
            decoder.getFlags().setWriteId3tagAutomatic(false);
            decoder.initParams();
            decoder.getParser().setInputFormat(GetAudio.SoundFileFormat.sf_mp3);
            FrameSkip skip = new FrameSkip();
            opened = true;
            decoder.getAudio().initInFile(decoder.getFlags(), file.toString(), skip);
            int rate = decoder.getFlags().getInSampleRate();
            int channels = decoder.getFlags().getInNumChannels();
            if(rate < 8000 || rate > 48000 || channels < 1 || channels > 2)
                throw new IOException("Unsupported recording audio");

            // LAME's PCM reader does not itself apply its reported gapless delay/padding.
            int delay = (skip.getEncoderDelay() >= 0 ? skip.getEncoderDelay() : 576) + 529;
            int padding = Math.max(0, skip.getEncoderPadding() - 529);
            if(padding > 4096) throw new IOException("Invalid recording padding");
            float[] tail = new float[Math.max(1, padding)];
            int tailCount = 0, tailPosition = 0;
            float[][] pcm = new float[2][1152];
            Resampler resampler = new Resampler(rate);
            long before = mSamples;
            int count;
            while((count = decoder.getAudio().get_audio16(decoder.getFlags(), pcm)) > 0)
            {
                mCheck.run();
                for(int i = 0; i < count; i++)
                {
                    if(delay > 0) { delay--; continue; }
                    float sample = channels == 1 ? pcm[0][i] : (pcm[0][i] + pcm[1][i]) / 2;
                    if(padding == 0) resampler.accept(sample);
                    else
                    {
                        if(tailCount == padding) resampler.accept(tail[tailPosition]);
                        else tailCount++;
                        tail[tailPosition] = sample;
                        tailPosition = (tailPosition + 1) % padding;
                    }
                }
            }
            if(count < 0) throw new IOException("Recording audio could not be decoded");
            resampler.finish();
            if(mSamples == before) throw new IOException("Recording contains no playable audio");
        }
        finally
        {
            try { if(opened) decoder.getAudio().close_infile(); }
            finally { decoder.close(); }
        }
    }

    void finish() throws IOException
    {
        flushPcm();
        int count = mEncoder.encodeFinish(mEncoded);
        if(count < 0) throw new IOException("MP3 export could not be completed");
        mOutput.write(mEncoded, 0, count);
    }

    private void sample(float sample) throws IOException
    {
        if(++mSamples > OUTPUT_RATE * 7200L) throw new IOException("Select at most two hours of audio");
        int value = Math.max(-32768, Math.min(32767, Math.round(sample)));
        mPcm[mPosition++] = (byte)value;
        mPcm[mPosition++] = (byte)(value >> 8);
        if(mPosition == mPcm.length) flushPcm();
    }

    private void flushPcm() throws IOException
    {
        mCheck.run();
        if(mPosition == 0) return;
        int count = mEncoder.encodeBuffer(mPcm, 0, mPosition, mEncoded);
        if(count < 0) throw new IOException("MP3 export could not be encoded");
        mOutput.write(mEncoded, 0, count);
        mPosition = 0;
    }

    /** Streaming interpolation to a common clock; selected files can have different recording sample rates. */
    private final class Resampler
    {
        private final double mStep;
        private long mIndex = -1;
        private double mNext;
        private float mPrevious;

        Resampler(int rate) { mStep = (double)rate / OUTPUT_RATE; }

        void accept(float value) throws IOException
        {
            mIndex++;
            if(mIndex > 0)
                while(mNext <= mIndex)
                {
                    sample((float)(mPrevious + (value - mPrevious) * (mNext - (mIndex - 1))));
                    mNext += mStep;
                }
            mPrevious = value;
        }

        void finish() throws IOException
        {
            if(mIndex < 0) return;
            while(mNext < mIndex + 1) { sample(mPrevious); mNext += mStep; }
        }
    }

    @Override public void close() { mEncoder.close(); }
}
