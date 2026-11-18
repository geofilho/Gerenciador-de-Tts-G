package br.com.geofilho.ttsg;

import android.annotation.TargetApi;
import android.app.Activity;
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_audio);
        prefs = Prefs.get(this);
        status = findViewById(R.id.txt_status);

        Switch sw = findViewById(R.id.switch_keep_phone);
        sw.setChecked(Prefs.keepOnPhone(prefs));
        if (Build.VERSION.SDK_INT < 23) {
            sw.setEnabled(false);
        }
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
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
        String bt = PhonePlayer.bluetoothOutputName(this);
        boolean on = Prefs.keepOnPhone(prefs);
        if (bt == null) {
            status.setText("Nenhum dispositivo Bluetooth de áudio conectado. O áudio segue o caminho normal.");
        } else if (on) {
            status.setText("Bluetooth conectado: " + bt + ". O leitor de tela fala no telefone e a mídia "
                    + "toca no Bluetooth.");
        } else {
            status.setText("Bluetooth conectado: " + bt + ". O leitor de tela e a mídia saem no Bluetooth.");
        }
    }

    @Override
    protected void onDestroy() {
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
