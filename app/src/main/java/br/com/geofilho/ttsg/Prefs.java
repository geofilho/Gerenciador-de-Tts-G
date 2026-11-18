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

    static boolean groupRepeats(SharedPreferences p) {
        return p.getBoolean("group_repeats", true);
    }
}
