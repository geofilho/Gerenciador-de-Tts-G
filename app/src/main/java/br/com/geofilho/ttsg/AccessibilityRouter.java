package br.com.geofilho.ttsg;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecordingConfiguration;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.List;
import java.util.concurrent.Executor;

/**
 * Android 12+: mantém TODO o áudio de acessibilidade do sistema (fala e sons
 * de navegação do leitor de tela) no alto-falante, enquanto a mídia continua
 * no Bluetooth.
 *
 * Como funciona: o Android faz o áudio de acessibilidade seguir o "dispositivo
 * de comunicação". O app escolhe o alto-falante com setCommunicationDevice e
 * mantém uma faixa silenciosa tocando, porque o Android desfaz a escolha de um
 * app que fica alguns segundos sem tocar nada. Durante ligações e quando outro
 * app usa o microfone (assistente de voz), a escolha é desfeita para não
 * atrapalhar. Ideia vista no leitor de tela SandFly (Apache 2.0).
 */
@TargetApi(31)
final class AccessibilityRouter {

    private final Context ctx;
    private final AudioManager am;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private AudioTrack keepAlive;
    private boolean commSetByUs;
    private volatile boolean active;
    private long lastEnsure;

    private AudioDeviceCallback deviceCallback;
    private AudioManager.OnModeChangedListener modeListener;
    private AudioManager.AudioRecordingCallback recordingCallback;
    private AudioManager.OnCommunicationDeviceChangedListener commListener;
    private SharedPreferences.OnSharedPreferenceChangeListener prefListener;
    private volatile boolean voiceInput;

    private final Runnable apply = new Runnable() {
        @Override
        public void run() {
            update();
        }
    };

    AccessibilityRouter(Context c) {
        ctx = c.getApplicationContext();
        am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
    }

    static boolean supported() {
        return Build.VERSION.SDK_INT >= 31;
    }

    /** Se a rota está ativa (fala e sons de acessibilidade indo para o alto-falante). */
    boolean isActive() {
        return active;
    }

