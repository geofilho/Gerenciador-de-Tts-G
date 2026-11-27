package br.com.geofilho.ttsg;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Botões que você nomeou, separados por aplicativo. Tocar num botão abre Editar e Apagar; as
 * mesmas opções ficam nas ações do leitor de tela (menu de ações do TalkBack) em cada botão.
 */
public class NamedButtonsActivity extends Activity {

    private LinearLayout list;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_named_buttons);
        list = findViewById(R.id.named_list);
    }

    @Override
    protected void onResume() {
        super.onResume();
        fill();
    }

    private void fill() {
        list.removeAllViews();
        List<String[]> all = Unlabeled.listNames(this);
        if (all.isEmpty()) {
            TextView t = text(getString(R.string.named_empty), false);
            list.addView(t);
            return;
        }
        // Agrupa por aplicativo, em ordem alfabética do nome do aplicativo e depois do nome do botão.
        final Map<String, String> labels = new LinkedHashMap<>();
        for (String[] e : all) {
            if (!labels.containsKey(e[1])) {
                labels.put(e[1], appLabel(e[1]));
            }
        }
        Collections.sort(all, new Comparator<String[]>() {
            @Override
            public int compare(String[] a, String[] b) {
                int r = labels.get(a[1]).compareToIgnoreCase(labels.get(b[1]));
                return r != 0 ? r : a[2].compareToIgnoreCase(b[2]);
            }
        });
        String lastPkg = null;
        for (final String[] e : all) {
            if (!e[1].equals(lastPkg)) {
                lastPkg = e[1];
                int count = 0;
                for (String[] x : all) {
                    if (x[1].equals(lastPkg)) {
                        count++;
                    }
                }
                list.addView(text(labels.get(lastPkg) + ": " + count + (count == 1 ? " botão" : " botões"), true));
            }
            Button b = (Button) getLayoutInflater().inflate(R.layout.item_named_button, list, false);
            b.setText(e[2] + (e[3].isEmpty() ? "" : " (" + e[3] + ")"));
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    choose(e, labels.get(e[1]));
                }
            });
            b.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override
                public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.addAction(new AccessibilityNodeInfo.AccessibilityAction(R.id.action_edit_name,
                            getString(R.string.named_edit)));
                    info.addAction(new AccessibilityNodeInfo.AccessibilityAction(R.id.action_delete_name,
                            getString(R.string.named_delete)));
                }

                @Override
                public boolean performAccessibilityAction(View host, int action, Bundle args) {
                    if (action == R.id.action_edit_name) {
                        edit(e);
                        return true;
                    }
                    if (action == R.id.action_delete_name) {
                        delete(e);
                        return true;
                    }
                    return super.performAccessibilityAction(host, action, args);
                }
            });
            list.addView(b);
        }
    }

    private TextView text(String s, boolean heading) {
        TextView t = (TextView) getLayoutInflater().inflate(R.layout.item_named_text, list, false);
        t.setText(s);
        if (heading) {
            t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
            if (Build.VERSION.SDK_INT >= 28) {
                t.setAccessibilityHeading(true);
            }
        }
        return t;
    }

    private void choose(final String[] e, String app) {
        new AlertDialog.Builder(this)
                .setTitle(e[2] + ", " + app)
                .setItems(new CharSequence[]{getString(R.string.named_edit), getString(R.string.named_delete)},
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                if (which == 0) {
                                    edit(e);
                                } else {
                                    delete(e);
                                }
                            }
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void edit(final String[] e) {
        final View box = getLayoutInflater().inflate(R.layout.dialog_name, null);
        final EditText edit = box.findViewById(R.id.edit_name);
        edit.setText(e[2]);
        edit.setSelection(e[2].length());
        new AlertDialog.Builder(this)
                .setTitle(R.string.named_edit)
                .setView(box)
                .setPositiveButton(R.string.name_button_save, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String name = edit.getText().toString().trim();
                        if (name.isEmpty()) {
                            delete(e);
                            return;
                        }
                        Unlabeled.renameKey(NamedButtonsActivity.this, e[0], name);
                        Toggles.say(NamedButtonsActivity.this, "Nome guardado: " + name + ".");
                        fill();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void delete(final String[] e) {
        new AlertDialog.Builder(this)
                .setMessage("Apagar o nome " + e[2] + "?")
                .setPositiveButton(R.string.named_delete, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        Unlabeled.renameKey(NamedButtonsActivity.this, e[0], "");
                        Toggles.say(NamedButtonsActivity.this, "Nome apagado: " + e[2] + ".");
                        fill();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private String appLabel(String pkg) {
        try {
            return getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0)).toString();
        } catch (Exception ex) {
            return pkg;
        }
    }
}
