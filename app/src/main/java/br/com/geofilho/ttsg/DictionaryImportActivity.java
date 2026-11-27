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
        // Arquivo de backup tocado no gerenciador de arquivos: oferece restaurar.
        final String text;
        try {
            InputStream is = getContentResolver().openInputStream(uri);
            text = is == null ? "" : Backup.readAll(is);
        } catch (Exception e) {
            done("Dicionário", "Não foi possível abrir este arquivo.");
            return;
        }
        if (Backup.isBackup(text)) {
            new AlertDialog.Builder(this)
                    .setTitle("Restaurar backup no Gerenciador de TTS G")
                    .setMessage(R.string.backup_restore_confirm)
                    .setPositiveButton("Restaurar", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            try {
                                Backup.restore(DictionaryImportActivity.this, text);
                                done("Backup", getString(R.string.backup_restored));
                            } catch (Exception e) {
                                done("Backup", getString(R.string.backup_restore_failed));
                            }
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
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Importar dicionário")
                .setMessage("As palavras deste arquivo serão juntadas ao dicionário do Gerenciador de TTS G. "
                        + "Continuar?")
                .setPositiveButton("Importar", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        importFile(text);
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

    private void importFile(String text) {
        String msg;
        try {
            int n = Dictionary.get(this).importFrom(new java.io.ByteArrayInputStream(text.getBytes("UTF-8")));
            msg = n == 0
                    ? "Nenhuma palavra encontrada neste arquivo. Confira se é um dicionário do Gerenciador de TTS G."
                    : n + (n == 1 ? " palavra importada." : " palavras importadas.");
        } catch (Exception e) {
            msg = "Não foi possível importar este arquivo.";
        }
        done("Dicionário", msg);
    }

    private void done(String title, String msg) {
        new AlertDialog.Builder(this)
                .setTitle(title)
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
