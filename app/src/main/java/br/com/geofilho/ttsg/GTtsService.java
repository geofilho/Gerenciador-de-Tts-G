package br.com.geofilho.ttsg;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.media.AudioFormat;
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

    @Override
    public void onCreate() {
        mainEngine = new EngineClient(this, "principal");
        emojiEngine = new EngineClient(this, "emoji");
        fallbackEngine = new EngineClient(this, "reserva");
        if (Build.VERSION.SDK_INT >= 24) {
            try {
                registerReceiver(unlockReceiver, new IntentFilter(Intent.ACTION_USER_UNLOCKED));
            } catch (Exception ignored) {
            }
        }
        super.onCreate();
    }

    @Override
    public void onDestroy() {
        if (Build.VERSION.SDK_INT >= 24) {
            try {
                unregisterReceiver(unlockReceiver);
            } catch (Exception ignored) {
            }
        }
        mainEngine.shutdown();
        emojiEngine.shutdown();
        fallbackEngine.shutdown();
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

    @Override
    public List<Voice> onGetVoices() {
        List<Voice> list = new ArrayList<>();
        list.add(new Voice(VOICE_NAME, configuredLocale(), Voice.QUALITY_HIGH, Voice.LATENCY_NORMAL,
                false, Collections.<String>emptySet()));
        return list;
    }

    @Override
    public int onIsValidVoiceName(String voiceName) {
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
        text = Dictionary.get(this).apply(text);
        boolean separateNumbers = Prefs.separateNumbers(p);
        List<TextProcessor.Segment> segments = TextProcessor.process(text, Prefs.groupRepeats(p),
                Prefs.numberMode(p), separateNumbers, Prefs.punctuationMode(p));

        String mainPkg = Prefs.engine(p, Prefs.MAIN);
        if (mainPkg.isEmpty()) {
            mainPkg = EngineClient.firstEngine(this);
        }
        String emojiPkg = Prefs.engine(p, Prefs.EMOJI);
        boolean useEmojiEngine = !emojiPkg.isEmpty() && emojiEngine.ensure(emojiPkg, stopFlag);
        // Junta o que vai para a mesma voz e velocidade (sem voz de emojis, eles vão na principal).
        segments = TextProcessor.merge(segments, useEmojiEngine, separateNumbers);

        // Motor principal; se ele falhar ou ainda não puder rodar, usa o motor reserva.
        EngineClient mainUse = mainEngine;
        String mainVoice = Prefs.voice(p, Prefs.MAIN);
        if (!mainEngine.ensure(mainPkg, stopFlag)) {
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
        boolean toPhone = Prefs.keepOnPhone(p) && PhonePlayer.bluetoothOutputName(this) != null;
        int outRate = 0;
        for (TextProcessor.Segment seg : segments) {
            if (stopped) {
                break;
            }
            boolean emoji = seg.emoji && useEmojiEngine;
            String who = emoji ? Prefs.EMOJI : Prefs.MAIN;
            EngineClient eng = emoji ? emojiEngine : mainUse;
            String voice = emoji ? Prefs.voice(p, Prefs.EMOJI) : mainVoice;
            for (String piece : splitLong(seg.text)) {
                if (stopped) {
                    break;
                }
                int rate = seg.number ? Prefs.numberRate(p) : Prefs.rate(p, who);
                float r = Levels.rate(rate) * reqRate;
                float pt = Levels.pitch(Prefs.pitch(p, who)) * reqPitch;
                Pcm pcm = eng.synth(piece, voice, r, pt, stopFlag);
                if (pcm == null && !stopped && eng != fallbackEngine) {
                    // O motor falhou nesta fala: tenta com o motor reserva para não ficar mudo.
                    EngineClient fb = useFallback(emoji ? Prefs.engine(p, Prefs.EMOJI) : mainPkg);
                    if (fb != null) {
                        pcm = fb.synth(piece, "", r, pt, stopFlag);
                    }
                }
                if (pcm == null || pcm.samples.length == 0 || stopped) {
                    continue;
                }
                if (outRate == 0) {
                    outRate = pcm.rate;
                    if (cb.start(outRate, AudioFormat.ENCODING_PCM_16BIT, 1) != TextToSpeech.SUCCESS) {
                        return;
                    }
                    if (toPhone) {
                        phonePlayer = PhonePlayer.open(this, outRate);
                    }
                }
                short[] s = Pcm.resample(pcm.samples, pcm.rate, outRate);
                byte[] bytes = Pcm.toBytes(s, Levels.volume(Prefs.volume(p, who)));
                PhonePlayer pp = phonePlayer;
                boolean ok = pp != null ? pp.write(bytes) : write(cb, bytes);
                if (!ok) {
                    releasePhonePlayer(false);
                    return;
                }
            }
        }
        if (outRate == 0) {
            // Nada para falar (ou interrompido antes do primeiro som).
            cb.start(22050, AudioFormat.ENCODING_PCM_16BIT, 1);
        }
        // Só avisa que terminou depois que o alto-falante acabou de tocar.
        releasePhonePlayer(true);
        cb.done();
    }

    private void releasePhonePlayer(boolean waitEnd) {
        PhonePlayer pp = phonePlayer;
        phonePlayer = null;
        if (pp != null) {
            if (waitEnd) {
                pp.finish();
            } else {
                pp.release();
            }
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
