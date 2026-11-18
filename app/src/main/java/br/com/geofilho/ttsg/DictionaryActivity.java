package br.com.geofilho.ttsg;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Toast;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/** Lista de palavras: tocar abre Editar ou Remover. Embaixo, exportar e importar. */
public class DictionaryActivity extends Activity {

    private static final int REQ_EXPORT = 1;
    private static final int REQ_IMPORT = 2;

    private ListView listView;
    private View empty;
    private List<Dictionary.Entry> entries = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dictionary);
        listView = findViewById(R.id.list);
        empty = findViewById(R.id.empty);

        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                showOptions(entries.get(position));
            }
        });
        findViewById(R.id.btn_export).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("application/json");
                i.putExtra(Intent.EXTRA_TITLE, "dicionario_tts_g.json");
                startActivityForResult(i, REQ_EXPORT);
            }
        });
        findViewById(R.id.btn_import).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                startActivityForResult(i, REQ_IMPORT);
            }
        });
        refresh();
    }

    private void refresh() {
        entries = Dictionary.get(this).list();
        List<String> labels = new ArrayList<>();
        for (Dictionary.Entry e : entries) {
            labels.add(e.word + ", substitui por: " + (e.replacement.isEmpty() ? "(nada)" : e.replacement));
        }
        listView.setAdapter(new ArrayAdapter<>(this, R.layout.item_entry, labels));
        boolean isEmpty = entries.isEmpty();
        empty.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        listView.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
        setTitle(getString(R.string.title_dictionary) + " (" + entries.size() + ")");
    }

    private void showOptions(final Dictionary.Entry e) {
        new AlertDialog.Builder(this)
                .setTitle(e.word)
                .setItems(new String[]{"Editar", "Remover"}, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) {
                            edit(e);
                        } else {
                            confirmRemove(e);
                        }
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void edit(final Dictionary.Entry e) {
        View form = getLayoutInflater().inflate(R.layout.dialog_entry, null);
        final EditText w = form.findViewById(R.id.edit_word);
        final EditText r = form.findViewById(R.id.edit_replacement);
        w.setText(e.word);
        r.setText(e.replacement);
        new AlertDialog.Builder(this)
                .setTitle("Editar")
                .setView(form)
                .setPositiveButton("Salvar", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        String word = w.getText().toString();
                        if (word.trim().isEmpty()) {
                            toast("A palavra não pode ficar vazia.");
                            return;
                        }
                        Dictionary.get(DictionaryActivity.this).replace(e.word, word, r.getText().toString());
                        toast("Alterado.");
                        refresh();
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void confirmRemove(final Dictionary.Entry e) {
        new AlertDialog.Builder(this)
                .setTitle("Remover " + e.word + "?")
                .setPositiveButton("Remover", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        Dictionary.get(DictionaryActivity.this).remove(e.word);
                        toast("Removido: " + e.word);
                        refresh();
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        try {
            if (requestCode == REQ_EXPORT) {
                OutputStream os = getContentResolver().openOutputStream(uri, "wt");
                if (os == null) {
                    throw new java.io.IOException();
                }
                Dictionary.get(this).exportTo(os);
                toast("Dicionário exportado com " + entries.size() + " palavras.");
            } else if (requestCode == REQ_IMPORT) {
                InputStream is = getContentResolver().openInputStream(uri);
                if (is == null) {
                    throw new java.io.IOException();
                }
                int n = Dictionary.get(this).importFrom(is);
                toast(n + " palavras importadas.");
                refresh();
            }
        } catch (Exception ex) {
            toast(requestCode == REQ_EXPORT ? "Não foi possível exportar." : "Não foi possível importar este arquivo.");
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }
}
