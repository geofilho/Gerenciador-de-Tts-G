package br.com.geofilho.ttsg;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

/**
 * Backup (menu principal): mostra Fazer backup e Restaurar backup por cima do menu e fecha ao
 * terminar. A tela é transparente; só as janelas aparecem.
 */
public class BackupActivity extends Activity {

    private static final int REQ_BACKUP = 2;
    private static final int REQ_RESTORE = 3;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState == null) {
            backupMenu();
        }
    }

    private final DialogInterface.OnDismissListener closeOnDismiss = new DialogInterface.OnDismissListener() {
        @Override
        public void onDismiss(DialogInterface d) {
            if (!waiting) {
                finish();
            }
        }
    };
    /** Esperando o seletor de arquivos ou outra janela: não fecha ainda. */
    private boolean waiting;

    // ----- backup -----

    private void backupMenu() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.backup)
                .setItems(new String[]{"Fazer backup", "Restaurar backup"}, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        waiting = true;
                        if (which == 0) {
                            makeBackup();
                        } else {
                            pickRestore();
                        }
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .setOnDismissListener(closeOnDismiss)
                .show();
    }

    /** Abre o seletor de arquivos do Android, começando em Downloads, com o nome já preenchido. */
    private void makeBackup() {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/json");
        i.putExtra(Intent.EXTRA_TITLE, Backup.FILE_NAME);
        startInDownloads(i);
        try {
            startActivityForResult(i, REQ_BACKUP);
        } catch (Exception e) {
            Toast.makeText(this, "O seletor de arquivos não abriu.", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void pickRestore() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startInDownloads(i);
        try {
            startActivityForResult(i, REQ_RESTORE);
        } catch (Exception e) {
            Toast.makeText(this, "O seletor de arquivos não abriu.", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private static void startInDownloads(Intent i) {
        if (Build.VERSION.SDK_INT >= 26) {
            i.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI,
                    Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload"));
        }
    }

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        waiting = false;
        if (result != RESULT_OK || data == null || data.getData() == null) {
            finish();
            return;
        }
        final Uri uri = data.getData();
        if (code == REQ_BACKUP) {
            try {
                java.io.OutputStream os = getContentResolver().openOutputStream(uri, "wt");
                if (os == null) {
                    throw new java.io.IOException();
                }
                int[] n = Backup.write(this, os);
                askShare(uri, n);
            } catch (Exception e) {
                message("Não foi possível salvar o backup.");
            }
        } else if (code == REQ_RESTORE) {
            new AlertDialog.Builder(this)
                    .setTitle("Restaurar backup")
                    .setMessage(R.string.backup_restore_confirm)
                    .setPositiveButton("Restaurar", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            restore(uri);
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
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
    }

    /** Depois de salvar: diz o que foi salvo e pergunta se quer compartilhar o arquivo. */
    private void askShare(final Uri uri, int[] n) {
        new AlertDialog.Builder(this)
                .setTitle("Backup salvo")
                .setMessage("Configurações, " + n[0] + (n[0] == 1 ? " palavra" : " palavras") + " do dicionário e "
                        + n[1] + (n[1] == 1 ? " botão nomeado" : " botões nomeados")
                        + ". Deseja compartilhar o arquivo de backup?")
                .setPositiveButton("Sim", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        waiting = true;
                        Intent send = new Intent(Intent.ACTION_SEND);
                        send.setType("application/json");
                        send.putExtra(Intent.EXTRA_STREAM, uri);
                        send.putExtra(Intent.EXTRA_SUBJECT, "Backup do Gerenciador de TTS G");
                        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        try {
                            startActivity(Intent.createChooser(send, "Compartilhar backup"));
                        } catch (Exception e) {
                            Toast.makeText(BackupActivity.this, "Nenhum aplicativo para compartilhar.",
                                    Toast.LENGTH_LONG).show();
                        }
                        finish();
                    }
                })
                .setNegativeButton("Não", null)
                .setOnDismissListener(closeOnDismiss)
                .show();
    }

    private void restore(Uri uri) {
        try {
            java.io.InputStream is = getContentResolver().openInputStream(uri);
            if (is == null) {
                throw new java.io.IOException();
            }
            Backup.restore(this, Backup.readAll(is));
            message(getString(R.string.backup_restored));
        } catch (Exception e) {
            message(getString(R.string.backup_restore_failed));
        }
    }

    private void message(String text) {
        waiting = false;
        new AlertDialog.Builder(this).setTitle(R.string.backup).setMessage(text).setPositiveButton("OK", null)
                .setOnDismissListener(closeOnDismiss).show();
    }
}
