package br.com.geofilho.ttsg;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.EditText;

/**
 * "Dar nome ao botão": dá um nome ao último botão sem nome em que o leitor de tela parou, num
 * aplicativo comum. Depois disso, sempre que o foco cair num botão do mesmo tipo, no mesmo lugar e
 * do mesmo tamanho nesse aplicativo, o Gerenciador fala o nome dado. Abre pelas configurações
 * rápidas (sem sair do aplicativo) ou pela tela de leitura de números.
 */
public class NameButtonActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            return;
        }
        String[] t = Unlabeled.target();
        if (t == null) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.name_button_title)
                    .setMessage(Prefs.unlabeledOn(Prefs.get(this)) ? R.string.name_button_none
                            : R.string.name_button_off)
                    .setPositiveButton(R.string.gboard_done, closer())
                    .setOnCancelListener(cancelCloser())
                    .show();
            return;
        }
        final String current = Unlabeled.targetName(this);
        final android.view.View box = getLayoutInflater().inflate(R.layout.dialog_name, null);
        final EditText edit = box.findViewById(R.id.edit_name);
        if (current != null) {
            edit.setText(current);
            edit.setSelection(current.length());
        }
        String app = appLabel(t[0]);
        String what = (t[3].isEmpty() ? "Elemento" : t[3]) + " sem nome no aplicativo " + app + ", " + t[2] + ".";
        if (current != null) {
            what += " Nome atual: " + current + ".";
        }
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(R.string.name_button_title)
                .setMessage(what)
                .setView(box)
                .setPositiveButton(R.string.name_button_save, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String name = edit.getText().toString().trim();
                        Unlabeled.nameTarget(NameButtonActivity.this, name);
                        Toggles.say(NameButtonActivity.this, name.isEmpty() ? "Nome apagado." : "Nome guardado: " + name + ".");
                        finish();
                    }
                })
                .setNegativeButton(android.R.string.cancel, closer())
                .setOnCancelListener(cancelCloser());
        if (current != null) {
            b.setNeutralButton(R.string.name_button_delete, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int w) {
                    Unlabeled.nameTarget(NameButtonActivity.this, "");
                    Toggles.say(NameButtonActivity.this, "Nome apagado.");
                    finish();
                }
            });
        }
        AlertDialog dlg = b.show();
        if (dlg.getWindow() != null) {
            dlg.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }
    }

    private String appLabel(String pkg) {
        try {
            return getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    private DialogInterface.OnClickListener closer() {
        return new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int w) {
                finish();
            }
        };
    }

    private DialogInterface.OnCancelListener cancelCloser() {
        return new DialogInterface.OnCancelListener() {
            @Override
            public void onCancel(DialogInterface d) {
                finish();
            }
        };
    }
}
