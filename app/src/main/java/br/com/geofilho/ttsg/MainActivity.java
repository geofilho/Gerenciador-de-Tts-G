package br.com.geofilho.ttsg;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import java.util.List;

/** Tela única: voz principal, teste, ajustes, dicionário e atalhos. */
public class MainActivity extends Activity {

    private VoiceSection voice;
    private Button btnSystem;
    private Updater updater;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        final EditText editTest = findViewById(R.id.edit_test);
        voice = new VoiceSection(this, Prefs.MAIN, true, false, new VoiceSection.TextProvider() {
            @Override
            public List<TextProcessor.Segment> segments() {
                // O teste passa pelas mesmas regras do motor: dicionário, repetidos, números e pontuação.
                SharedPreferences p = Prefs.get(MainActivity.this);
                String t = Dictionary.get(MainActivity.this).apply(editTest.getText().toString());
                boolean sep = Prefs.separateNumbers(p);
                return TextProcessor.merge(TextProcessor.process(t, Prefs.groupRepeats(p), Prefs.numberMode(p), sep,
                        Prefs.punctuationMode(p)), false, sep);
            }
        });

        findViewById(R.id.btn_dictionary).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showDictionaryMenu();
            }
        });
        btnSystem = findViewById(R.id.btn_system);
        btnSystem.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openTtsSettings();
            }
        });
        findViewById(R.id.btn_numbers).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, NumbersActivity.class));
            }
        });

        findViewById(R.id.btn_audio).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, AudioActivity.class));
            }
        });

        findViewById(R.id.btn_translation).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, TranslationActivity.class));
            }
        });

        findViewById(R.id.btn_diagnostics).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, DiagnosticsActivity.class));
            }
        });

        handleShortcut(getIntent());

        updater = new Updater(this);
        Button btnUpdate = findViewById(R.id.btn_update);
        try {
            String v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            btnUpdate.setText(getString(R.string.check_update) + ", versão instalada " + v);
        } catch (Exception ignored) {
        }
        btnUpdate.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                updater.check();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        updater.onResume();
        String def = null;
        try {
            def = Settings.Secure.getString(getContentResolver(), "tts_default_synth");
        } catch (Exception ignored) {
        }
        if (getPackageName().equals(def)) {
            btnSystem.setText("Motor do sistema: Gerenciador de TTS G ativo. Toque para abrir as configurações");
        } else {
            btnSystem.setText("Usar como motor de TTS do sistema, para o dicionário e os emojis valerem no leitor de tela");
        }
    }

    @Override
    protected void onDestroy() {
        voice.shutdown();
        super.onDestroy();
    }

    /** Atalho "Dicionário" do ícone: abre a tela principal já com o menu do dicionário. */
    private void handleShortcut(Intent intent) {
        if (intent != null && "br.com.geofilho.ttsg.DICIONARIO".equals(intent.getAction())) {
            intent.setAction(Intent.ACTION_MAIN); // não reabre o menu ao girar a tela
            showDictionaryMenu();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleShortcut(intent);
    }

    /** Menu "Dicionário": Adicionar palavra ou Gerenciar dicionário. */
    private void showDictionaryMenu() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.dictionary)
                .setItems(new String[]{getString(R.string.add_word_short), getString(R.string.manage_dictionary)},
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                if (which == 0) {
                                    addWordDialog();
                                } else {
                                    startActivity(new Intent(MainActivity.this, DictionaryActivity.class));
                                }
                            }
                        })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    /** Janela com os campos "Palavra ou frase" e "Substituir por". */
    private void addWordDialog() {
        View form = getLayoutInflater().inflate(R.layout.dialog_entry, null);
        final EditText word = form.findViewById(R.id.edit_word);
        final EditText replacement = form.findViewById(R.id.edit_replacement);
        new AlertDialog.Builder(this)
                .setTitle(R.string.add_word_short)
                .setView(form)
                .setPositiveButton("Salvar", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        String w = word.getText().toString();
                        if (w.trim().isEmpty()) {
                            Toast.makeText(MainActivity.this, "A palavra não pode ficar vazia.", Toast.LENGTH_SHORT)
                                    .show();
                            return;
                        }
                        Dictionary.get(MainActivity.this).put(w, replacement.getText().toString());
                        Toast.makeText(MainActivity.this, "Salvo no dicionário: " + w.trim(), Toast.LENGTH_SHORT)
                                .show();
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
        word.requestFocus();
    }

    private void openTtsSettings() {
        try {
            startActivity(new Intent("com.android.settings.TTS_SETTINGS"));
        } catch (ActivityNotFoundException e) {
            try {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            } catch (ActivityNotFoundException e2) {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            }
        }
    }
}
