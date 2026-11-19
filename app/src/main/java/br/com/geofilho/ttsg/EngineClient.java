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
import java.util.Locale;
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
    private volatile TextToSpeech tts;
    private String pkg = "";
    private volatile int status = Integer.MIN_VALUE;
    private CountDownLatch initLatch;
    private volatile CountDownLatch doneLatch;
    private volatile boolean ok;
    private final Map<String, Voice> voices = new HashMap<>();
    private String currentVoice = "";
    private int seq;

    /** Depois de uma falha, espera este tempo antes de tentar o motor de novo. */
    private static final long RETRY_MS = 10000;
    /** Na primeira vez, espera o motor ficar pronto por até este tempo. */
    private static final long FIRST_WAIT_MS = 4000;
    /** Motor que nunca termina de iniciar: desiste e tenta de novo depois. */
    private static final long GIVE_UP_MS = 30000;

    private volatile long failedAt;
    private long createdAt;
    private long voicesLoadedAt;
    private Locale locale;
    private boolean localeApplied;

    EngineClient(Context ctx, String name) {
        this.ctx = ctx.getApplicationContext();
        // Armazenamento que funciona até antes de digitar a senha, depois de religar.
        this.file = new File(Prefs.storage(ctx).getCacheDir(), name + ".wav");
    }

    /** Idioma usado quando nenhuma voz específica é escolhida (motor reserva). */
    void setLocale(Locale l) {
        if (l != null && !l.equals(locale)) {
            locale = l;
            localeApplied = false;
        }
    }

    /** Permite tentar o motor de novo na hora (por exemplo, logo depois de desbloquear). */
    void retryNow() {
        failedAt = 0;
    }

    /**
     * Motor reserva: o padrão do aparelho (Samsung ou Google) ou outro instalado,
     * diferente do motor que falhou. Vazio se não houver.
     */
    static String fallbackEngine(Context c, String failedPkg) {
        List<ResolveInfo> list = installedEngines(c);
        String[] preferred = {"com.samsung.SMT", "com.google.android.tts"};
        for (String want : preferred) {
            for (ResolveInfo r : list) {
                if (want.equals(r.serviceInfo.packageName) && !want.equals(failedPkg)) {
                    return want;
                }
            }
        }
        for (ResolveInfo r : list) {
            if (!r.serviceInfo.packageName.equals(failedPkg)) {
                return r.serviceInfo.packageName;
            }
        }
        return "";
    }

    /** O motor pode rodar agora? (Antes da senha, só os preparados para isso aparecem.) */
    static boolean isAvailable(Context c, String pkg) {
        try {
            Intent i = new Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE).setPackage(pkg);
            return !c.getPackageManager().queryIntentServices(i, 0).isEmpty();
        } catch (Exception e) {
            return false;
        }
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

    /**
     * Garante o motor pronto. Não trava a fala: se o motor demora ou falhou,
     * retorna false (o serviço usa o motor reserva) e tenta de novo depois.
     */
    boolean ensure(final String wantPkg, StopFlag stop) {
        if (wantPkg.isEmpty()) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (tts != null && !wantPkg.equals(pkg)) {
            shutdown();
        }
        if (tts != null && status == TextToSpeech.ERROR) {
            if (now - failedAt < RETRY_MS) {
                return false;
            }
            shutdown();
        }
        if (tts == null) {
            if (now - failedAt < RETRY_MS) {
                return false;
            }
            if (!isAvailable(ctx, wantPkg)) {
                // Ex.: antes de digitar a senha, motores como a RHVoice não podem rodar.
                // Se pedíssemos assim mesmo, o Android trocaria em silêncio por outro motor.
                failedAt = now;
                return false;
            }
            pkg = wantPkg;
            status = Integer.MIN_VALUE;
            createdAt = now;
            initLatch = new CountDownLatch(1);
            final CountDownLatch created = new CountDownLatch(1);
            final CountDownLatch myInit = initLatch;
            // O TextToSpeech precisa ser criado na thread principal.
            main.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        tts = new TextToSpeech(ctx, new TextToSpeech.OnInitListener() {
                            @Override
                            public void onInit(int s) {
                                status = s;
                                if (s != TextToSpeech.SUCCESS) {
                                    failedAt = System.currentTimeMillis();
                                }
                                myInit.countDown();
                            }
                        }, wantPkg);
                        tts.setOnUtteranceProgressListener(listener);
                    } catch (Exception e) {
                        status = TextToSpeech.ERROR;
                        failedAt = System.currentTimeMillis();
                        myInit.countDown();
                    }
                    created.countDown();
                }
            });
            if (!await(created, 5000, stop)) {
                return false;
            }
        }
        if (status == TextToSpeech.SUCCESS) {
            loadVoices(false);
            return true;
        }
        long waited = now - createdAt;
        long wait = waited < FIRST_WAIT_MS ? FIRST_WAIT_MS - waited : 150;
        if (!await(initLatch, wait, stop)) {
            if (System.currentTimeMillis() - createdAt > GIVE_UP_MS) {
                failedAt = System.currentTimeMillis();
                shutdown();
            }
            return false;
        }
        if (status != TextToSpeech.SUCCESS) {
            failedAt = System.currentTimeMillis();
            return false;
        }
        loadVoices(false);
        return true;
    }

    /** Carrega a lista de vozes; se veio vazia ou falta a voz pedida, tenta de novo (no máximo a cada 3 s). */
    private void loadVoices(boolean force) {
        if (!voices.isEmpty() && !force) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - voicesLoadedAt < 3000) {
            return;
        }
        voicesLoadedAt = now;
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
            if (v == null) {
                // Logo depois de religar, o motor pode ainda não ter informado as vozes.
                loadVoices(true);
                v = voices.get(voiceName);
            }
            if (v != null) {
                try {
                    t.setVoice(v);
                    currentVoice = voiceName;
                } catch (Exception ignored) {
                }
            }
        }
        if ((voiceName == null || voiceName.isEmpty()) && locale != null && !localeApplied) {
            try {
                t.setLanguage(locale);
            } catch (Exception ignored) {
            }
            localeApplied = true;
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
        voicesLoadedAt = 0;
        currentVoice = "";
        localeApplied = false;
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
