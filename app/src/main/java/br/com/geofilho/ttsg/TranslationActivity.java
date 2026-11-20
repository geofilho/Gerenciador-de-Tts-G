package br.com.geofilho.ttsg;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Página "Tradução de voz TTS": chave, idioma de origem (Automático), idioma de
 * destino (padrão: idioma do aparelho), aviso e teste.
 */
public class TranslationActivity extends Activity {

    private SharedPreferences prefs;
    private Button btnSource;
    private Button btnTarget;
    private TextToSpeech tts;
    private boolean ready;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_translation);
        prefs = Prefs.get(this);

        Switch sw = findViewById(R.id.switch_translate);
        sw.setChecked(Prefs.translateOn(prefs));
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                prefs.edit().putBoolean("translate_on", checked).apply();
            }
        });

        btnSource = findViewById(R.id.btn_source);
        btnTarget = findViewById(R.id.btn_target);
        refresh();
        btnSource.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                choose("Idioma de origem", true);
            }
        });
        btnTarget.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                choose("Idioma de destino", false);
            }
        });

        // O teste fala pelo próprio Gerenciador, com a voz, o dicionário e os ajustes escolhidos.
        tts = new TextToSpeech(this, new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                ready = status == TextToSpeech.SUCCESS;
            }
        }, getPackageName());
        final EditText edit = findViewById(R.id.edit_test);
        findViewById(R.id.btn_test).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                test(edit.getText().toString());
            }
        });
    }

    private void refresh() {
        btnSource.setText("Idioma de origem: " + Translator.displayName(Prefs.translateSource(prefs)));
        btnTarget.setText("Idioma de destino: " + Translator.displayName(Prefs.translateTarget(prefs)));
    }

    /** Lista de idiomas em ordem alfabética; a origem começa com "Automático". */
    private void choose(String title, final boolean source) {
        final List<String> codes = new ArrayList<>();
        for (String c : Translator.LANGUAGES) {
            codes.add(c);
        }
        String device = Translator.deviceLanguage();
        if (!codes.contains(device)) {
            codes.add(device);
        }
        Collections.sort(codes, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                return Translator.displayName(a).compareToIgnoreCase(Translator.displayName(b));
            }
        });
        if (source) {
            codes.add(0, "auto");
        }
        String current = source ? Prefs.translateSource(prefs) : Prefs.translateTarget(prefs);
        String[] names = new String[codes.size()];
        int checked = -1;
        for (int i = 0; i < names.length; i++) {
            names[i] = Translator.displayName(codes.get(i));
            if (codes.get(i).equals(current)) {
                checked = i;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(names, checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        prefs.edit().putString(source ? "translate_src" : "translate_dst", codes.get(which)).apply();
                        refresh();
                        d.dismiss();
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void test(final String text) {
        if (!ready) {
            Toast.makeText(this, "O motor ainda está carregando.", Toast.LENGTH_SHORT).show();
            return;
        }
        final String src = Prefs.translateSource(prefs);
        final String dst = Prefs.translateTarget(prefs);
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String out = Translator.translate(text, src, dst);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (out.equals(text) && !Translator.looksLike(text, dst)) {
                            Toast.makeText(TranslationActivity.this, "Não foi possível traduzir agora. "
                                    + "Confira a internet. Lendo o texto original.", Toast.LENGTH_SHORT).show();
                        }
                        if (tts != null) {
                            tts.speak(out, TextToSpeech.QUEUE_FLUSH, null, "traducao");
                        }
                    }
                });
            }
        }).start();
    }

    @Override
    protected void onDestroy() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        super.onDestroy();
    }
}
