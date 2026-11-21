package br.com.geofilho.ttsg;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

/** Janelas do dicionário, usadas pela tela principal, pelo atalho do ícone e pelo widget. */
final class DictionaryDialogs {

    private DictionaryDialogs() {
    }

    /**
     * Menu "Dicionário": Adicionar palavra ou Gerenciar dicionário.
     * {@code onDone}: chamado quando o fluxo termina (por exemplo, para fechar a janela do widget).
     */
    static void showMenu(final Activity act, final Runnable onDone) {
        final boolean[] next = {false};
        AlertDialog d = new AlertDialog.Builder(act)
                .setTitle(R.string.dictionary)
                .setItems(new String[]{act.getString(R.string.add_word_short), act.getString(R.string.manage_dictionary)},
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                next[0] = true;
                                if (which == 0) {
                                    addWord(act, onDone);
                                } else {
                                    act.startActivity(new Intent(act, DictionaryActivity.class));
                                    done(onDone);
                                }
                            }
                        })
                .setNegativeButton("Cancelar", null)
                .create();
        d.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialog) {
                if (!next[0]) {
                    done(onDone);
                }
            }
        });
        d.show();
    }

    /** Janela com os campos "Palavra ou frase" e "Substituir por". */
    static void addWord(final Activity act, final Runnable onDone) {
        View form = act.getLayoutInflater().inflate(R.layout.dialog_entry, null);
        final EditText word = form.findViewById(R.id.edit_word);
        final EditText replacement = form.findViewById(R.id.edit_replacement);
        AlertDialog d = new AlertDialog.Builder(act)
                .setTitle(R.string.add_word_short)
                .setView(form)
                .setPositiveButton("Salvar", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String w = word.getText().toString();
                        if (w.trim().isEmpty()) {
                            Toast.makeText(act, "A palavra não pode ficar vazia.", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        Dictionary.get(act).put(w, replacement.getText().toString());
                        Toast.makeText(act, "Salvo no dicionário: " + w.trim(), Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancelar", null)
                .create();
        d.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialog) {
                done(onDone);
            }
        });
        d.show();
        word.requestFocus();
    }

    private static void done(Runnable onDone) {
        if (onDone != null) {
            onDone.run();
        }
    }
}
