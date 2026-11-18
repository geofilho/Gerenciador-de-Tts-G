package br.com.geofilho.ttsg;

import android.content.Context;
import android.content.SharedPreferences;

/** Configurações salvas. Prefixo "main" = voz principal, "emoji" = voz dos emojis. */
final class Prefs {
    static final String MAIN = "main";
    static final String EMOJI = "emoji";

    private Prefs() {
    }

    static SharedPreferences get(Context c) {
        return c.getApplicationContext().getSharedPreferences("config", Context.MODE_PRIVATE);
    }

    static String engine(SharedPreferences p, String who) {
        return p.getString(who + "_engine", "");
    }

    static String voice(SharedPreferences p, String who) {
        return p.getString(who + "_voice", "");
    }

    static String language(SharedPreferences p) {
        return p.getString(MAIN + "_lang", "pt-BR");
    }

    static int rate(SharedPreferences p, String who) {
        return p.getInt(who + "_rate", 50);
    }

    static int pitch(SharedPreferences p, String who) {
        return p.getInt(who + "_pitch", 50);
    }

    static int volume(SharedPreferences p, String who) {
        return p.getInt(who + "_volume", 100);
    }

    /** Velocidade dos números; se nunca foi ajustada, segue a da voz principal. */
    static int numberRate(SharedPreferences p) {
        return p.getInt("number_rate", rate(p, MAIN));
    }

    /** Números só viram trechos separados quando a velocidade deles é diferente. */
    static boolean separateNumbers(SharedPreferences p) {
        return numberRate(p) != rate(p, MAIN);
    }

    static boolean keepOnPhone(SharedPreferences p) {
        return p.getBoolean("keep_on_phone", false);
    }

    static int punctuationMode(SharedPreferences p) {
        return p.getInt("punctuation_mode", TextProcessor.PUNCT_NONE);
    }

    static int numberMode(SharedPreferences p) {
        return p.getInt("number_mode", TextProcessor.NUMBERS_WHOLE);
    }

    static boolean groupRepeats(SharedPreferences p) {
        return p.getBoolean("group_repeats", true);
    }
}
