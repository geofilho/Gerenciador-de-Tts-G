package br.com.geofilho.ttsg;

import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.service.quicksettings.TileService;
import android.widget.Toast;

/**
 * Chaves de liga e desliga da Tradução e da Saída de áudio, usadas pelos widgets,
 * pelos botões das configurações rápidas e pelas ações do MacroDroid. Cada mudança
 * é falada (aviso) e atualiza widgets e botões, para o estado mostrado ser sempre o real.
 */
final class Toggles {

    private Toggles() {
    }

    static boolean translationOn(Context c) {
        return Prefs.translateOn(Prefs.get(c));
    }

    static boolean audioOn(Context c) {
        return Prefs.keepOnPhone(Prefs.get(c));
    }

    static void toggleTranslation(Context c) {
        setTranslation(c, !translationOn(c));
    }

    static void setTranslation(Context c, boolean on) {
        Prefs.get(c).edit().putBoolean("translate_on", on).apply();
        if (on) {
            Translator.warm(c);
            Translator.prepareDevice(c);
        }
        say(c, on ? "Tradução ativada." : "Tradução desativada.");
        refresh(c);
    }

    static void toggleAudio(Context c) {
        setAudio(c, !audioOn(c));
    }

    static void setAudio(Context c, boolean on) {
        SharedPreferences p = Prefs.get(c);
        if (on) {
            if (Build.VERSION.SDK_INT < 23) {
                say(c, "A saída de áudio precisa do Android 6 ou mais novo.");
                refresh(c);
                return;
            }
            if (PhonePlayer.bluetoothOutputName(c) == null) {
                say(c, "Saída de áudio não ativada: nenhum dispositivo Bluetooth de áudio conectado.");
                refresh(c);
                return;
            }
        }
        p.edit().putBoolean("keep_on_phone", on).apply();
        say(c, on ? "Saída de áudio ativada: o leitor de tela fala no telefone."
                : "Saída de áudio desativada.");
        refresh(c);
    }

    /** Texto de estado, falado e escrito (não depende de cor). */
    static String state(boolean on) {
        return on ? "ativada" : "desativada";
    }

    /** Atualiza os widgets e os botões das configurações rápidas com o estado atual. */
    static void refresh(Context c) {
        Context app = c.getApplicationContext() != null ? c.getApplicationContext() : c;
        updateWidgets(app, QuickWidget.Translation.class);
        updateWidgets(app, QuickWidget.Audio.class);
        if (Build.VERSION.SDK_INT >= 24) {
            try {
                TileService.requestListeningState(app, new ComponentName(app, QuickTile.Translation.class));
                TileService.requestListeningState(app, new ComponentName(app, QuickTile.Audio.class));
            } catch (Exception ignored) {
                // Botão não adicionado à barra: nada a atualizar.
            }
        }
    }

    private static void updateWidgets(Context c, Class<?> provider) {
        try {
            AppWidgetManager m = AppWidgetManager.getInstance(c);
            int[] ids = m.getAppWidgetIds(new ComponentName(c, provider));
            if (ids == null || ids.length == 0) {
                return;
            }
            Intent i = new Intent(c, provider);
            i.setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE);
            i.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids);
            c.sendBroadcast(i);
        } catch (Exception ignored) {
        }
    }

    static void say(Context c, String msg) {
        Toast.makeText(c.getApplicationContext(), msg, Toast.LENGTH_LONG).show();
    }
}
