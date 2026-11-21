package br.com.geofilho.ttsg;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

/**
 * Widgets da tela inicial.
 * Dicionário: abre o menu do dicionário numa tarefa própria (ao sair, volta para a tela inicial).
 * Tradução e Saída de áudio: são chaves; cada toque liga ou desliga, com aviso falado.
 */
public abstract class QuickWidget extends AppWidgetProvider {

    static final String ACTION_TOGGLE = "br.com.geofilho.ttsg.widget.ALTERNAR";

    abstract int label();

    abstract int requestCode();

    /** Tela que o widget abre; null quando o widget é uma chave. */
    Class<?> target() {
        return null;
    }

    /** Estado da chave; só usado quando target() é null. */
    boolean isOn(Context c) {
        return false;
    }

    void toggle(Context c) {
    }

    @Override
    public void onReceive(Context c, Intent intent) {
        if (ACTION_TOGGLE.equals(intent.getAction()) && target() == null) {
            toggle(c); // fala o novo estado e atualiza todos os widgets e botões
            return;
        }
        super.onReceive(c, intent);
    }

    @Override
    public void onUpdate(Context c, AppWidgetManager manager, int[] ids) {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
                | (android.os.Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent pi;
        String text = c.getString(label());
        String description = text;
        if (target() != null) {
            Intent open = new Intent(c, target());
            open.setAction(Intent.ACTION_VIEW);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            pi = PendingIntent.getActivity(c, requestCode(), open, flags);
        } else {
            Intent t = new Intent(c, getClass());
            t.setAction(ACTION_TOGGLE);
            t.setComponent(new ComponentName(c, getClass()));
            pi = PendingIntent.getBroadcast(c, requestCode(), t, flags);
            boolean on = isOn(c);
            text = text + ": " + Toggles.state(on);
            description = text + ". Toque para " + (on ? "desativar" : "ativar") + ".";
        }
        for (int id : ids) {
            RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget);
            v.setTextViewText(R.id.widget_label, text);
            v.setContentDescription(R.id.widget_root, description);
            v.setOnClickPendingIntent(R.id.widget_root, pi);
            manager.updateAppWidget(id, v);
        }
    }

    /** Widget "Dicionário". */
    public static class Dictionary extends QuickWidget {
        @Override
        int label() {
            return R.string.dictionary;
        }

        @Override
        Class<?> target() {
            return DictionaryQuickActivity.class;
        }

        @Override
        int requestCode() {
            return 1;
        }
    }

    /** Widget "Saída de áudio": chave de liga e desliga. */
    public static class Audio extends QuickWidget {
        @Override
        int label() {
            return R.string.shortcut_audio_short;
        }

        @Override
        boolean isOn(Context c) {
            return Toggles.audioOn(c);
        }

        @Override
        void toggle(Context c) {
            Toggles.toggleAudio(c);
        }

        @Override
        int requestCode() {
            return 2;
        }
    }

    /** Widget "Tradução": chave de liga e desliga. */
    public static class Translation extends QuickWidget {
        @Override
        int label() {
            return R.string.shortcut_translation_short;
        }

        @Override
        boolean isOn(Context c) {
            return Toggles.translationOn(c);
        }

        @Override
        void toggle(Context c) {
            Toggles.toggleTranslation(c);
        }

        @Override
        int requestCode() {
            return 3;
        }
    }
}
