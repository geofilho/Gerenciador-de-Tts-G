package br.com.geofilho.ttsg;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

/** Leitura de WAV gerado pelos motores e conversão para PCM 16 bits mono. */
final class Pcm {
    final short[] samples;
    final int rate;

    private Pcm(short[] samples, int rate) {
        this.samples = samples;
        this.rate = rate;
    }

    static Pcm readWav(File f) throws IOException {
        int size = (int) f.length();
        if (size < 44) {
            return null;
        }
        byte[] b = new byte[size];
        FileInputStream in = new FileInputStream(f);
        try {
            int off = 0;
            while (off < size) {
                int r = in.read(b, off, size - off);
                if (r < 0) {
                    break;
                }
                off += r;
            }
            size = off;
        } finally {
            in.close();
        }
        if (!(b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F')) {
            return null;
        }
        int channels = 1;
        int rate = 22050;
        int bits = 16;
        int dataStart = -1;
        int dataLen = 0;
        int p = 12;
        while (p + 8 <= size) {
            int len = le32(b, p + 4);
            if (b[p] == 'f' && b[p + 1] == 'm' && b[p + 2] == 't' && b[p + 3] == ' ') {
                channels = le16(b, p + 10);
                rate = le32(b, p + 12);
                bits = le16(b, p + 22);
            } else if (b[p] == 'd' && b[p + 1] == 'a' && b[p + 2] == 't' && b[p + 3] == 'a') {
                dataStart = p + 8;
                // Alguns motores deixam o tamanho zerado ou errado: lê até o fim.
                dataLen = (len <= 0 || dataStart + len > size) ? size - dataStart : len;
                break;
            }
            if (len < 0) {
                break;
            }
            p += 8 + len + (len & 1);
        }
        if (dataStart < 0 || channels < 1 || rate <= 0) {
            return null;
        }
        int bytesPerSample = bits / 8;
        if (bytesPerSample != 1 && bytesPerSample != 2) {
            return null;
        }
        int frame = bytesPerSample * channels;
        int frames = dataLen / frame;
        short[] out = new short[frames];
        for (int i = 0; i < frames; i++) {
            int pos = dataStart + i * frame;
            int sum = 0;
            for (int c = 0; c < channels; c++) {
                int q = pos + c * bytesPerSample;
                sum += bytesPerSample == 2 ? (short) le16(b, q) : ((b[q] & 0xFF) - 128) << 8;
            }
            out[i] = (short) (sum / channels);
        }
        return new Pcm(out, rate);
    }

    /** Reamostragem linear simples (leve) para juntar vozes de taxas diferentes. */
    static short[] resample(short[] in, int from, int to) {
        if (from == to || in.length == 0) {
            return in;
        }
        int outLen = (int) ((long) in.length * to / from);
        short[] out = new short[outLen];
        double step = (double) from / to;
        for (int i = 0; i < outLen; i++) {
            double src = i * step;
            int a = (int) src;
            int b = Math.min(a + 1, in.length - 1);
            double t = src - a;
            out[i] = (short) (in[a] + (in[b] - in[a]) * t);
        }
        return out;
    }

    /** Converte para bytes little-endian aplicando o volume (0 a 1). */
    static byte[] toBytes(short[] s, float gain) {
        byte[] out = new byte[s.length * 2];
        boolean full = gain >= 0.999f;
        for (int i = 0; i < s.length; i++) {
            int v = full ? s[i] : (int) (s[i] * gain);
            out[2 * i] = (byte) v;
            out[2 * i + 1] = (byte) (v >> 8);
        }
        return out;
    }

    private static int le16(byte[] b, int p) {
        return (b[p] & 0xFF) | ((b[p + 1] & 0xFF) << 8);
    }

    private static int le32(byte[] b, int p) {
        return (b[p] & 0xFF) | ((b[p + 1] & 0xFF) << 8) | ((b[p + 2] & 0xFF) << 16) | ((b[p + 3] & 0xFF) << 24);
    }
}
