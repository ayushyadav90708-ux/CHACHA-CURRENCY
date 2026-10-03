package com.kodari.notesongs.client;

import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;

final class AudioDecoder {
    private static final int MAX_DURATION_SECONDS = 180;

    private AudioDecoder() {
    }

    static DecodedAudio decode(byte[] mp3) throws Exception {
        ArrayList<short[]> frames = new ArrayList<>();
        int rate = -1;
        int channels = -1;
        long totalSamples = 0;
        Decoder decoder = new Decoder();
        try (Bitstream bitstream = new Bitstream(new ByteArrayInputStream(mp3))) {
            Header header;
            while ((header = bitstream.readFrame()) != null) {
                SampleBuffer buffer = (SampleBuffer) decoder.decodeFrame(header, bitstream);
                if (rate == -1) {
                    rate = buffer.getSampleFrequency();
                    channels = buffer.getChannelCount();
                    if (rate < 8000 || rate > 96000 || channels < 1 || channels > 2) {
                        throw new IllegalArgumentException("Unsupported MP3 audio format");
                    }
                } else if (rate != buffer.getSampleFrequency() || channels != buffer.getChannelCount()) {
                    throw new IllegalArgumentException("MP3 changes audio format mid-file");
                }
                int length = buffer.getBufferLength();
                totalSamples += length;
                if (totalSamples > (long) rate * channels * MAX_DURATION_SECONDS) {
                    throw new IllegalArgumentException("MP3 exceeds the 180 second limit");
                }
                short[] frame = new short[length];
                System.arraycopy(buffer.getBuffer(), 0, frame, 0, length);
                frames.add(frame);
                bitstream.closeFrame();
            }
        }
        if (rate < 0 || totalSamples == 0) {
            throw new IllegalArgumentException("No MP3 audio frames were found");
        }
        short[] samples = new short[(int) totalSamples];
        int offset = 0;
        for (short[] frame : frames) {
            System.arraycopy(frame, 0, samples, offset, frame.length);
            offset += frame.length;
        }
        return new DecodedAudio(samples, rate, channels);
    }

    record DecodedAudio(short[] samples, int sampleRate, int channels) {
    }
}