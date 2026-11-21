package br.com.geofilho.ttsg;

import android.annotation.TargetApi;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * Botões das configurações rápidas (barra que se abre puxando a tela de cima):
 * Dicionário, Saída de áudio e Tradução. Como os widgets, abrem a função numa
 * tarefa própria; ao sair, volta para onde a pessoa estava.
 */
@TargetApi(24)
public abstract class QuickTile extends TileService {

    abstract Class<?> target();

    abstract int requestCode();

    @Override
    public void onStartListening() {
        Tile t = getQsTile();
        if (t != null) {
            t.setState(Tile.STATE_INACTIVE); // é um botão que abre, não uma chave
            t.updateTile();
        }
    }

    @Override
    public void onClick() {
        if (isLocked()) {
            // Com a tela bloqueada, pede o desbloqueio e depois abre.
            unlockAndRun(new Runnable() {
                @Override
                public void run() {
                    open();
                }
            });
        } else {
            open();
        }
    }

    private void open() {
        Intent i = new Intent(this, target());
        i.setAction(Intent.ACTION_VIEW);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        if (Build.VERSION.SDK_INT >= 34) {
            // No Android 14+, o sistema só aceita abrir telas pelas configurações rápidas assim.
            startActivityAndCollapse(PendingIntent.getActivity(this, requestCode(), i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        } else {
            startActivityAndCollapseLegacy(i);
        }
    }

    @SuppressWarnings("deprecation")
    private void startActivityAndCollapseLegacy(Intent i) {
        startActivityAndCollapse(i);
    }

    /** Configuração rápida "Dicionário". */
    public static class Dictionary extends QuickTile {
        @Override
        Class<?> target() {
            return DictionaryQuickActivity.class;
        }

        @Override
        int requestCode() {
            return 11;
        }
    }

    /** Configuração rápida "Saída de áudio do leitor de tela". */
    public static class Audio extends QuickTile {
        @Override
        Class<?> target() {
            return AudioActivity.class;
        }

        @Override
        int requestCode() {
            return 12;
        }
    }

    /** Configuração rápida "Tradução de voz TTS". */
    public static class Translation extends QuickTile {
        @Override
        Class<?> target() {
            return TranslationActivity.class;
        }

        @Override
        int requestCode() {
            return 13;
        }
    }
}
