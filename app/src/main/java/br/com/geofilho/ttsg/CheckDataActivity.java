package br.com.geofilho.ttsg;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;

import java.util.ArrayList;
import java.util.Locale;

/** Diz ao sistema que os dados de voz estão prontos (o app usa os de outros motores). */
public class CheckDataActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ArrayList<String> available = new ArrayList<>();
        Locale l = Locale.forLanguageTag(Prefs.language(Prefs.get(this)));
        try {
            available.add(l.getISO3Language() + "-" + l.getISO3Country());
        } catch (Exception e) {
            available.add("por-BRA");
        }
        Intent data = new Intent();
        data.putStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES, available);
        data.putStringArrayListExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES, new ArrayList<String>());
        setResult(TextToSpeech.Engine.CHECK_VOICE_DATA_PASS, data);
        finish();
    }
}
