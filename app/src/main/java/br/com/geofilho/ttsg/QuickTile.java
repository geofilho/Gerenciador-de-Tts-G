package br.com.geofilho.ttsg;

import android.annotation.TargetApi;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * Botões das configurações rápidas (barra que se abre puxando a tela de cima).
 * Dicionário: abre o menu do dicionário numa tarefa própria.
 * Tradução e Saída de áudio: são chaves; cada toque liga ou desliga, com aviso falado.
 */
@TargetApi(24)
public abstract class QuickTile extends TileService {

    abstract int requestCode();

    /** Tela que o botão abre; null quando o botão é uma chave. */
    Class<?> target() {
        return null;
    }

    boolean isOn() {
        return false;
    }

    void toggle() {
    }

    @Override
    public void onStartListening() {
        Tile t = getQsTile();
        if (t == null) {
            return;
        }
        if (target() == null) {
            boolean on = isOn();
            t.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
            if (Build.VERSION.SDK_INT >= 29) {
                t.setSubtitle(Toggles.state(on)); // estado escrito, não só pela cor
            }
            if (Build.VERSION.SDK_INT >= 30) {
                t.setStateDescription(Toggles.state(on));
            }
        } else {
            t.setState(Tile.STATE_INACTIVE); // é um botão que abre, não uma chave
        }
        t.updateTile();
    }

    @Override
    public void onClick() {
        if (target() == null) {
            toggle(); // funciona também com a tela bloqueada
            onStartListening();
            return;
        }
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

    /** Configuração rápida "Saída de áudio": chave. */
    public static class Audio extends QuickTile {
        @Override
        boolean isOn() {
            return Toggles.audioOn(this);
        }

        @Override
        void toggle() {
            Toggles.toggleAudio(this);
        }

        @Override
        int requestCode() {
            return 12;
        }
    }

    /** Configuração rápida "Tradução": chave. */
    public static class Translation extends QuickTile {
        @Override
        boolean isOn() {
            return Toggles.translationOn(this);
        }

        @Override
        void toggle() {
            Toggles.toggleTranslation(this);
        }

        @Override
        int requestCode() {
            return 13;
        }
    }
}
