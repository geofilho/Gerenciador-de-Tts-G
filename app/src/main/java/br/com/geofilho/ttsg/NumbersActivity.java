package br.com.geofilho.ttsg;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import java.util.List;
import java.util.Set;

/** Página "Leitura de números, pontuação e emojis". */
public class NumbersActivity extends Activity {

    /** Na mesma ordem das constantes NUMBERS_* do TextProcessor. */
    private static final String[] NUMBER_MODES = {"Número inteiro", "Um a um", "Dois a dois"};
    /** Na mesma ordem das constantes PUNCT_* do TextProcessor. */
    private static final String[] PUNCT_MODES = {"Nenhuma", "Algumas", "Todas"};

    private SharedPreferences prefs;
    private Button btnNumbers;
    private Button btnPunctuation;
    private Button btnGboard;
    private TextToSpeech tts;
    private boolean ready;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_numbers);
        prefs = Prefs.get(this);

        btnNumbers = findViewById(R.id.btn_numbers);
        btnPunctuation = findViewById(R.id.btn_punctuation);
        refresh();

        btnNumbers.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                choose("Leitura de números", NUMBER_MODES, "number_mode", Prefs.numberMode(prefs));
            }
        });
        btnPunctuation.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                choose("Leitura de pontuação", PUNCT_MODES, "punctuation_mode", Prefs.punctuationMode(prefs));
            }
        });

        if (!prefs.contains("number_rate")) {
            prefs.edit().putInt("number_rate", Prefs.numberRate(prefs)).apply();
        }
        VoiceSection.bindSlider(this, prefs, R.id.seek_number_rate, R.id.lbl_number_rate,
                "Velocidade dos números", "number_rate", Prefs.numberRate(prefs));

        findViewById(R.id.btn_emoji).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new android.content.Intent(NumbersActivity.this, EmojiActivity.class));
            }
        });

        android.widget.Switch swGboard = findViewById(R.id.switch_gboard);
        swGboard.setChecked(Prefs.gboardOn(prefs));
        swGboard.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                prefs.edit().putBoolean("gboard_on", checked).apply();
                if (checked && !gboardServiceOn()) {
                    explainGboard();
                }
            }
        });
        btnGboard = findViewById(R.id.btn_gboard_service);
        btnGboard.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                explainGboard();
            }
        });

        final EditText editTest = findViewById(R.id.edit_test);
        String engine = Prefs.engine(prefs, Prefs.MAIN);
        if (engine.isEmpty()) {
            engine = EngineClient.firstEngine(this);
        }
        tts = new TextToSpeech(this, new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                ready = status == TextToSpeech.SUCCESS;
            }
        }, engine);
        findViewById(R.id.btn_test).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                test(editTest.getText().toString());
            }
        });
    }

    @Override
    protected void onDestroy() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        super.onDestroy();
    }

    /** Fala com a voz principal e as mesmas regras do motor. */
    private void test(String text) {
        if (tts == null || !ready) {
            Toast.makeText(this, "O motor ainda está carregando.", Toast.LENGTH_SHORT).show();
            return;
        }
        String voiceName = Prefs.voice(prefs, Prefs.MAIN);
        try {
            Set<Voice> voices = tts.getVoices();
            if (voices != null) {
                for (Voice v : voices) {
                    if (v.getName().equals(voiceName)) {
                        tts.setVoice(v);
                        break;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        tts.setPitch(Levels.pitch(Prefs.pitch(prefs, Prefs.MAIN)));
        Bundle params = new Bundle();
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, Levels.volume(Prefs.volume(prefs, Prefs.MAIN)));
        String t = Dictionary.get(this).apply(text);
        boolean sep = Prefs.separateNumbers(prefs);
        List<TextProcessor.Segment> segs = TextProcessor.merge(TextProcessor.process(t, Prefs.groupRepeats(prefs),
                Prefs.numberMode(prefs), sep, Prefs.punctuationMode(prefs)), false, sep);
        if (segs.isEmpty()) {
            tts.stop();
            return;
        }
        for (int i = 0; i < segs.size(); i++) {
            TextProcessor.Segment seg = segs.get(i);
            tts.setSpeechRate(Levels.rate(seg.number ? Prefs.numberRate(prefs) : Prefs.rate(prefs, Prefs.MAIN)));
            tts.speak(seg.text, i == 0 ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD, params, "teste" + i);
        }
    }

    private void refresh() {
        btnNumbers.setText("Leitura de números: " + label(NUMBER_MODES, Prefs.numberMode(prefs)));
        btnPunctuation.setText("Leitura de pontuação: " + label(PUNCT_MODES, Prefs.punctuationMode(prefs)));
    }

    private static String label(String[] items, int index) {
        return items[index >= 0 && index < items.length ? index : 0];
    }

    private void choose(String title, String[] items, final String key, int checked) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(items, checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        prefs.edit().putInt(key, which).apply();
                        refresh();
                        d.dismiss();
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (btnGboard != null) {
            btnGboard.setText(gboardServiceOn()
                    ? "Serviço das sugestões do Gboard: ativado. Toque para desativar nas configurações"
                    : "Serviço das sugestões do Gboard: desativado. Toque para ativar nas configurações");
        }
    }

    /** O serviço "ler sugestões do Gboard" está ligado nas configurações de acessibilidade? */
    private boolean gboardServiceOn() {
        try {
            String on = android.provider.Settings.Secure.getString(getContentResolver(),
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return on != null && on.contains(getPackageName() + "/") && on.contains("GboardService");
        } catch (Exception e) {
            return false;
        }
    }

    private void explainGboard() {
        new AlertDialog.Builder(this)
                .setTitle("Sugestões do Gboard (teste)")
                .setMessage("Para ler as sugestões, ative o serviço Gerenciador de TTS G: ler sugestões do Gboard. "
                        + "Toque em Abrir configurações, depois em Aplicativos instalados, e ative o serviço.\n\n"
                        + "Se a opção estiver bloqueada (configuração restrita): toque em Informações do app, no "
                        + "menu de três pontos, no canto de cima, e em Permitir configurações restritas. Depois "
                        + "volte e ative.\n\n"
                        + "O serviço observa só o teclado Gboard e nunca lê nada em campos de senha.")
                .setPositiveButton("Abrir configurações", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        try {
                            startActivity(new android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS));
                        } catch (Exception ignored) {
                        }
                    }
                })
                .setNeutralButton("Informações do app", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        try {
                            startActivity(new android.content.Intent(
                                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    android.net.Uri.parse("package:" + getPackageName())));
                        } catch (Exception ignored) {
                        }
                    }
                })
                .setNegativeButton("Fechar", null)
                .show();
    }
}
