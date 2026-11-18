package br.com.geofilho.ttsg;

import android.annotation.TargetApi;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;

/**
 * Toca a fala direto no alto-falante do telefone, mesmo com fone ou caixa
 * Bluetooth conectados (a música continua no Bluetooth). Android 6 ou mais novo.
 */
@TargetApi(23)
final class PhonePlayer {

    private final AudioTrack track;
    private long framesWritten;
    private volatile boolean stopped;

    private PhonePlayer(AudioTrack track) {
        this.track = track;
    }

    /** Há um fone, caixa ou som de carro Bluetooth conectado como saída de áudio? */
    static String bluetoothOutputName(Context c) {
        if (Build.VERSION.SDK_INT < 23) {
            return null;
        }
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) {
            return null;
        }
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            int t = d.getType();
            if (t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                    || t == 26 /* BLE_HEADSET */ || t == 27 /* BLE_SPEAKER */) {
                CharSequence name = d.getProductName();
                return name != null && name.length() > 0 ? name.toString() : "dispositivo Bluetooth";
            }
        }
        return null;
    }

    private static AudioDeviceInfo speaker(AudioManager am) {
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            if (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                return d;
            }
        }
        return null;
    }

    /** Cria o tocador apontado para o alto-falante, ou null se não for possível. */
    static PhonePlayer open(Context c, int sampleRate) {
        if (Build.VERSION.SDK_INT < 23) {
            return null;
        }
        try {
            AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            AudioDeviceInfo spk = am == null ? null : speaker(am);
            if (spk == null) {
                return null;
            }
            int usage = Build.VERSION.SDK_INT >= 26
                    ? AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY : AudioAttributes.USAGE_MEDIA;
            int min = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            AudioTrack t = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(usage)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(sampleRate)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(Math.max(min, sampleRate / 5 * 2))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            t.setPreferredDevice(spk);
            t.play();
            return new PhonePlayer(t);
        } catch (Exception e) {
            return null;
        }
    }

    /** Escreve o áudio (bloqueia enquanto toca). Retorna false se foi interrompido. */
    boolean write(byte[] data) {
        int off = 0;
        while (off < data.length) {
            if (stopped) {
                return false;
            }
            int n = track.write(data, off, Math.min(8192, data.length - off));
            if (n <= 0) {
                return false;
            }
            off += n;
        }
        framesWritten += data.length / 2;
        return true;
    }

    /** Espera terminar de tocar o que foi escrito, para a próxima fala não atropelar. */
    void finish() {
        long end = System.currentTimeMillis() + 15000;
        try {
            while (!stopped && System.currentTimeMillis() < end) {
                long head = track.getPlaybackHeadPosition() & 0xFFFFFFFFL;
                if (head >= framesWritten) {
                    break;
                }
                Thread.sleep(10);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        release();
    }

    void stop() {
        stopped = true;
        try {
            track.pause();
            track.flush();
        } catch (Exception ignored) {
        }
    }

    void release() {
        try {
            track.stop();
        } catch (Exception ignored) {
        }
        track.release();
    }
}
