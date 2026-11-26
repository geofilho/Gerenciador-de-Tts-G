package br.com.geofilho.ttsg;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.net.Uri;
import android.os.Bundle;

import java.io.InputStream;

/**
 * Abre um arquivo de dicionário tocado no gerenciador de arquivos ("Abrir com",
 * "Importar no Gerenciador de TTS G"): pede confirmação e importa, juntando com o
 * dicionário atual. A tela é transparente; só a janela aparece.
 */
public class DictionaryImportActivity extends Activity {

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        final Uri uri = getIntent().getData();
        if (uri == null) {
            finish();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Importar dicionário")
                .setMessage("As palavras deste arquivo serão juntadas ao dicionário do Gerenciador de TTS G. "
                        + "Continuar?")
                .setPositiveButton("Importar", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        importFile(uri);
                    }
                })
                .setNegativeButton("Cancelar", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        finish();
                    }
                })
                .setOnCancelListener(new DialogInterface.OnCancelListener() {
                    @Override
                    public void onCancel(DialogInterface d) {
                        finish();
                    }
                })
                .show();
    }

    private void importFile(Uri uri) {
        String msg;
        try {
            InputStream is = getContentResolver().openInputStream(uri);
            if (is == null) {
                throw new java.io.IOException();
            }
            int n = Dictionary.get(this).importFrom(is);
            msg = n == 0
                    ? "Nenhuma palavra encontrada neste arquivo. Confira se é um dicionário do Gerenciador de TTS G."
                    : n + (n == 1 ? " palavra importada." : " palavras importadas.");
        } catch (Exception e) {
            msg = "Não foi possível importar este arquivo.";
        }
        new AlertDialog.Builder(this)
                .setTitle("Dicionário")
                .setMessage(msg)
                .setPositiveButton("OK", null)
                .setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override
                    public void onDismiss(DialogInterface d) {
                        finish();
                    }
                })
                .show();
    }
}
