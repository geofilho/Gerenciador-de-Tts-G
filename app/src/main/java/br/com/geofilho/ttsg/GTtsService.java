package br.com.geofilho.ttsg;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.annotation.TargetApi;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Build;
import android.speech.tts.SynthesisCallback;
import android.speech.tts.SynthesisRequest;
import android.speech.tts.TextToSpeech;
import android.speech.tts.TextToSpeechService;
import android.speech.tts.Voice;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Motor de TTS do Gerenciador. Quando escolhido como motor padrão do
 * sistema (o TalkBack passa a usá-lo), ele:
 * 1. aplica o dicionário;
 * 2. separa emojis e agrupa repetições;
 * 3. manda cada trecho para o motor e a voz escolhidos, com velocidade,
 *    tonalidade e volume configurados no app.
 */
public class GTtsService extends TextToSpeechService {

    private static final String VOICE_NAME = "gerenciador-tts-g";

    private EngineClient mainEngine;
    private EngineClient emojiEngine;
    private volatile boolean stopped;
    /** Tocador próprio no alto-falante, usado quando há Bluetooth e a opção está ligada. */
    private volatile PhonePlayer phonePlayer;

    private final EngineClient.StopFlag stopFlag = new EngineClient.StopFlag() {
        @Override
        public boolean stopped() {
            return stopped;
        }
    };

    /** Motor da voz que um aplicativo pediu (uma das vozes dos outros motores). */
    /**
     * Motores das vozes pedidas pelos aplicativos, um por motor, guardados abertos (até 3), para o Ariadne
     * alternar entre a voz dos cruzamentos e a dos favoritos sem reabrir o motor a cada frase.
     */
    private final java.util.LinkedHashMap<String, EngineClient> pickedEngines =
            new java.util.LinkedHashMap<String, EngineClient>(4, 0.75f, true);

    /** Motor para a voz pedida: o principal, se for o mesmo motor; senão um guardado (abre na primeira vez). */
    private EngineClient pickedEngineFor(String pkg) {
        String mainPkg = Prefs.engine(Prefs.get(this), Prefs.MAIN);
        if (pkg.equals(mainPkg)) {
            return mainEngine;
        }
        synchronized (pickedEngines) {
            EngineClient e = pickedEngines.get(pkg);
            if (e == null) {
                if (pickedEngines.size() >= 3) {
                    String oldest = pickedEngines.keySet().iterator().next();
                    EngineClient old = pickedEngines.remove(oldest);
                    old.shutdown();
                }
                e = new EngineClient(this, "voz pedida " + pkg);
                pickedEngines.put(pkg, e);
            }
            return e;
        }
    }

