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
import android.widget.TextView;
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

    private TextView txtDevice;
    private Button btnDeviceDownload;
    private Switch sw;

    private SharedPreferences prefs;
    private Button btnSource;
    private Button btnTarget;
    private Button btnClear;
    private Button btnEmail;
    private android.widget.TextView txtSystem;
    private Button btnSystemDownload;
    private TextToSpeech tts;
    private boolean ready;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_translation);
        prefs = Prefs.get(this);

        sw = findViewById(R.id.switch_translate);
        sw.setChecked(Prefs.translateOn(prefs));
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                prefs.edit().putBoolean("translate_on", checked).apply();
                Toggles.refresh(TranslationActivity.this);
                if (checked) {
                    Translator.warm(TranslationActivity.this); // conexão pronta para a primeira tradução
                    Translator.prepareDevice(TranslationActivity.this);
                }
            }
        });

        Switch online = findViewById(R.id.switch_online);
        online.setChecked(Prefs.translateOnline(prefs));
        online.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                prefs.edit().putBoolean("translate_online", checked).apply();
                if (checked) {
                    Translator.warm(TranslationActivity.this);
                } else {
                    Translator.prepareDevice(TranslationActivity.this); // sem internet, o tradutor no aparelho é essencial
                }
            }
        });

        btnEmail = findViewById(R.id.btn_email);
        showEmail();
        btnEmail.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                emailDialog();
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
        txtSystem = findViewById(R.id.txt_system);
        btnSystemDownload = findViewById(R.id.btn_system_download);
        btnSystemDownload.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                android.app.PendingIntent pi = android.os.Build.VERSION.SDK_INT >= 31
                        ? SystemTranslator.settingsIntent(TranslationActivity.this) : null;
                try {
                    if (pi == null) {
                        throw new Exception();
                    }
                    pi.send();
                } catch (Exception e) {
                    Toast.makeText(TranslationActivity.this, "Este aparelho não tem tela para baixar idiomas do tradutor.",
                            Toast.LENGTH_LONG).show();
                }
            }
        });
        txtDevice = findViewById(R.id.txt_device);
        btnDeviceDownload = findViewById(R.id.btn_device_download);
        btnDeviceDownload.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                btnDeviceDownload.setVisibility(View.GONE);
                txtDevice.setText("Tradutor no aparelho: baixando os idiomas, isso pode levar alguns minutos.");
                String src = Prefs.translateSource(prefs);
                DeviceTranslator.download(TranslationActivity.this, "auto".equals(src) ? null : src,
                        Prefs.translateTarget(prefs), false, new DeviceTranslator.Done() {
                            @Override
                            public void done(final boolean ok, final String error) {
                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        Toast.makeText(TranslationActivity.this, ok
                                                ? "Tradutor no aparelho pronto. Agora traduz sem internet."
                                                : "Não foi possível baixar: " + error + ".", Toast.LENGTH_LONG).show();
                                        checkDeviceTranslator();
                                    }
                                });
                            }
                        });
            }
        });
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
                        checkSystemTranslator();
                        if (Prefs.translateOn(prefs)) {
                            DeviceTranslator.resetAuto(); // idioma novo: baixa o pacote dele pelo Wi-Fi
                            Translator.prepareDevice(TranslationActivity.this);
                            txtDevice.postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    checkDeviceTranslator();
                                }
                            }, 800);
                        }
                        checkDeviceTranslator();
                        d.dismiss();
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void showEmail() {
        String e = Prefs.myMemoryEmail(prefs);
        btnEmail.setText("E-mail para o tradutor reserva: " + (e.isEmpty() ? "não informado" : e));
    }

    /** E-mail opcional que aumenta o limite diário do MyMemory. */
    private void emailDialog() {
        View form = getLayoutInflater().inflate(R.layout.dialog_email, null);
        final EditText email = form.findViewById(R.id.edit_email);
        email.setText(Prefs.myMemoryEmail(prefs));
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("E-mail para o tradutor reserva")
                .setView(form)
                .setPositiveButton("Salvar", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        String e = email.getText().toString().trim();
                        if (!e.isEmpty() && !android.util.Patterns.EMAIL_ADDRESS.matcher(e).matches()) {
                            Toast.makeText(TranslationActivity.this, "Esse e-mail não parece válido. Confira e tente de novo.",
                                    Toast.LENGTH_LONG).show();
                            return;
                        }
                        saveEmail(e);
                    }
                })
                .setNegativeButton("Cancelar", null);
        if (!Prefs.myMemoryEmail(prefs).isEmpty()) {
            b.setNeutralButton("Apagar e-mail", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int which) {
                    saveEmail("");
                }
            });
        }
        b.show();
        email.requestFocus();
    }

    private void saveEmail(String e) {
        prefs.edit().putString("mymemory_email", e).apply();
        Translator.setMyMemoryEmail(e);
        showEmail();
        Toast.makeText(this, e.isEmpty() ? "E-mail apagado." : "E-mail salvo.", Toast.LENGTH_SHORT).show();
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

    /** Mostra se o tradutor no aparelho (ML Kit) já tem os idiomas baixados. */
    private void checkDeviceTranslator() {
        final String src = Prefs.translateSource(prefs);
        final String dst = Prefs.translateTarget(prefs);
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String st = DeviceTranslator.status(TranslationActivity.this, src, dst);
                final boolean ready = DeviceTranslator.canTranslate(TranslationActivity.this, "en", dst)
                        && ("auto".equals(src) || DeviceTranslator.canTranslate(TranslationActivity.this, src, dst));
                final boolean busy = DeviceTranslator.isDownloading();
                final boolean supported = DeviceTranslator.code(dst) != null;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        txtDevice.setText("Tradutor no aparelho: " + st + ".");
                        btnDeviceDownload.setVisibility(!ready && !busy && supported ? View.VISIBLE : View.GONE);
                    }
                });
            }
        }).start();
    }

    /** Mostra se o tradutor do próprio Android existe neste aparelho (consulta em segundo plano). */
    private void checkSystemTranslator() {
        if (android.os.Build.VERSION.SDK_INT < 31) {
            txtSystem.setText("Tradutor do Android: não disponível, precisa do Android 12 ou mais novo.");
            return;
        }
        txtSystem.setText("Tradutor do Android: verificando…");
        final String dst = Prefs.translateTarget(prefs);
        new Thread(new Runnable() {
            @Override
            public void run() {
                SystemTranslator.refresh(TranslationActivity.this, true);
                final String st = SystemTranslator.status(TranslationActivity.this, dst);
                final boolean dl = SystemTranslator.canDownload() && SystemTranslator.settingsIntent(TranslationActivity.this) != null;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        txtSystem.setText("Tradutor do Android: " + st + ".");
                        btnSystemDownload.setVisibility(dl ? View.VISIBLE : View.GONE);
                    }
                });
            }
        }).start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (sw != null && sw.isChecked() != Prefs.translateOn(prefs)) {
            sw.setChecked(Prefs.translateOn(prefs)); // pode ter mudado pelo widget ou pela barra
        }
        checkSystemTranslator();
        checkDeviceTranslator();
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
                Translator.lastProvider = "";
                Translator.setMyMemoryEmail(Prefs.myMemoryEmail(prefs));
                final String out = Translator.translate(text, src, dst, true, null);
                final String err = Translator.lastError;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (!err.isEmpty()) {
                            Toast.makeText(TranslationActivity.this, "Não foi possível traduzir agora. Motivo: "
                                    + err + ". Lendo o texto original.", Toast.LENGTH_LONG).show();
                        } else if (!out.equals(text) && !Translator.lastProvider.isEmpty()) {
                            Toast.makeText(TranslationActivity.this, "Traduzido por: "
                                    + Translator.lastProvider + ".", Toast.LENGTH_SHORT).show();
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
