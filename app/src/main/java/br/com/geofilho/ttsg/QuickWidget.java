package br.com.geofilho.ttsg;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

/**
 * Widgets da tela inicial: um botão para cada função (Dicionário, Saída de áudio
 * e Tradução). Abrem a função numa tarefa própria: ao sair, volta direto para a
 * tela inicial do aparelho, sem passar pela tela do aplicativo.
 */
public abstract class QuickWidget extends AppWidgetProvider {

    abstract int label();

    abstract Class<?> target();

    abstract int requestCode();

    @Override
    public void onUpdate(Context c, AppWidgetManager manager, int[] ids) {
        Intent open = new Intent(c, target());
        open.setAction(Intent.ACTION_VIEW);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
                | (android.os.Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent pi = PendingIntent.getActivity(c, requestCode(), open, flags);
        String text = c.getString(label());
        for (int id : ids) {
            RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget);
            v.setTextViewText(R.id.widget_label, text);
            v.setContentDescription(R.id.widget_root, text);
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

    /** Widget "Saída de áudio do leitor de tela". */
    public static class Audio extends QuickWidget {
        @Override
        int label() {
            return R.string.title_audio;
        }

        @Override
        Class<?> target() {
            return AudioActivity.class;
        }

        @Override
        int requestCode() {
            return 2;
        }
    }

    /** Widget "Tradução de voz TTS". */
    public static class Translation extends QuickWidget {
        @Override
        int label() {
            return R.string.title_translation;
        }

        @Override
        Class<?> target() {
            return TranslationActivity.class;
        }

        @Override
        int requestCode() {
            return 3;
        }
    }
}