    private void forEachPicked(boolean shutdown) {
        synchronized (pickedEngines) {
            for (EngineClient e : pickedEngines.values()) {
                if (shutdown) {
                    e.shutdown();
                } else {
                    e.stop();
                }
            }
            if (shutdown) {
                pickedEngines.clear();
            }
        }
    }
    private final java.util.Set<String> notedPicks =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    /** Motor instalado, removido ou atualizado: refaz a lista de vozes oferecidas aos aplicativos. */
    private final BroadcastReceiver packagesReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            VoiceCatalog.refreshLater(GTtsService.this, 3000);
        }
    };

    /** Motor reserva: usado quando o motor escolhido falha ou ainda não pode rodar. */
    private EngineClient fallbackEngine;
    private String fallbackPkg = "";
    private boolean usingFallback;

    /** Depois de digitar a senha, tenta na hora voltar ao motor e à voz escolhidos. */
    private final BroadcastReceiver unlockReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            mainEngine.retryNow();
            emojiEngine.retryNow();
        }
    };

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Object audioDeviceCallback;

    /** Se não sobrou nenhuma saída Bluetooth, desliga "Manter o áudio do leitor de tela no telefone". */
    private final Runnable checkBluetooth = new Runnable() {
        @Override
        public void run() {
            turnOffKeepOnPhoneIfNoBluetooth();
        }
    };

    private void turnOffKeepOnPhoneIfNoBluetooth() {
        SharedPreferences p = Prefs.get(this);
        if (Prefs.keepOnPhone(p) && PhonePlayer.bluetoothOutputName(this) == null) {
            p.edit().putBoolean("keep_on_phone", false).apply();
            Toggles.refresh(this);
            Diagnostics.note(this, "Bluetooth desconectado: a opção Manter o áudio do leitor de tela no telefone "
                    + "foi desativada.");
        }
    }

    @TargetApi(23)
    private void watchBluetooth() {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am == null) {
            return;
        }
        AudioDeviceCallback cb = new AudioDeviceCallback() {
            @Override
            public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) {
                // Confere de novo depois de 2 s, para não desligar numa queda rápida da conexão.
                handler.removeCallbacks(checkBluetooth);
                handler.postDelayed(checkBluetooth, 2000);
            }
        };
        am.registerAudioDeviceCallback(cb, handler);
        audioDeviceCallback = cb;
    }

    /** Android 12+: leva todo o áudio de acessibilidade (fala e sons do leitor) ao alto-falante. */
    private AccessibilityRouter router;

    @Override
    public void onCreate() {
        if (AccessibilityRouter.supported()) {
            try {
                router = new AccessibilityRouter(this);
                router.start();
            } catch (Exception e) {
                router = null;
            }
        }
        if (Build.VERSION.SDK_INT >= 23) {
            try {
                watchBluetooth();
            } catch (Exception ignored) {
            }
        }
        if (Prefs.translateOn(Prefs.get(this))) {
            Translator.init(this); // carrega as traduções guardadas em segundo plano
        }
        Translator.warm(this);
        mainEngine = new EngineClient(this, "principal");
        emojiEngine = new EngineClient(this, "emoji");
        fallbackEngine = new EngineClient(this, "reserva");
        warmUp();
        // Lista de vozes dos outros motores: atualiza em segundo plano e quando um motor muda.
        VoiceCatalog.refreshIfEnginesChanged(this, 4000);
        try {
            IntentFilter f = new IntentFilter();
            f.addAction(Intent.ACTION_PACKAGE_ADDED);
            f.addAction(Intent.ACTION_PACKAGE_REMOVED);
            f.addAction(Intent.ACTION_PACKAGE_REPLACED);
            f.addAction(Intent.ACTION_PACKAGE_CHANGED);
            f.addDataScheme("package");
            registerReceiver(packagesReceiver, f);
        } catch (Exception ignored) {
        }
        if (Build.VERSION.SDK_INT >= 24) {
            try {
                registerReceiver(unlockReceiver, new IntentFilter(Intent.ACTION_USER_UNLOCKED));
            } catch (Exception ignored) {
            }
        }
        super.onCreate();
    }

    /**
     * Conecta já ao motor e à voz escolhidos, em segundo plano, para a primeira fala do leitor
     * de tela não esperar essa conexão (depois de religar ou quando o Android reabre o serviço).
     */
    private void warmUp() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    SharedPreferences p = Prefs.get(GTtsService.this);
                    String pkg = Prefs.engine(p, Prefs.MAIN);
                    if (pkg.isEmpty()) {
                        pkg = EngineClient.firstEngine(GTtsService.this);
                    }
                    if (mainEngine.ensure(pkg, null)) {
                        mainEngine.prepareVoice(Prefs.voice(p, Prefs.MAIN));
                    }
                    Dictionary.get(GTtsService.this); // carrega o dicionário de pronúncia também
                } catch (Exception ignored) {
                }
            }
        }, "preparar-motor");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public void onDestroy() {
        releaseIdlePlayer();
        if (router != null) {
            router.stop();
        }
        handler.removeCallbacks(checkBluetooth);
        if (Build.VERSION.SDK_INT >= 23 && audioDeviceCallback != null) {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (am != null) {
                am.unregisterAudioDeviceCallback((AudioDeviceCallback) audioDeviceCallback);
            }
        }
        if (Build.VERSION.SDK_INT >= 24) {
            try {
                unregisterReceiver(unlockReceiver);
            } catch (Exception ignored) {
            }
        }
        mainEngine.shutdown();
        emojiEngine.shutdown();
        fallbackEngine.shutdown();
        forEachPicked(true);
        try {
            unregisterReceiver(packagesReceiver);
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }

    // ----- idioma e voz que o app anuncia ao sistema -----

    private Locale configuredLocale() {
        return Locale.forLanguageTag(Prefs.language(Prefs.get(this)));
    }

    @Override
    protected int onIsLanguageAvailable(String lang, String country, String variant) {
        // Qualquer idioma é aceito: o texto sempre vai para a voz escolhida no app.
        return TextToSpeech.LANG_COUNTRY_AVAILABLE;
    }

    @Override
    protected String[] onGetLanguage() {
        Locale l = configuredLocale();
        return new String[]{iso3Language(l), iso3Country(l), ""};
    }

    @Override
    protected int onLoadLanguage(String lang, String country, String variant) {
        return TextToSpeech.LANG_COUNTRY_AVAILABLE;
    }

    /**
     * A voz padrão do Gerenciador (primeira da lista) e as vozes dos outros motores instalados, para os
     * aplicativos que deixam escolher a voz (Ariadne GPS e outros). A lista vem guardada, sem esperar.
     */
    @Override
    public List<Voice> onGetVoices() {
        List<Voice> list = new ArrayList<>();
        list.add(new Voice(VOICE_NAME, configuredLocale(), Voice.QUALITY_HIGH, Voice.LATENCY_NORMAL,
                false, Collections.<String>emptySet()));
        list.addAll(VoiceCatalog.voices(this));
        if (VoiceCatalog.count(this) == 0) {
            VoiceCatalog.refreshLater(this, 0); // primeira vez: monta a lista para a próxima consulta
        }
        return list;
    }

    @Override
    public int onIsValidVoiceName(String voiceName) {
        // A voz padrão, as dos outros motores e, por segurança, qualquer outra (vai para a voz padrão).
        return TextToSpeech.SUCCESS;
    }

    @Override
    public int onLoadVoice(String voiceName) {
        return TextToSpeech.SUCCESS;
    }

    @Override
    public String onGetDefaultVoiceNameFor(String lang, String country, String variant) {
        return VOICE_NAME;
    }

    @Override
    public Set<String> onGetFeaturesForLanguage(String lang, String country, String variant) {
        return Collections.emptySet();
    }

    // ----- síntese -----

    @Override
    protected void onStop() {
        stopped = true;
        PhonePlayer pp = phonePlayer;
        if (pp != null) {
            pp.stop();
        }
        mainEngine.stop();
        emojiEngine.stop();
        fallbackEngine.stop();
        forEachPicked(false);
    }

    @Override
    protected void onSynthesizeText(SynthesisRequest request, SynthesisCallback cb) {
        stopped = false;
        CharSequence cs = request.getCharSequenceText();
        String text = cs != null ? cs.toString() : request.getText();
        if (text == null) {
            text = "";
        }

        SharedPreferences p = Prefs.get(this);
        float reqRate = request.getSpeechRate() / 100f;
        float reqPitch = request.getPitch() / 100f;

        String original = text;
        // Botão "sem etiqueta": troca pelo nome encontrado pelo serviço de acessibilidade.
        if (Prefs.unlabeledOn(p)) {
            text = Unlabeled.replace(this, text);
        }
        // Tradução automática (se ligada): antes do dicionário, que está no idioma do usuário.
        text = Translator.maybeTranslate(this, text, stopFlag);
        if (stopped) {
            return;
        }
        text = Dictionary.get(this).apply(text);
        if (Prefs.dropEmojiWord(p)) {
            text = TextProcessor.dropEmojiWord(text);
        }
        boolean separateNumbers = Prefs.separateNumbers(p);
        List<TextProcessor.Segment> segments = TextProcessor.process(text, Prefs.groupRepeats(p),
                Prefs.numberMode(p), separateNumbers, Prefs.punctuationMode(p));

        String mainPkg = Prefs.engine(p, Prefs.MAIN);
        if (mainPkg.isEmpty()) {
            mainPkg = EngineClient.firstEngine(this);
        }
        String emojiPkg = Prefs.engine(p, Prefs.EMOJI);
        // Só prepara o motor de emojis quando o texto tem emoji.
        boolean hasEmoji = false;
        for (TextProcessor.Segment seg : segments) {
            if (seg.emoji) {
                hasEmoji = true;
                break;
            }
        }
        boolean useEmojiEngine = hasEmoji && !emojiPkg.isEmpty() && emojiEngine.ensure(emojiPkg, stopFlag);
        // Junta o que vai para a mesma voz e velocidade (sem voz de emojis, eles vão na principal).
        segments = TextProcessor.merge(segments, useEmojiEngine, separateNumbers);

        // Voz pedida pelo aplicativo (uma das vozes dos outros motores): fala com aquele motor e aquela
        // voz, com tudo o que o Gerenciador faz. Sem voz pedida (TalkBack e outros), segue como sempre.
        VoiceCatalog.Entry picked = VoiceCatalog.find(this, request.getVoiceName());
        EngineClient pickedUse = null;
        if (picked != null) {
            pickedUse = pickedEngineFor(picked.pkg);
            boolean ok = pickedUse.ensure(picked.pkg, stopFlag);
            String note = "um aplicativo pediu a voz " + picked.voice + " do motor " + picked.pkg
                    + (ok ? ": o motor respondeu." : ": o motor não respondeu; foi usada a voz padrão do Gerenciador.");
            // Uma vez por voz e resultado enquanto o motor está aberto: o Ariadne alterna vozes a toda
            // hora, e os avisos de troca de motor não podem sumir do relatório por causa disso.
            if (notedPicks.add(note)) {
                Diagnostics.note(this, note);
            }
            if (stopped) {
                return;
            }
            if (ok) {
                mainPkg = picked.pkg;
            } else {
                picked = null;
            }
        }

        // Motor principal; se ele falhar ou ainda não puder rodar, usa o motor reserva.
        EngineClient mainUse = picked != null ? pickedUse : mainEngine;
        String mainVoice = picked != null ? picked.voice : Prefs.voice(p, Prefs.MAIN);
        if (picked == null && !mainEngine.ensure(mainPkg, stopFlag)) {
            if (stopped) {
                return;
            }
            mainUse = useFallback(mainPkg);
            mainVoice = "";
            if (mainUse == null) {
                Diagnostics.note(this, "nenhum motor respondeu (escolhido: " + mainPkg + ").");
                cb.error();
                return;
            }
            usingFallback = true;
        } else if (usingFallback) {
            usingFallback = false;
            Diagnostics.note(this, "voltou ao motor escolhido " + mainPkg + ".");
        }
        if (Prefs.diagnosticsOn(p)) {
            Diagnostics.record(original, segments, useEmojiEngine,
                    mainUse == fallbackEngine ? "reserva " + fallbackPkg : mainPkg);
        }

        // Fala no telefone e música no Bluetooth: o próprio app toca no alto-falante.
        boolean toPhone = false;
        if (Prefs.keepOnPhone(p)) {
            if (PhonePlayer.bluetoothOutputName(this) != null) {
                toPhone = true;
                if (router != null) {
                    router.ensure();
                }
            } else {
                turnOffKeepOnPhoneIfNoBluetooth();
            }
        }
        if (!toPhone) {
            releaseIdlePlayer(); // a fala voltou para a saída normal: fecha o canal guardado
        }
        Output out = new Output(cb, toPhone);
        speaking:
        for (TextProcessor.Segment seg : segments) {
            boolean emoji = seg.emoji && useEmojiEngine;
            String who = emoji ? Prefs.EMOJI : Prefs.MAIN;
            EngineClient eng = emoji ? emojiEngine : mainUse;
            String voice = emoji ? Prefs.voice(p, Prefs.EMOJI) : mainVoice;
            for (String piece : splitLong(seg.text)) {
                if (stopped) {
                    break speaking;
                }
                int rate = seg.number ? Prefs.numberRate(p) : Prefs.rate(p, who);
                float r = Levels.rate(rate) * reqRate;
                float pt = Levels.pitch(Prefs.pitch(p, who)) * reqPitch;
                out.gain = Levels.volume(Prefs.volume(p, who));
                // O áudio vai tocando à medida que o motor gera (ver Output.write).
                int res = eng.synth(piece, voice, r, pt, stopFlag, out);
                if (res == EngineClient.FAIL && !stopped && !out.failed && eng != fallbackEngine) {
                    // O motor falhou nesta fala: tenta com o motor reserva para não ficar mudo.
                    EngineClient fb = useFallback(emoji ? Prefs.engine(p, Prefs.EMOJI) : mainPkg);
                    if (fb != null) {
                        res = fb.synth(piece, "", r, pt, stopFlag, out);
                    }
                }
                if (out.failed) {
                    releasePhonePlayer(false);
                    return;
                }
                if (res == EngineClient.ABORTED) {
                    break speaking;
                }
            }
        }
        if (out.outRate == 0) {
            // Nada para falar (ou interrompido antes do primeiro som).
            cb.start(22050, AudioFormat.ENCODING_PCM_16BIT, 1);
        }
        // Só avisa que terminou depois que o alto-falante acabou de tocar.
        releasePhonePlayer(true);
        cb.done();
    }

    /** Recebe os pedaços de áudio dos motores e toca na hora (sistema ou alto-falante). */
    private final class Output implements EngineClient.AudioSink {
        private final SynthesisCallback cb;
        private final boolean toPhone;
        int outRate;
        float gain = 1f;
        boolean failed;

        Output(SynthesisCallback cb, boolean toPhone) {
            this.cb = cb;
            this.toPhone = toPhone;
        }

        @Override
        public boolean write(short[] mono, int rate) {
            if (stopped) {
                return false;
            }
            if (outRate == 0) {
                outRate = rate;
                if (cb.start(outRate, AudioFormat.ENCODING_PCM_16BIT, 1) != TextToSpeech.SUCCESS) {
                    failed = true;
                    return false;
                }
                if (toPhone) {
                    phonePlayer = takePlayer(outRate);
                }
            }
            return send(Pcm.toBytes(Pcm.resample(mono, rate, outRate), gain));
        }

        @Override
        public boolean writePcm16(byte[] data, int len, int rate) {
            if (stopped) {
                return false;
            }
            if ((outRate != 0 && rate != outRate) || gain < 0.999f) {
                // Precisa ajustar volume ou taxa: converte e segue pelo caminho normal.
                short[] mono = new short[len / 2];
                for (int i = 0; i < mono.length; i++) {
                    mono[i] = (short) ((data[2 * i] & 0xFF) | (data[2 * i + 1] << 8));
                }
                return write(mono, rate);
            }
            if (outRate == 0) {
                outRate = rate;
                if (cb.start(outRate, AudioFormat.ENCODING_PCM_16BIT, 1) != TextToSpeech.SUCCESS) {
                    failed = true;
                    return false;
                }
                if (toPhone) {
                    phonePlayer = takePlayer(outRate);
                }
            }
            // Já está no formato final: segue direto, sem copiar nem converter.
            return send(len == data.length ? data : java.util.Arrays.copyOf(data, len));
        }

        private boolean send(byte[] bytes) {
            PhonePlayer pp = phonePlayer;
            boolean ok = pp != null ? pp.write(bytes) : GTtsService.this.write(cb, bytes);
            if (!ok) {
                failed = true;
            }
            return ok;
        }
    }

    private void releasePhonePlayer(boolean waitEnd) {
        PhonePlayer pp = phonePlayer;
        phonePlayer = null;
        if (pp != null) {
            if (waitEnd) {
                pp.waitEnd();
                parkPlayer(pp); // guarda o canal para a próxima fala
            } else {
                pp.release();
            }
        }
    }

    // ----- canal do alto-falante guardado entre as falas -----

    private final Object playerLock = new Object();
    private PhonePlayer idlePlayer;
    private static final long KEEP_PLAYER_MS = 8000;
    private final Runnable releaseIdle = new Runnable() {
        @Override
        public void run() {
            releaseIdlePlayer();
        }
    };

    /** Canal guardado da fala anterior, se servir; senão abre um novo. */
    private PhonePlayer takePlayer(int rate) {
        PhonePlayer old;
        synchronized (playerLock) {
            old = idlePlayer;
            idlePlayer = null;
        }
        handler.removeCallbacks(releaseIdle);
        if (old != null) {
            if (old.reuse(rate)) {
                return old;
            }
            old.release();
        }
        return PhonePlayer.open(this, rate);
    }

    private void parkPlayer(PhonePlayer pp) {
        pp.park();
        PhonePlayer old;
        synchronized (playerLock) {
            old = idlePlayer;
            idlePlayer = pp;
        }
        if (old != null && old != pp) {
            old.release();
        }
        handler.removeCallbacks(releaseIdle);
        handler.postDelayed(releaseIdle, KEEP_PLAYER_MS); // sem falas por 8 s: fecha o canal
    }

    private void releaseIdlePlayer() {
        PhonePlayer old;
        synchronized (playerLock) {
            old = idlePlayer;
            idlePlayer = null;
        }
        if (old != null) {
            old.release();
        }
    }

    /** Prepara o motor reserva (padrão do aparelho) e registra o aviso no diagnóstico. */
    private EngineClient useFallback(String failedPkg) {
        String fb = EngineClient.fallbackEngine(this, failedPkg);
        if (fb.isEmpty()) {
            return null;
        }
        fallbackEngine.setLocale(configuredLocale());
        if (!fallbackEngine.ensure(fb, stopFlag)) {
            return null;
        }
        fallbackPkg = fb;
        boolean locked = false;
        if (Build.VERSION.SDK_INT >= 24) {
            android.os.UserManager um = (android.os.UserManager) getSystemService(USER_SERVICE);
            locked = um != null && !um.isUserUnlocked();
        }
        Diagnostics.note(this, "o motor escolhido " + failedPkg + " não respondeu"
                + (locked ? " (aparelho ainda bloqueado depois de religar)" : "")
                + "; foi usado o motor reserva " + fb + ".");
        return fallbackEngine;
    }

    private boolean write(SynthesisCallback cb, byte[] data) {
        int max = cb.getMaxBufferSize();
        for (int off = 0; off < data.length; off += max) {
            if (stopped) {
                return false;
            }
            int len = Math.min(max, data.length - off);
            if (cb.audioAvailable(data, off, len) != TextToSpeech.SUCCESS) {
                return false;
            }
        }
        return true;
    }

    /** Textos longos são falados por frases, para o som começar mais rápido. */
    static List<String> splitLong(String text) {
        List<String> out = new ArrayList<>();
        if (text.length() <= 250) {
            out.add(text);
            return out;
        }
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean end = c == '\n' || ((c == '.' || c == '!' || c == '?' || c == ';')
                    && i + 1 < text.length() && Character.isWhitespace(text.charAt(i + 1)));
            if ((end && i - start >= 40) || i - start >= 400) {
                out.add(text.substring(start, i + 1));
                start = i + 1;
            }
        }
        if (start < text.length() && text.substring(start).trim().length() > 0) {
            out.add(text.substring(start));
        }
        return out;
    }

    private static String iso3Language(Locale l) {
        try {
            return l.getISO3Language();
        } catch (Exception e) {
            return "por";
        }
    }

    private static String iso3Country(Locale l) {
        try {
            return l.getISO3Country();
        } catch (Exception e) {
            return "";
        }
    }
}
