package br.com.geofilho.ttsg;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

/** Tela única: voz principal, teste, ajustes, dicionário e atalhos. */
public class MainActivity extends Activity {

    private VoiceSection voice;
    private View addBox;
    private EditText editWord;
    private EditText editReplacement;
    private Button btnSystem;
    private Updater updater;
    private Button btnNumbers;

    /** Na mesma ordem das constantes NUMBERS_* do TextProcessor. */
    private static final String[] NUMBER_MODES = {"Número inteiro", "Um a um", "Dois a dois"};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        final EditText editTest = findViewById(R.id.edit_test);
        voice = new VoiceSection(this, Prefs.MAIN, true, false, new VoiceSection.TextProvider() {
            @Override
            public String text() {
                // O teste já passa pelo dicionário e pelas regras de símbolos repetidos.
                String t = Dictionary.get(MainActivity.this).apply(editTest.getText().toString());
                t = TextProcessor.numbers(t, Prefs.numberMode(Prefs.get(MainActivity.this)));
                return TextProcessor.join(TextProcessor.split(t, Prefs.groupRepeats(Prefs.get(MainActivity.this))));
            }
        });

        addBox = findViewById(R.id.add_box);
        editWord = findViewById(R.id.edit_word);
        editReplacement = findViewById(R.id.edit_replacement);

        findViewById(R.id.btn_add_word).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                addBox.setVisibility(View.VISIBLE);
                editWord.requestFocus();
                editWord.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_FOCUSED);
            }
        });
        findViewById(R.id.btn_save_word).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveWord();
            }
        });
        findViewById(R.id.btn_cancel_word).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeAddBox();
            }
        });
        findViewById(R.id.btn_manage_dictionary).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, DictionaryActivity.class));
            }
        });
        findViewById(R.id.btn_emoji).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, EmojiActivity.class));
            }
        });
        btnSystem = findViewById(R.id.btn_system);
        btnSystem.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openTtsSettings();
            }
        });
        btnNumbers = findViewById(R.id.btn_numbers);
        showNumberMode();
        btnNumbers.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chooseNumberMode();
            }
        });

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

    private void showNumberMode() {
        int mode = Prefs.numberMode(Prefs.get(this));
        if (mode < 0 || mode >= NUMBER_MODES.length) {
            mode = 0;
        }
        btnNumbers.setText("Leitura de números: " + NUMBER_MODES[mode]);
    }

    private void chooseNumberMode() {
        new AlertDialog.Builder(this)
                .setTitle("Leitura de números")
                .setSingleChoiceItems(NUMBER_MODES, Prefs.numberMode(Prefs.get(this)),
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                Prefs.get(MainActivity.this).edit().putInt("number_mode", which).apply();
                                showNumberMode();
                                d.dismiss();
                            }
                        })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void saveWord() {
        String word = editWord.getText().toString();
        if (word.trim().isEmpty()) {
            editWord.setError("Digite a palavra ou frase");
            editWord.requestFocus();
            return;
        }
        Dictionary.get(this).put(word, editReplacement.getText().toString());
        Toast.makeText(this, "Salvo no dicionário: " + word.trim(), Toast.LENGTH_SHORT).show();
        closeAddBox();
    }

    private void closeAddBox() {
        editWord.setText("");
        editReplacement.setText("");
        editWord.setError(null);
        addBox.setVisibility(View.GONE);
        View btn = findViewById(R.id.btn_add_word);
        btn.requestFocus();
        btn.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_FOCUSED);
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
