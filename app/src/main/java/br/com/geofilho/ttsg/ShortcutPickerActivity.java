package br.com.geofilho.ttsg;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;

/**
 * Lista de ações que outros apps (MacroDroid "Iniciar atalho", lançadores) podem
 * guardar como atalho. A pessoa escolhe uma ação e o atalho volta pronto para o app.
 */
public class ShortcutPickerActivity extends Activity {

    private static final String[] NAMES = {
            "Dicionário", "Ligar tradução", "Desligar tradução", "Ligar saída de áudio", "Desligar saída de áudio"
    };
    private static final String[] ACTIONS = {
            ActionActivity.ACTION_DICTIONARY, ActionActivity.ACTION_TRANSLATE_ON, ActionActivity.ACTION_TRANSLATE_OFF,
            ActionActivity.ACTION_AUDIO_ON, ActionActivity.ACTION_AUDIO_OFF
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final boolean[] chosen = {false};
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("Ação do Gerenciador de TTS G")
                .setItems(NAMES, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        chosen[0] = true;
                        setResult(RESULT_OK, result(which));
                        finish();
                    }
                })
                .setNegativeButton("Cancelar", null)
                .create();
        d.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialog) {
                if (!chosen[0]) {
                    setResult(RESULT_CANCELED);
                    finish();
                }
            }
        });
        d.show();
    }

    @SuppressWarnings("deprecation")
    private Intent result(int which) {
        Intent action = new Intent(this, ActionActivity.class);
        action.setAction(ACTIONS[which]);
        action.putExtra(ActionActivity.EXTRA_TOKEN, ActionActivity.token(Prefs.get(this)));
        action.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        String name = "Gerenciador: " + NAMES[which];
        Intent out = null;
        if (Build.VERSION.SDK_INT >= 26) {
            ShortcutManager sm = getSystemService(ShortcutManager.class);
            if (sm != null) {
                ShortcutInfo info = new ShortcutInfo.Builder(this, "acao_" + which)
                        .setShortLabel(NAMES[which])
                        .setLongLabel(name)
                        .setIcon(Icon.createWithResource(this, R.drawable.ic_launcher))
                        .setIntent(action)
                        .build();
                out = sm.createShortcutResultIntent(info);
            }
        }
        if (out == null) {
            out = new Intent();
        }
        // Formato clássico, lido pelo MacroDroid e por lançadores antigos.
        out.putExtra(Intent.EXTRA_SHORTCUT_INTENT, action);
        out.putExtra(Intent.EXTRA_SHORTCUT_NAME, name);
        out.putExtra(Intent.EXTRA_SHORTCUT_ICON_RESOURCE,
                Intent.ShortcutIconResource.fromContext(this, R.drawable.ic_launcher));
        return out;
    }
}
