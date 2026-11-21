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
    private Button btnClear;
    private Button btnDeepL;
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
                if (checked) {
                    Translator.warm(TranslationActivity.this); // conexão pronta para a primeira tradução
                }
            }
        });

        btnDeepL = findViewById(R.id.btn_deepl);
        showDeepL();
        btnDeepL.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                deepLDialog();
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
        Translator.init(this);
        btnClear = findViewById(R.id.btn_clear_saved);
        showSavedCount();
        btnClear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmClear();
            }
        });

        findViewById(R.id.btn_help).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(TranslationActivity.this)
                        .setTitle(R.string.audio_help_title)
                        .setMessage(R.string.translate_help_text)
                        .setPositiveButton("Fechar", null)
                        .show();
            }
        });

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

    private void showDeepL() {
        btnDeepL.setText(Prefs.deepLKey(prefs).isEmpty() ? "Chave do DeepL: não configurada"
                : "Chave do DeepL: configurada");
    }

    /** Janela da chave do DeepL: explicação, botão para o site, campo para colar e Salvar. */
    private void deepLDialog() {
        View form = getLayoutInflater().inflate(R.layout.dialog_deepl, null);
        final EditText key = form.findViewById(R.id.edit_deepl_key);
        key.setText(Prefs.deepLKey(prefs));
        form.findViewById(R.id.btn_deepl_site).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://www.deepl.com/pro-api")));
                } catch (Exception e) {
                    Toast.makeText(TranslationActivity.this, "Nenhum navegador encontrado.", Toast.LENGTH_SHORT)
                            .show();
                }
            }
        });
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("Chave do DeepL")
                .setView(form)
                .setPositiveButton("Salvar", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        saveDeepL(key.getText().toString().trim());
                    }
                })
                .setNegativeButton("Cancelar", null);
        if (!Prefs.deepLKey(prefs).isEmpty()) {
            b.setNeutralButton("Apagar chave", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int which) {
                    saveDeepL("");
                }
            });
        }
        b.show();
    }

    private void saveDeepL(final String key) {
        prefs.edit().putString("deepl_key", key).apply();
        Translator.deepLKey = key;
        showDeepL();
        if (key.isEmpty()) {
            Toast.makeText(this, "Chave do DeepL apagada.", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, "Chave salva. Testando…", Toast.LENGTH_SHORT).show();
        final String dst = Prefs.translateTarget(prefs);
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String err = Translator.checkDeepLKey(key, dst);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(TranslationActivity.this, err.isEmpty()
                                        ? "Chave do DeepL funcionando."
                                        : "A chave do DeepL não funcionou: " + err + ".",
                                Toast.LENGTH_LONG).show();
                    }
                });
            }
        }).start();
    }

    private void showSavedCount() {
        btnClear.setText("Apagar traduções guardadas (" + Translator.savedCount() + ")");
    }

    private void confirmClear() {
        new AlertDialog.Builder(this)
                .setTitle("Apagar as " + Translator.savedCount() + " traduções guardadas?")
                .setMessage("Os textos voltam a ser traduzidos pela internet quando aparecerem de novo.")
                .setPositiveButton("Apagar", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        Translator.clearSaved();
                        showSavedCount();
                        Toast.makeText(TranslationActivity.this, "Traduções guardadas apagadas.", Toast.LENGTH_SHORT)
                                .show();
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (btnClear != null) {
            // O arquivo carrega em segundo plano: atualiza a contagem um pouco depois também.
            showSavedCount();
            btnClear.postDelayed(new Runnable() {
                @Override
                public void run() {
                    showSavedCount();
                }
            }, 500);
        }
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
                Translator.lastError = "";
                Translator.deepLKey = Prefs.deepLKey(prefs);
                final String out = Translator.translate(text, src, dst, true, null);
                final String err = Translator.lastError;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (!err.isEmpty()) {
                            Toast.makeText(TranslationActivity.this, "Não foi possível traduzir agora. Motivo: "
                                    + err + ". Lendo o texto original.", Toast.LENGTH_LONG).show();
                        }
                        if (tts != null) {
                            tts.speak(out, TextToSpeech.QUEUE_FLUSH, null, "traducao");
                        }
                        showSavedCount();
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
