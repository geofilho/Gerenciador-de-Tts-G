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
                if (Prefs.dropEmojiWord(p)) {
                    t = TextProcessor.dropEmojiWord(t);
                }
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

        findViewById(R.id.btn_backup).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                BackupUi.menu(MainActivity.this);
            }
        });

        findViewById(R.id.btn_about).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, AboutActivity.class));
            }
        });

        handleShortcut(getIntent());
        removeDeviceTranslatorFiles();

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

    /**
     * A versão de teste 1.59 tinha o tradutor no aparelho (ML Kit), que baixa pacotes de idioma
     * de cerca de 30 MB cada. Esta versão leve não usa esses arquivos: apaga, para liberar espaço.
     */
    private void removeDeviceTranslatorFiles() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                java.io.File[] dirs = {getFilesDir(), getCacheDir(),
                        android.os.Build.VERSION.SDK_INT >= 21 ? getNoBackupFilesDir() : null};
                for (java.io.File dir : dirs) {
                    java.io.File[] list = dir != null ? dir.listFiles() : null;
                    if (list == null) {
                        continue;
                    }
                    for (java.io.File f : list) {
                        String n = f.getName().toLowerCase(java.util.Locale.ROOT);
                        if (n.startsWith("com.google.mlkit") || n.startsWith("mlkit")) {
                            deleteAll(f);
                        }
                    }
                }
            }
        }, "limpar-tradutor").start();
    }

    private static void deleteAll(java.io.File f) {
        java.io.File[] kids = f.listFiles();
        if (kids != null) {
            for (java.io.File k : kids) {
                deleteAll(k);
            }
        }
        f.delete();
    }

    /** Menu "Dicionário": Adicionar palavra ou Gerenciar dicionário. */
    private void showDictionaryMenu() {
        DictionaryDialogs.showMenu(this, null);
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

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        BackupUi.onResult(this, code, result, data);
    }
}
