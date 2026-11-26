package br.com.geofilho.ttsg;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.UserManager;

import java.io.File;

/** Configurações salvas. Prefixo "main" = voz principal, "emoji" = voz dos emojis. */
final class Prefs {
    static final String MAIN = "main";
    static final String EMOJI = "emoji";

    private Prefs() {
    }

    static SharedPreferences get(Context c) {
        return storage(c).getSharedPreferences("config", Context.MODE_PRIVATE);
    }

    private static volatile boolean migrated;

    /**
     * Onde ficam configurações, dicionário e arquivos temporários. No Android 7+
     * é o armazenamento protegido pelo aparelho, que funciona até antes de
     * digitar a senha depois de religar (assim o motor já fala na tela de bloqueio).
     */
    static Context storage(Context c) {
        Context cached = storageCtx;
        if (cached != null) {
            return cached; // criado uma vez: cada fala lê as configurações várias vezes
        }
        Context app = c.getApplicationContext() != null ? c.getApplicationContext() : c;
        if (Build.VERSION.SDK_INT < 24) {
            storageCtx = app;
            return app;
        }
        Context de = app.createDeviceProtectedStorageContext();
        if (!migrated) {
            migrate(app, de);
        }
        storageCtx = de;
        return de;
    }

    private static volatile Context storageCtx;

    /** Leva as configurações e o dicionário das versões antigas para o armazenamento novo (uma vez). */
    @TargetApi(24)
    private static synchronized void migrate(Context ce, Context de) {
        if (migrated) {
            return;
        }
        UserManager um = (UserManager) ce.getSystemService(Context.USER_SERVICE);
        if (um == null || !um.isUserUnlocked()) {
            return; // ainda bloqueado: tenta de novo depois da senha
        }
        SharedPreferences flag = de.getSharedPreferences("migracao", Context.MODE_PRIVATE);
        if (!flag.getBoolean("feito", false)) {
            try {
                de.moveSharedPreferencesFrom(ce, "config");
            } catch (Exception ignored) {
            }
            try {
                File old = new File(ce.getFilesDir(), "dicionario.json");
                File neu = new File(de.getFilesDir(), "dicionario.json");
                if (old.exists() && !neu.exists() && !old.renameTo(neu)) {
                    copy(old, neu);
                    old.delete();
                }
            } catch (Exception ignored) {
            }
            flag.edit().putBoolean("feito", true).commit();
        }
        migrated = true;
    }

    private static void copy(File from, File to) throws java.io.IOException {
        java.io.InputStream in = new java.io.FileInputStream(from);
        java.io.OutputStream out = new java.io.FileOutputStream(to);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        in.close();
        out.close();
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

    /** Registro das falas no diagnóstico (desligado por padrão, para não pesar). */
    static boolean diagnosticsOn(SharedPreferences p) {
        return p.getBoolean("diagnostics_on", false);
    }

    /** Ler automaticamente as sugestões do Gboard (precisa do serviço de acessibilidade ligado). */
    static boolean gboardOn(SharedPreferences p) {
        return p.getBoolean("gboard_on", false);
    }

    static boolean translateOn(SharedPreferences p) {
        return p.getBoolean("translate_on", false);
    }

    /** Usar também os tradutores pela internet (Google, Lingva, MyMemory). Desligado: só sem internet. */
    static boolean translateOnline(SharedPreferences p) {
        return p.getBoolean("translate_online", true);
    }

    /** Idioma de origem da tradução: "auto" detecta sozinho. */
    static String translateSource(SharedPreferences p) {
        return p.getString("translate_src", "auto");
    }

    /** Idioma de destino da tradução: padrão é o idioma do aparelho. */
    static String translateTarget(SharedPreferences p) {
        return p.getString("translate_dst", Translator.deviceLanguage());
    }

    /** E-mail para aumentar o limite diário do tradutor reserva MyMemory (vazio = sem e-mail). */
    static String myMemoryEmail(SharedPreferences p) {
        return p.getString("mymemory_email", "");
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

    /** Ignorar a palavra "Emoji" antes dos emojis ("Emoji 😔" vira só o emoji). */
    static boolean dropEmojiWord(SharedPreferences p) {
        return p.getBoolean("drop_emoji_word", true);
    }

    static boolean groupRepeats(SharedPreferences p) {
        return p.getBoolean("group_repeats", true);
    }
}
