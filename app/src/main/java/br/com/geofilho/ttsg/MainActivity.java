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
        updateFocusButton();
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

    /** Lista de ações de "Lembrar o foco ao voltar". */
    private void explainFocusMemory() {
        int n = Prefs.focusApps(Prefs.get(this)).size();
        String[] items = {
                "Escolher os aplicativos (" + n + (n == 1 ? " escolhido)" : " escolhidos)"),
                (focusMemoryOn() ? "Desativar" : "Ativar") + " nas configurações de acessibilidade",
                "Liberar configuração restrita, se a ativação estiver bloqueada",
                "Como funciona"
        };
        new AlertDialog.Builder(this)
                .setTitle("Lembrar o foco ao voltar (teste)")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) {
                            chooseFocusApps();
                        } else if (which == 1) {
                            try {
                                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                                Toast.makeText(MainActivity.this, "Em Aplicativos instalados, toque em Gerenciador de TTS G: "
                                        + "lembrar o foco ao voltar.", Toast.LENGTH_LONG).show();
                            } catch (ActivityNotFoundException e) {
                                startActivity(new Intent(Settings.ACTION_SETTINGS));
                            }
                        } else if (which == 2) {
                            try {
                                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        android.net.Uri.parse("package:" + getPackageName())));
                                Toast.makeText(MainActivity.this, "Toque no menu de três pontos, no canto de cima, e em "
                                        + "Permitir configurações restritas.", Toast.LENGTH_LONG).show();
                            } catch (ActivityNotFoundException ignored) {
                            }
                        } else {
                            new AlertDialog.Builder(MainActivity.this)
                                    .setTitle("Como funciona")
                                    .setMessage("Nos aplicativos que você escolher, quando você entra num item de uma "
                                            + "lista, como um grupo do Telegram, e volta, o foco do leitor de tela volta "
                                            + "para o item onde você estava.\n\n"
                                            + "Os outros aplicativos, como os de banco, não são observados. Mesmo assim, "
                                            + "alguns apps de banco desconfiam de qualquer serviço de acessibilidade "
                                            + "ligado; se algum reclamar, desative esta opção.\n\n"
                                            + "Não deixa a fala mais lenta. Não lê nem guarda suas mensagens: guarda só o "
                                            + "nome do item em que você tocou, até voltar para a lista.")
                                    .setPositiveButton("Fechar", null)
                                    .show();
                        }
                    }
                })
                .setNegativeButton("Fechar", null)
                .show();
    }

    /** Escolha dos aplicativos: lista dos instalados, com os escolhidos marcados primeiro. */
    private void chooseFocusApps() {
        final android.content.pm.PackageManager pm = getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        final java.util.Set<String> chosen = Prefs.focusApps(Prefs.get(this));
        final java.util.List<String[]> apps = new java.util.ArrayList<>(); // {nome, pacote}
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (android.content.pm.ResolveInfo r : pm.queryIntentActivities(launcher, 0)) {
            String pkg = r.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || !seen.add(pkg)) {
                continue;
            }
            apps.add(new String[]{String.valueOf(r.loadLabel(pm)), pkg});
        }
        java.util.Collections.sort(apps, new java.util.Comparator<String[]>() {
            @Override
            public int compare(String[] a, String[] b) {
                boolean ca = chosen.contains(a[1]);
                boolean cb = chosen.contains(b[1]);
                if (ca != cb) {
                    return ca ? -1 : 1; // escolhidos primeiro
                }
                return a[0].compareToIgnoreCase(b[0]);
            }
        });
        final String[] names = new String[apps.size()];
        final boolean[] checked = new boolean[apps.size()];
        for (int i = 0; i < names.length; i++) {
            names[i] = apps.get(i)[0];
            checked[i] = chosen.contains(apps.get(i)[1]);
        }
        new AlertDialog.Builder(this)
                .setTitle("Aplicativos em que o foco é lembrado")
                .setMultiChoiceItems(names, checked, new DialogInterface.OnMultiChoiceClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which, boolean isChecked) {
                        checked[which] = isChecked;
                    }
                })
                .setPositiveButton("Salvar", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        java.util.List<String> out = new java.util.ArrayList<>();
                        for (int i = 0; i < checked.length; i++) {
                            if (checked[i]) {
                                out.add(apps.get(i)[1]);
                            }
                        }
                        Prefs.setFocusApps(Prefs.get(MainActivity.this), out);
                        Toast.makeText(MainActivity.this, out.isEmpty() ? "Nenhum aplicativo escolhido."
                                : out.size() + (out.size() == 1 ? " aplicativo escolhido." : " aplicativos escolhidos."),
                                Toast.LENGTH_SHORT).show();
                        updateFocusButton();
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void updateFocusButton() {
        int n = Prefs.focusApps(Prefs.get(this)).size();
        btnFocus.setText("Lembrar o foco ao voltar: " + (focusMemoryOn() ? "ativado" : "desativado") + ", em " + n
                + (n == 1 ? " aplicativo" : " aplicativos") + ". Toque para escolher os aplicativos ou ativar");
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
