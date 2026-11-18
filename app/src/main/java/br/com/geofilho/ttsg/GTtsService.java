package br.com.geofilho.ttsg;

import android.content.SharedPreferences;
import android.media.AudioFormat;
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

    private final EngineClient.StopFlag stopFlag = new EngineClient.StopFlag() {
        @Override
        public boolean stopped() {
            return stopped;
        }
    };

    @Override
    public void onCreate() {
        mainEngine = new EngineClient(this, "principal");
        emojiEngine = new EngineClient(this, "emoji");
        super.onCreate();
    }

    @Override
    public void onDestroy() {
        mainEngine.shutdown();
        emojiEngine.shutdown();
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
        mainEngine.stop();
        emojiEngine.stop();
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

        text = Dictionary.get(this).apply(text);
        List<TextProcessor.Segment> segments = TextProcessor.split(text, Prefs.groupRepeats(p));

        String mainPkg = Prefs.engine(p, Prefs.MAIN);
        if (mainPkg.isEmpty()) {
            mainPkg = EngineClient.firstEngine(this);
        }
        String emojiPkg = Prefs.engine(p, Prefs.EMOJI);
        boolean useEmojiEngine = !emojiPkg.isEmpty() && emojiEngine.ensure(emojiPkg, stopFlag);
        if (!useEmojiEngine && segments.size() > 1) {
            // Sem voz própria para emojis: fala tudo de uma vez na voz principal.
            List<TextProcessor.Segment> one = new ArrayList<>();
            one.add(new TextProcessor.Segment(TextProcessor.join(segments), false));
            segments = one;
        }

        if (!mainEngine.ensure(mainPkg, stopFlag)) {
            if (!stopped) {
                cb.error();
            }
            return;
        }

        int outRate = 0;
        for (TextProcessor.Segment seg : segments) {
            if (stopped) {
                break;
            }
            boolean emoji = seg.emoji && useEmojiEngine;
            String who = emoji ? Prefs.EMOJI : Prefs.MAIN;
            EngineClient eng = emoji ? emojiEngine : mainEngine;
            for (String piece : splitLong(seg.text)) {
                if (stopped) {
                    break;
                }
                Pcm pcm = eng.synth(piece, Prefs.voice(p, who),
                        Levels.rate(Prefs.rate(p, who)) * reqRate,
                        Levels.pitch(Prefs.pitch(p, who)) * reqPitch, stopFlag);
                if (pcm == null || pcm.samples.length == 0 || stopped) {
                    continue;
                }
                if (outRate == 0) {
                    outRate = pcm.rate;
                    if (cb.start(outRate, AudioFormat.ENCODING_PCM_16BIT, 1) != TextToSpeech.SUCCESS) {
                        return;
                    }
                }
                short[] s = Pcm.resample(pcm.samples, pcm.rate, outRate);
                if (!write(cb, Pcm.toBytes(s, Levels.volume(Prefs.volume(p, who))))) {
                    return;
                }
            }
        }
        if (outRate == 0) {
            // Nada para falar (ou interrompido antes do primeiro som).
            cb.start(22050, AudioFormat.ENCODING_PCM_16BIT, 1);
        }
        cb.done();
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
