package br.com.geofilho.ttsg;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.AudioFormat;
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
import java.util.concurrent.LinkedBlockingQueue;
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

    /** Recebe o áudio em pedaços, já em PCM 16 bits mono. Retorna false para interromper. */
    interface AudioSink {
        boolean write(short[] mono, int sampleRate);
    }

    /** Resultado de {@link #synth}. */
    static final int OK = 0;
    static final int FAIL = -1;
    static final int ABORTED = -2;

    private static final int EV_BEGIN = 1;
    private static final int EV_AUDIO = 2;
    private static final int EV_DONE = 3;
    private static final int EV_ERROR = 4;
    private static final int EV_STOP = 5;

    /** Eventos do motor (chegam em outra thread) para a thread de síntese. */
    private final LinkedBlockingQueue<Object[]> events = new LinkedBlockingQueue<>();
    private volatile String currentId = "";

    /**
     * Gera o áudio e entrega em pedaços ao {@code sink} à medida que o motor
     * produz (Android 7+), para a fala começar antes de o motor terminar.
     * Motores que não entregam pedaços caem no modo antigo: arquivo inteiro.
     * Retorna OK, FAIL (o motor falhou antes de produzir som) ou ABORTED.
     */
    int synth(String text, String voiceName, float rate, float pitch, StopFlag stop, AudioSink sink) {
        TextToSpeech t = tts;
        if (t == null) {
            return FAIL;
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
        String id = "g" + (seq++);
        events.clear();
        currentId = id;
        if (t.synthesizeToFile(text, new Bundle(), file, id) != TextToSpeech.SUCCESS) {
            return FAIL;
        }

        int sampleRate = 0;
        int encoding = AudioFormat.ENCODING_PCM_16BIT;
        int channels = 1;
        byte[] carry = new byte[0];
        boolean streamed = false;
        long deadline = System.currentTimeMillis() + 30000;
        try {
            while (true) {
                long left = deadline - System.currentTimeMillis();
                // Espera bloqueante: acorda na hora em que o motor manda algo (sem conferir a cada 20 ms).
                Object[] e = left > 0 ? events.poll(left, TimeUnit.MILLISECONDS) : null;
                if (e == null) {
                    t.stop();
                    return streamed ? OK : FAIL;
                }
                if (stop.stopped()) {
                    t.stop();
                    return ABORTED;
                }
                int type = (Integer) e[0];
                if (type == EV_BEGIN) {
                    sampleRate = (Integer) e[1];
                    encoding = (Integer) e[2];
                    channels = Math.max(1, (Integer) e[3]);
                } else if (type == EV_AUDIO) {
                    if (sampleRate <= 0) {
                        continue; // sem formato informado: usa o arquivo no fim
                    }
                    byte[] data = (byte[]) e[1];
                    if (carry.length > 0) {
                        byte[] joined = new byte[carry.length + data.length];
                        System.arraycopy(carry, 0, joined, 0, carry.length);
                        System.arraycopy(data, 0, joined, carry.length, data.length);
                        data = joined;
                    }
                    int frame = bytesPerSample(encoding) * channels;
                    int usable = data.length - data.length % frame;
                    carry = usable < data.length ? java.util.Arrays.copyOfRange(data, usable, data.length) : new byte[0];
                    short[] mono = toMono(data, usable, encoding, channels);
                    if (mono == null) {
                        continue;
                    }
                    if (mono.length > 0) {
                        streamed = true;
                        if (!sink.write(mono, sampleRate)) {
                            t.stop();
                            return ABORTED;
                        }
                    }
                } else if (type == EV_DONE) {
                    if (streamed) {
                        return OK;
                    }
                    // O motor não entregou pedaços: lê o arquivo inteiro (modo antigo).
                    Pcm pcm = Pcm.readWav(file);
                    if (pcm == null) {
                        return FAIL;
                    }
                    if (pcm.samples.length > 0 && !sink.write(pcm.samples, pcm.rate)) {
                        return ABORTED;
                    }
                    return OK;
                } else if (type == EV_ERROR) {
                    return streamed ? OK : FAIL;
                } else {
                    return ABORTED;
                }
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return ABORTED;
        } catch (Exception ex) {
            return streamed ? OK : FAIL;
        }
    }

    private static int bytesPerSample(int encoding) {
        if (encoding == AudioFormat.ENCODING_PCM_8BIT) {
            return 1;
        }
        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
            return 4;
        }
        return 2;
    }

    /** Converte para 16 bits mono. Retorna null para formatos desconhecidos. */
    private static short[] toMono(byte[] b, int len, int encoding, int channels) {
        int bps = bytesPerSample(encoding);
        if (encoding != AudioFormat.ENCODING_PCM_16BIT && encoding != AudioFormat.ENCODING_PCM_8BIT
                && encoding != AudioFormat.ENCODING_PCM_FLOAT) {
            return null;
        }
        int frames = len / (bps * channels);
        short[] out = new short[frames];
        for (int i = 0; i < frames; i++) {
            int sum = 0;
            for (int c = 0; c < channels; c++) {
                int p = (i * channels + c) * bps;
                int v;
                if (bps == 2) {
                    v = (short) ((b[p] & 0xFF) | (b[p + 1] << 8));
                } else if (bps == 1) {
                    v = ((b[p] & 0xFF) - 128) << 8;
                } else {
                    int bits = (b[p] & 0xFF) | ((b[p + 1] & 0xFF) << 8) | ((b[p + 2] & 0xFF) << 16) | (b[p + 3] << 24);
                    float f = Float.intBitsToFloat(bits);
                    v = (int) (Math.max(-1f, Math.min(1f, f)) * 32767);
                }
                sum += v;
            }
            out[i] = (short) (sum / channels);
        }
        return out;
    }

    void stop() {
        TextToSpeech t = tts;
        if (t != null) {
            t.stop();
        }
        events.offer(new Object[]{EV_STOP}); // acorda a thread de síntese na hora
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
        public void onBeginSynthesis(String id, int sampleRateInHz, int audioFormat, int channelCount) {
            post(id, new Object[]{EV_BEGIN, sampleRateInHz, audioFormat, channelCount});
        }

        @Override
        public void onAudioAvailable(String id, byte[] audio) {
            post(id, new Object[]{EV_AUDIO, audio});
        }

        @Override
        public void onDone(String id) {
            post(id, new Object[]{EV_DONE});
        }

        @Override
        @SuppressWarnings("deprecation")
        public void onError(String id) {
            post(id, new Object[]{EV_ERROR});
        }

        @Override
        public void onError(String id, int code) {
            post(id, new Object[]{EV_ERROR});
        }

        @Override
        public void onStop(String id, boolean interrupted) {
            post(id, new Object[]{EV_STOP});
        }

        /** Ignora eventos atrasados de falas anteriores. */
        private void post(String id, Object[] event) {
            if (id != null && id.equals(currentId)) {
                events.offer(event);
            }
        }
    };
}
