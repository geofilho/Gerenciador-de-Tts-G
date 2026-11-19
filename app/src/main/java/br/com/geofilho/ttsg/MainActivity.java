package br.com.geofilho.ttsg;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.Toast;

import java.util.List;

/** Tela única: voz principal, teste, ajustes, dicionário e atalhos. */
public class MainActivity extends Activity {

    private VoiceSection voice;
    private View addBox;
    private EditText editWord;
    private EditText editReplacement;
    private Button btnSystem;
    private Updater updater;
    private Switch keepSwitch;
    private SharedPreferences.OnSharedPreferenceChangeListener keepListener;

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
        findViewById(R.id.btn_numbers).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, NumbersActivity.class));
            }
        });

        setupKeepOnPhone();

        findViewById(R.id.btn_diagnostics).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, DiagnosticsActivity.class));
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
        if (keepSwitch != null) {
            keepSwitch.setChecked(Prefs.keepOnPhone(Prefs.get(this)));
        }
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
        if (keepListener != null) {
            Prefs.get(this).unregisterOnSharedPreferenceChangeListener(keepListener);
        }
        voice.shutdown();
        super.onDestroy();
    }

    /**
     * Chave "Manter o áudio do leitor de tela no telefone": um toque liga ou desliga.
     * Só liga com Bluetooth de áudio conectado e desliga sozinha quando ele desconecta.
     */
    private void setupKeepOnPhone() {
        final SharedPreferences prefs = Prefs.get(this);
        keepSwitch = findViewById(R.id.switch_keep_phone);
        if (Build.VERSION.SDK_INT < 23) {
            keepSwitch.setVisibility(View.GONE);
            return;
        }
        keepSwitch.setChecked(Prefs.keepOnPhone(prefs));
        keepSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                if (checked == Prefs.keepOnPhone(prefs)) {
                    return;
                }
                if (checked && PhonePlayer.bluetoothOutputName(MainActivity.this) == null) {
                    b.setChecked(false);
                    Toast.makeText(MainActivity.this, "Conecte um dispositivo Bluetooth de áudio primeiro.",
                            Toast.LENGTH_LONG).show();
                    return;
                }
                prefs.edit().putBoolean("keep_on_phone", checked).apply();
            }
        });
        // Se o motor desligar a chave (Bluetooth desconectado), a tela acompanha.
        keepListener = new SharedPreferences.OnSharedPreferenceChangeListener() {
            @Override
            public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
                if ("keep_on_phone".equals(key) && keepSwitch.isChecked() != Prefs.keepOnPhone(sp)) {
                    keepSwitch.setChecked(Prefs.keepOnPhone(sp));
                }
            }
        };
        prefs.registerOnSharedPreferenceChangeListener(keepListener);
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
