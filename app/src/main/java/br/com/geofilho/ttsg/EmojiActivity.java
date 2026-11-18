package br.com.geofilho.ttsg;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.Switch;
import android.widget.Toast;

/**
 * Voz para emojis: motor, voz (no idioma escolhido na tela principal),
 * ouvir, velocidade, tonalidade, volume e a chave de agrupar repetidos.
 */
public class EmojiActivity extends Activity {

    private static final String SAMPLE = "😀 ❤️❤️❤️❤️❤️ 👍";

    private VoiceSection voice;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_emoji);
        findViewById(R.id.edit_test).setVisibility(View.GONE);

        final SharedPreferences prefs = Prefs.get(this);
        voice = new VoiceSection(this, Prefs.EMOJI, false, true, new VoiceSection.TextProvider() {
            @Override
            public String text() {
                return TextProcessor.join(TextProcessor.split(SAMPLE, Prefs.groupRepeats(prefs)));
            }
        });

        Switch sw = findViewById(R.id.switch_group);
        sw.setChecked(Prefs.groupRepeats(prefs));
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                prefs.edit().putBoolean("group_repeats", checked).apply();
            }
        });

        findViewById(R.id.btn_diagnostics).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showDiagnostics();
            }
        });
    }

    private void showDiagnostics() {
        final String report = Diagnostics.report();
        new AlertDialog.Builder(this)
                .setTitle("Diagnóstico")
                .setMessage(report)
                .setPositiveButton("Copiar", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                        if (cm != null) {
                            cm.setPrimaryClip(ClipData.newPlainText("Diagnóstico", report));
                            Toast.makeText(EmojiActivity.this, "Diagnóstico copiado.", Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton("Fechar", null)
                .show();
    }

    @Override
    protected void onDestroy() {
        voice.shutdown();
        super.onDestroy();
    }
}