    void start() {
        if (am == null) {
            return;
        }
        Executor main = new Executor() {
            @Override
            public void execute(Runnable r) {
                handler.post(r);
            }
        };
        deviceCallback = new AudioDeviceCallback() {
            @Override
            public void onAudioDevicesAdded(AudioDeviceInfo[] added) {
                reassert();
            }

            @Override
            public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) {
                reassert();
            }
        };
        am.registerAudioDeviceCallback(deviceCallback, handler);
        modeListener = new AudioManager.OnModeChangedListener() {
            @Override
            public void onModeChanged(int mode) {
                reassert();
            }
        };
        am.addOnModeChangedListener(main, modeListener);
        recordingCallback = new AudioManager.AudioRecordingCallback() {
            @Override
            public void onRecordingConfigChanged(List<AudioRecordingConfiguration> configs) {
                voiceInput = isVoiceInput(configs);
                handler.removeCallbacks(apply);
                handler.post(apply);
            }
        };
        am.registerAudioRecordingCallback(recordingCallback, handler);
        voiceInput = isVoiceInput(am.getActiveRecordingConfigurations());
        commListener = new AudioManager.OnCommunicationDeviceChangedListener() {
            @Override
            public void onCommunicationDeviceChanged(AudioDeviceInfo device) {
                // O Bluetooth ou o sistema trocou a rota: confere de novo em seguida.
                if (active && (device == null || device.getType() != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)) {
                    handler.removeCallbacks(apply);
                    handler.postDelayed(apply, 300);
                }
            }
        };
        am.addOnCommunicationDeviceChangedListener(main, commListener);
        prefListener = new SharedPreferences.OnSharedPreferenceChangeListener() {
            @Override
            public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
                if ("keep_on_phone".equals(key)) {
                    handler.removeCallbacks(apply);
                    handler.post(apply);
                }
            }
        };
        Prefs.get(ctx).registerOnSharedPreferenceChangeListener(prefListener);
        handler.post(apply);
    }

    void stop() {
        handler.removeCallbacksAndMessages(null);
        if (am != null) {
            try {
                am.unregisterAudioDeviceCallback(deviceCallback);
                am.removeOnModeChangedListener(modeListener);
                am.unregisterAudioRecordingCallback(recordingCallback);
                am.removeOnCommunicationDeviceChangedListener(commListener);
            } catch (Exception ignored) {
            }
        }
        if (prefListener != null) {
            Prefs.get(ctx).unregisterOnSharedPreferenceChangeListener(prefListener);
        }
        release();
    }

    /** Confere rápido antes de cada fala (no máximo 2 vezes por segundo). */
    void ensure() {
        long now = SystemClock.uptimeMillis();
        if (now - lastEnsure < 500) {
            return;
        }
        lastEnsure = now;
        handler.post(apply);
    }

    /** Depois de conectar ou desconectar Bluetooth, a negociação leva 1 a 2 s: confere algumas vezes. */
    private void reassert() {
        handler.removeCallbacks(apply);
        handler.post(apply);
        handler.postDelayed(apply, 1000);
        handler.postDelayed(apply, 3000);
    }

    private void update() {
        if (am == null) {
            return;
        }
        boolean want = Prefs.keepOnPhone(Prefs.get(ctx))
                && PhonePlayer.bluetoothOutputName(ctx) != null
                && !inCall() && !voiceInput;
        AudioDeviceInfo speaker = want ? speaker() : null;
        if (speaker == null) {
            release();
            return;
        }
        startKeepAlive(speaker);
        AudioDeviceInfo current = am.getCommunicationDevice();
        if (current == null || current.getId() != speaker.getId()) {
            try {
                commSetByUs = am.setCommunicationDevice(speaker) || commSetByUs;
            } catch (Exception ignored) {
            }
        }
        active = commSetByUs;
    }

    private void release() {
        active = false;
        stopKeepAlive();
        if (commSetByUs && am != null) {
            try {
                am.clearCommunicationDevice();
            } catch (Exception ignored) {
            }
            commSetByUs = false;
        }
    }

    private boolean inCall() {
        int mode = am.getMode();
        return mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION
                || mode == AudioManager.MODE_RINGTONE || mode == AudioManager.MODE_CALL_SCREENING;
    }

    private static boolean isVoiceInput(List<AudioRecordingConfiguration> configs) {
        if (configs == null) {
            return false;
        }
        for (AudioRecordingConfiguration c : configs) {
            int s = c.getClientAudioSource();
            if (s == MediaRecorder.AudioSource.VOICE_COMMUNICATION
                    || s == MediaRecorder.AudioSource.VOICE_RECOGNITION) {
                return true;
            }
        }
        return false;
    }

    /** O alto-falante (nunca o fone de ouvido do telefone, para a fala não ficar baixinha). */
    private AudioDeviceInfo speaker() {
        for (AudioDeviceInfo d : am.getAvailableCommunicationDevices()) {
            if (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                return d;
            }
        }
        return null;
    }

    /**
     * Faixa de silêncio em loop (zeros, volume normal): mantém o app "tocando"
     * para o Android não desfazer a rota. Volume zero não serve, porque o
     * sistema passaria a considerar a faixa parada.
     */
    private void startKeepAlive(AudioDeviceInfo device) {
        if (keepAlive != null) {
            try {
                keepAlive.setPreferredDevice(device);
            } catch (Exception ignored) {
            }
            return;
        }
        try {
            int rate = 16000;
            int size = Math.max(1024, AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT));
            AudioTrack t = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(rate)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(size)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build();
            byte[] silence = new byte[size];
            if (t.write(silence, 0, silence.length) > 0) {
                t.setLoopPoints(0, silence.length / 2, -1);
            }
            t.setPreferredDevice(device);
            t.play();
            keepAlive = t;
        } catch (Exception ignored) {
        }
    }

    private void stopKeepAlive() {
        AudioTrack t = keepAlive;
        keepAlive = null;
        if (t != null) {
            try {
                t.stop();
            } catch (Exception ignored) {
            }
            t.release();
        }
    }
}
