package br.com.geofilho.ttsg;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Conexão do serviço com um motor de TTS real. Gera o áudio em arquivo e
 * devolve o PCM. Os métodos bloqueiam e devem rodar na thread de síntese.
 */
final class EngineClient {

    interface StopFlag {
        boolean stopped();
    }

    private final Context ctx;
    private final File file;
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextToSpeech tts;
    private String pkg = "";
    private volatile int status = Integer.MIN_VALUE;
    private CountDownLatch initLatch;
    private volatile CountDownLatch doneLatch;
    private volatile boolean ok;
    private final Map<String, Voice> voices = new HashMap<>();
    private String currentVoice = "";
    private int seq;

    EngineClient(Context ctx, String name) {
        this.ctx = ctx.getApplicationContext();
        this.file = new File(ctx.getCacheDir(), name + ".wav");
    }

    /** Lista os pacotes dos motores instalados, sem o próprio app. */
    static List<ResolveInfo> installedEngines(Context c) {
        PackageManager pm = c.getPackageManager();
        List<ResolveInfo> list = pm.queryIntentServices(
                new Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), PackageManager.GET_META_DATA);
        List<ResolveInfo> out = new ArrayList<>();
        for (ResolveInfo r : list) {
            if (r.serviceInfo != null && !c.getPackageName().equals(r.serviceInfo.packageName)) {
                out.add(r);
            }
        }
        return out;
    }

    static String firstEngine(Context c) {
        List<ResolveInfo> list = installedEngines(c);
        for (ResolveInfo r : list) {
            if ("com.google.android.tts".equals(r.serviceInfo.packageName)) {
                return r.serviceInfo.packageName;
            }
        }
        return list.isEmpty() ? "" : list.get(0).serviceInfo.packageName;
    }

    boolean ensure(final String wantPkg, StopFlag stop) {
        if (wantPkg.isEmpty()) {
            return false;
        }
        if (tts != null && wantPkg.equals(pkg) && status == TextToSpeech.SUCCESS) {
            return true;
        }
        if (tts != null && (!wantPkg.equals(pkg) || status == TextToSpeech.ERROR)) {
            shutdown();
        }
        if (tts == null) {
            pkg = wantPkg;
            status = Integer.MIN_VALUE;
            initLatch = new CountDownLatch(1);
            final CountDownLatch created = new CountDownLatch(1);
            // O TextToSpeech precisa ser criado na thread principal.
            main.post(new Runnable() {
                @Override
                public void run() {
                    tts = new TextToSpeech(ctx, new TextToSpeech.OnInitListener() {
                        @Override
                        public void onInit(int s) {
                            status = s;
                            initLatch.countDown();
                        }
                    }, wantPkg);
                    tts.setOnUtteranceProgressListener(listener);
                    created.countDown();
                }
            });
            if (!await(created, 5000, stop)) {
                return false;
            }
        }
        if (!await(initLatch, 8000, stop) || status != TextToSpeech.SUCCESS) {
            return false;
        }
        loadVoices();
        return true;
    }

    private void loadVoices() {
        if (!voices.isEmpty()) {
            return;
        }
        try {
            Set<Voice> set = tts.getVoices();
            if (set != null) {
                for (Voice v : set) {
                    voices.put(v.getName(), v);
                }
            }
        } catch (Exception ignored) {
            // Alguns motores antigos falham aqui; seguem com a voz padrão.
        }
    }

    /** Gera o áudio do texto. Retorna null se falhar ou for interrompido. */
    Pcm synth(String text, String voiceName, float rate, float pitch, StopFlag stop) {
        TextToSpeech t = tts;
        if (t == null) {
            return null;
        }
        if (voiceName != null && !voiceName.isEmpty() && !voiceName.equals(currentVoice)) {
            Voice v = voices.get(voiceName);
            if (v != null) {
                try {
                    t.setVoice(v);
                    currentVoice = voiceName;
                } catch (Exception ignored) {
                }
            }
        }
        t.setSpeechRate(rate);
        t.setPitch(pitch);
        file.delete();
        ok = false;
        doneLatch = new CountDownLatch(1);
        int r = t.synthesizeToFile(text, new Bundle(), file, "g" + (seq++));
        if (r != TextToSpeech.SUCCESS) {
            return null;
        }
        if (!await(doneLatch, 30000, stop)) {
            t.stop();
            return null;
        }
        if (!ok) {
            return null;
        }
        try {
            return Pcm.readWav(file);
        } catch (Exception e) {
            return null;
        }
    }

    void stop() {
        TextToSpeech t = tts;
        if (t != null) {
            t.stop();
        }
        CountDownLatch l = doneLatch;
        if (l != null) {
            l.countDown();
        }
    }

    void shutdown() {
        final TextToSpeech t = tts;
        tts = null;
        voices.clear();
        currentVoice = "";
        status = Integer.MIN_VALUE;
        if (t != null) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    t.shutdown();
                }
            });
        }
    }

    private static boolean await(CountDownLatch latch, long timeoutMs, StopFlag stop) {
        long end = System.currentTimeMillis() + timeoutMs;
        try {
            while (System.currentTimeMillis() < end) {
                if (latch.await(20, TimeUnit.MILLISECONDS)) {
                    return stop == null || !stop.stopped();
                }
                if (stop != null && stop.stopped()) {
                    return false;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return false;
    }

    private final UtteranceProgressListener listener = new UtteranceProgressListener() {
        @Override
        public void onStart(String id) {
        }

        @Override
        public void onDone(String id) {
            ok = true;
            release();
        }

        @Override
        @SuppressWarnings("deprecation")
        public void onError(String id) {
            ok = false;
            release();
        }

        @Override
        public void onError(String id, int code) {
            ok = false;
            release();
        }

        @Override
        public void onStop(String id, boolean interrupted) {
            ok = false;
            release();
        }

        private void release() {
            CountDownLatch l = doneLatch;
            if (l != null) {
                l.countDown();
            }
        }
    };
}
