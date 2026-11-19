package br.com.geofilho.ttsg;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/** Página "Saída de áudio do leitor de tela". */
public class AudioActivity extends Activity {

    private SharedPreferences prefs;
    private TextView status;
    private TextToSpeech tts;
    private boolean ready;
    private Object deviceCallback;
    private Switch keepSwitch;
    private SharedPreferences.OnSharedPreferenceChangeListener prefListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_audio);
        prefs = Prefs.get(this);
        status = findViewById(R.id.txt_status);

        final Switch sw = findViewById(R.id.switch_keep_phone);
        keepSwitch = sw;
        sw.setChecked(Prefs.keepOnPhone(prefs));
        // Se o motor desligar a opção (Bluetooth desconectado), a chave acompanha.
        prefListener = new SharedPreferences.OnSharedPreferenceChangeListener() {
            @Override
            public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
                if ("keep_on_phone".equals(key) && sw.isChecked() != Prefs.keepOnPhone(sp)) {
                    sw.setChecked(Prefs.keepOnPhone(sp));
                    updateStatus();
                }
            }
        };
        prefs.registerOnSharedPreferenceChangeListener(prefListener);
        if (Build.VERSION.SDK_INT < 23) {
            sw.setEnabled(false);
        }
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                if (checked && PhonePlayer.bluetoothOutputName(AudioActivity.this) == null) {
                    b.setChecked(false);
                    Toast.makeText(AudioActivity.this, "Conecte um fone, caixa de som ou som do carro Bluetooth "
                            + "antes de ativar esta opção.", Toast.LENGTH_LONG).show();
                    return;
                }
                if (checked == Prefs.keepOnPhone(prefs)) {
                    return;
                }
                prefs.edit().putBoolean("keep_on_phone", checked).apply();
                updateStatus();
            }
        });

        // O teste usa o próprio Gerenciador como motor, para ouvir por onde a fala sai.
        tts = new TextToSpeech(this, new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int s) {
                ready = s == TextToSpeech.SUCCESS;
            }
        }, getPackageName());
        findViewById(R.id.btn_test).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!ready) {
                    Toast.makeText(AudioActivity.this, "O motor ainda está carregando.", Toast.LENGTH_SHORT).show();
                    return;
                }
                tts.speak("Este é o áudio do leitor de tela.", TextToSpeech.QUEUE_FLUSH, null, "teste");
            }
        });

        findViewById(R.id.btn_help).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(AudioActivity.this)
                        .setTitle(R.string.audio_help_title)
                        .setMessage(R.string.audio_help_text)
                        .setPositiveButton("Fechar", null)
                        .show();
            }
        });

        updateStatus();
        if (Build.VERSION.SDK_INT >= 23) {
            watchDevices();
        }
    }

    @TargetApi(23)
    private void watchDevices() {
        AudioDeviceCallback cb = new AudioDeviceCallback() {
            @Override
            public void onAudioDevicesAdded(AudioDeviceInfo[] added) {
                updateStatus();
            }

            @Override
            public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) {
                updateStatus();
                keepSwitch.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (Prefs.keepOnPhone(prefs) && PhonePlayer.bluetoothOutputName(AudioActivity.this) == null) {
                            prefs.edit().putBoolean("keep_on_phone", false).apply();
                        }
                        updateStatus();
                    }
                }, 2000);
            }
        };
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am != null) {
            am.registerAudioDeviceCallback(cb, null);
            deviceCallback = cb;
        }
    }

    private void updateStatus() {
        if (Build.VERSION.SDK_INT < 23) {
            status.setText("Esta opção precisa do Android 6 ou mais novo.");
            return;
        }
        String def = null;
        try {
            def = android.provider.Settings.Secure.getString(getContentResolver(), "tts_default_synth");
        } catch (Exception ignored) {
        }
        if (!getPackageName().equals(def)) {
            status.setText("Atenção: o Gerenciador de TTS G não está escolhido como motor de TTS do sistema. "
                    + "Assim esta opção não funciona. Na tela principal, use o botão Usar como motor de TTS do "
                    + "sistema.");
            return;
        }
        String bt = PhonePlayer.bluetoothOutputName(this);
        boolean on = Prefs.keepOnPhone(prefs);
        if (bt == null) {
            status.setText("Nenhum dispositivo Bluetooth de áudio conectado. O áudio segue o caminho normal.");
        } else if (on) {
            status.setText("Bluetooth conectado: " + bt + ". O leitor de tela fala no telefone"
                    + (AccessibilityRouter.supported() ? ", inclusive os sons de navegação," : "")
                    + " e a mídia toca no Bluetooth.");
        } else {
            status.setText("Bluetooth conectado: " + bt + ". O leitor de tela e a mídia saem no Bluetooth.");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
    }

    @Override
    protected void onDestroy() {
        if (prefListener != null) {
            prefs.unregisterOnSharedPreferenceChangeListener(prefListener);
        }
        if (Build.VERSION.SDK_INT >= 23 && deviceCallback != null) {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (am != null) {
                am.unregisterAudioDeviceCallback((AudioDeviceCallback) deviceCallback);
            }
        }
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        super.onDestroy();
    }
}
