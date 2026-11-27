package br.com.geofilho.ttsg;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.widget.Toast;

/**
 * Backup no menu principal (como no Compartilhar G): Fazer backup e Restaurar backup, por cima da tela
 * principal, que fica aberta o tempo todo (assim o compartilhamento não perde a permissão do arquivo).
 * Cada passo fica anotado no diagnóstico.
 */
final class BackupUi {

    static final int REQ_BACKUP = 7002;
    static final int REQ_RESTORE = 7003;

    private BackupUi() {
    }

    static void menu(final Activity a) {
        new AlertDialog.Builder(a)
                .setTitle(R.string.backup)
                .setItems(new String[]{"Fazer backup", "Restaurar backup"}, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) {
                            make(a);
                        } else {
                            pickRestore(a);
                        }
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Android 10 ou mais novo: salva direto na pasta Download, sem seletor e sem teclado. */
    private static void make(Activity a) {
        if (Build.VERSION.SDK_INT < 29) {
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("application/json");
            i.putExtra(Intent.EXTRA_TITLE, Backup.FILE_NAME);
            try {
                a.startActivityForResult(i, REQ_BACKUP);
            } catch (Exception e) {
                Toast.makeText(a, "O seletor de arquivos não abriu.", Toast.LENGTH_LONG).show();
            }
            return;
        }
        String name = "gerenciador_tts_g_backup_"
                + new java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm", java.util.Locale.ROOT).format(new java.util.Date())
                + ".json";
        Uri uri = null;
        try {
            android.content.ContentValues v = new android.content.ContentValues();
            v.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/json");
            v.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS);
            uri = a.getContentResolver().insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) {
                throw new java.io.IOException("o Android não criou o arquivo");
            }
            java.io.OutputStream os = a.getContentResolver().openOutputStream(uri, "wt");
            if (os == null) {
                throw new java.io.IOException("o arquivo não abriu para gravar");
            }
            int[] n = Backup.write(a, os);
            Diagnostics.note(a, "backup salvo na pasta Download: " + name + ".");
            askShare(a, uri, n, "Salvo na pasta Download com o nome " + name + ". ");
        } catch (Exception e) {
            if (uri != null) {
                try {
                    a.getContentResolver().delete(uri, null, null);
                } catch (Exception ignored) {
                }
            }
            Diagnostics.note(a, "o backup não foi salvo: " + e + ".");
            message(a, "Não foi possível salvar o backup.");
        }
    }

    private static void pickRestore(Activity a) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        if (Build.VERSION.SDK_INT >= 26) {
            i.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI,
                    Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload"));
        }
        try {
            a.startActivityForResult(i, REQ_RESTORE);
        } catch (Exception e) {
            Toast.makeText(a, "O seletor de arquivos não abriu.", Toast.LENGTH_LONG).show();
        }
    }

    /** Resultado do seletor de arquivos. Devolve true se era do backup. */
    static boolean onResult(final Activity a, int code, int result, Intent data) {
        if (code != REQ_BACKUP && code != REQ_RESTORE) {
            return false;
        }
        if (result != Activity.RESULT_OK || data == null || data.getData() == null) {
            return true;
        }
        final Uri uri = data.getData();
        if (code == REQ_BACKUP) {
            try {
                java.io.OutputStream os = a.getContentResolver().openOutputStream(uri, "wt");
                if (os == null) {
                    throw new java.io.IOException();
                }
                askShare(a, uri, Backup.write(a, os), "");
            } catch (Exception e) {
                Diagnostics.note(a, "o backup não foi salvo: " + e + ".");
                message(a, "Não foi possível salvar o backup.");
            }
        } else {
            new AlertDialog.Builder(a)
                    .setTitle("Restaurar backup")
                    .setMessage(R.string.backup_restore_confirm)
                    .setPositiveButton("Restaurar", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            try {
                                java.io.InputStream is = a.getContentResolver().openInputStream(uri);
                                if (is == null) {
                                    throw new java.io.IOException();
                                }
                                Backup.restore(a, Backup.readAll(is));
                                message(a, a.getString(R.string.backup_restored));
                            } catch (Exception e) {
                                Diagnostics.note(a, "o backup não foi restaurado: " + e + ".");
                                message(a, a.getString(R.string.backup_restore_failed));
                            }
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        }
        return true;
    }

    private static void askShare(final Activity a, final Uri uri, int[] n, String where) {
        new AlertDialog.Builder(a)
                .setTitle("Backup salvo")
                .setMessage(where + "Configurações, " + n[0] + (n[0] == 1 ? " palavra" : " palavras") + " do dicionário e "
                        + n[1] + (n[1] == 1 ? " botão nomeado" : " botões nomeados")
                        + ". Deseja compartilhar o arquivo de backup?")
                .setPositiveButton("Sim", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        share(a, uri);
                    }
                })
                .setNegativeButton("Não", null)
                .show();
    }

    private static void share(Activity a, Uri uri) {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("application/json");
        send.putExtra(Intent.EXTRA_STREAM, uri);
        send.putExtra(Intent.EXTRA_SUBJECT, "Backup do Gerenciador de TTS G");
        send.setClipData(android.content.ClipData.newRawUri("", uri));
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        Intent chooser = Intent.createChooser(send, "Compartilhar backup");
        chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            a.startActivity(chooser);
            Diagnostics.note(a, "compartilhamento do backup aberto: " + uri + ".");
        } catch (Exception e) {
            Diagnostics.note(a, "o compartilhamento do backup não abriu: " + e + ".");
            Toast.makeText(a, "Nenhum aplicativo para compartilhar.", Toast.LENGTH_LONG).show();
        }
    }

    private static void message(Activity a, String text) {
        new AlertDialog.Builder(a).setTitle(R.string.backup).setMessage(text).setPositiveButton("OK", null).show();
    }
}
