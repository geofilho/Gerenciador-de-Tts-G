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
    private Button btnFocus;
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
        removeDeviceTranslatorFiles();

        btnFocus = findViewById(R.id.btn_focus_memory);
        btnFocus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                explainFocusMemory();
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
        btnFocus.setText(focusMemoryOn()
                ? "Lembrar o foco ao voltar, no Telegram: ativado. Toque para desativar ou saber mais"
                : "Lembrar o foco ao voltar, no Telegram: desativado. Toque para ativar");
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

    /** O serviço "lembrar o foco ao voltar" está ligado nas configurações de acessibilidade? */
    private boolean focusMemoryOn() {
        try {
            String on = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return on != null && on.contains(getPackageName() + "/") && on.contains("FocusMemoryService");
        } catch (Exception e) {
            return false;
        }
    }

    private void explainFocusMemory() {
        new AlertDialog.Builder(this)
                .setTitle("Lembrar o foco ao voltar (teste)")
                .setMessage("No Telegram, quando você entra num grupo e volta para a lista, o foco do leitor de tela "
                        + "volta para o grupo onde você estava.\n\n"
                        + "Para ativar: toque em Abrir configurações, depois em Aplicativos instalados, e ative "
                        + "Gerenciador de TTS G: lembrar o foco ao voltar.\n\n"
                        + "Se a opção estiver bloqueada (configuração restrita): abra Informações do app do "
                        + "Gerenciador de TTS G, toque no menu de três pontos, no canto de cima, e em Permitir "
                        + "configurações restritas. Depois volte e ative.\n\n"
                        + "Funciona só no Telegram e não deixa a fala mais lenta. Não lê nem guarda suas mensagens: "
                        + "guarda só o nome do grupo em que você tocou, até voltar para a lista.")
                .setPositiveButton("Abrir configurações", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        try {
                            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                        } catch (ActivityNotFoundException e) {
                            startActivity(new Intent(Settings.ACTION_SETTINGS));
                        }
                    }
                })
                .setNeutralButton("Informações do app", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        try {
                            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    android.net.Uri.parse("package:" + getPackageName())));
                        } catch (ActivityNotFoundException ignored) {
                        }
                    }
                })
                .setNegativeButton("Fechar", null)
                .show();
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
}
