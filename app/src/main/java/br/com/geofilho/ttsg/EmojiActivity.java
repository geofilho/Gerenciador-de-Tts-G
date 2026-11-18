package br.com.geofilho.ttsg;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.Switch;

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
    }

    @Override
    protected void onDestroy() {
        voice.shutdown();
        super.onDestroy();
    }
}
