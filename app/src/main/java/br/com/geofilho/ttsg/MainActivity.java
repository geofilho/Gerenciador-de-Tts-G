package br.com.geofilho.ttsg;

import android.app.Activity;
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
    }

    @Override
    protected void onResume() {
        super.onResume();
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
