package br.com.geofilho.ttsg;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Verifica se há versão nova publicada no GitHub, mostra as novidades
 * e, com o OK da pessoa, baixa e abre o instalador.
 */
final class Updater {

    /** Arquivo com a última versão: versionCode, versionName, apk e novidades. */
    static final String INFO_URL =
            "https://raw.githubusercontent.com/geofilho/Gerenciador-de-Tts-G/main/atualizacao/versao.json";

    private final Activity act;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean busy;
    /** Quando precisa liberar "instalar apps desconhecidos", instala ao voltar. */
    private boolean installAfterPermission;

    Updater(Activity act) {
        this.act = act;
    }

    void check() {
        if (busy) {
            return;
        }
        busy = true;
        toast("Procurando atualização");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String json = new String(download(INFO_URL + "?t=" + System.currentTimeMillis()), "UTF-8");
                    final JSONObject info = new JSONObject(json);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            busy = false;
                            showResult(info);
                        }
                    });
                } catch (Exception e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            busy = false;
                            message("Atualização", "Não foi possível verificar a atualização. "
                                    + "Confira se o aparelho está conectado à internet e tente de novo.");
                        }
                    });
                }
            }
        }).start();
    }

    /** Chamar no onResume da tela: continua a instalação depois da permissão. */
    void onResume() {
        if (installAfterPermission && canInstall()) {
            installAfterPermission = false;
            install();
        }
    }

    private void showResult(JSONObject info) {
        int remoteCode = info.optInt("versionCode", 0);
        String remoteName = info.optString("versionName", "");
        final String apkUrl = info.optString("apk", "");
        String news = info.optString("novidades", "").trim();
        int localCode = 0;
        String localName = "";
        try {
            PackageInfo pi = act.getPackageManager().getPackageInfo(act.getPackageName(), 0);
            localCode = pi.versionCode;
            localName = pi.versionName;
        } catch (Exception ignored) {
        }
        if (remoteCode <= localCode || apkUrl.isEmpty()) {
            message("Atualização", "Você já está com a versão mais recente, " + localName + ".");
            return;
        }
        StringBuilder msg = new StringBuilder();
        msg.append("Versão instalada: ").append(localName).append(".\n");
        msg.append("Nova versão: ").append(remoteName).append(".\n\n");
        if (!news.isEmpty()) {
            msg.append("Novidades:\n").append(news).append("\n\n");
        }
        msg.append("Deseja baixar e instalar agora?");
        new AlertDialog.Builder(act)
                .setTitle("Atualização disponível")
                .setMessage(msg.toString())
                .setPositiveButton("OK, baixar", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        downloadApk(apkUrl);
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void downloadApk(final String url) {
        final AlertDialog wait = new AlertDialog.Builder(act)
                .setTitle("Atualização")
                .setMessage("Baixando a atualização, aguarde.")
                .setCancelable(false)
                .show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean ok;
                try {
                    byte[] data = download(url + (url.contains("?") ? "&" : "?") + "t=" + System.currentTimeMillis());
                    File f = ApkProvider.file(act);
                    OutputStream os = new FileOutputStream(f);
                    os.write(data);
                    os.close();
                    // Confere se o arquivo é mesmo uma versão deste aplicativo.
                    PackageInfo pi = act.getPackageManager().getPackageArchiveInfo(f.getPath(), 0);
                    ok = pi != null && act.getPackageName().equals(pi.packageName);
                } catch (Exception e) {
                    ok = false;
                }
                final boolean success = ok;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            wait.dismiss();
                        } catch (Exception ignored) {
                        }
                        if (success) {
                            install();
                        } else {
                            message("Atualização", "Não foi possível baixar a atualização. Tente de novo mais tarde.");
                        }
                    }
                });
            }
        }).start();
    }

    private boolean canInstall() {
        return Build.VERSION.SDK_INT < 26 || act.getPackageManager().canRequestPackageInstalls();
    }

    private void install() {
        if (!canInstall()) {
            new AlertDialog.Builder(act)
                    .setTitle("Permissão necessária")
                    .setMessage("Para instalar a atualização, o Android precisa que você permita que o "
                            + "Gerenciador de TTS G instale aplicativos. Na próxima tela, ative a opção "
                            + "e depois volte. A instalação continua sozinha.")
                    .setPositiveButton("Abrir configuração", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int which) {
                            installAfterPermission = true;
                            try {
                                act.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                        Uri.parse("package:" + act.getPackageName())));
                            } catch (ActivityNotFoundException e) {
                                act.startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS));
                            }
                        }
                    })
                    .setNegativeButton("Cancelar", null)
                    .show();
            return;
        }
        Intent i;
        if (Build.VERSION.SDK_INT >= 24) {
            i = new Intent(Intent.ACTION_INSTALL_PACKAGE);
            i.setData(ApkProvider.URI);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else {
            // Android 5 e 6: o instalador precisa de um arquivo legível.
            File pub = new File(act.getExternalCacheDir(), "atualizacao.apk");
            try {
                copy(ApkProvider.file(act), pub);
            } catch (Exception e) {
                message("Atualização", "Não foi possível preparar a instalação.");
                return;
            }
            i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(Uri.fromFile(pub), "application/vnd.android.package-archive");
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            act.startActivity(i);
        } catch (ActivityNotFoundException e) {
            message("Atualização", "O instalador do Android não abriu.");
        }
    }

    // ----- utilidades -----

    private static byte[] download(String address) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setUseCaches(false);
        c.setRequestProperty("Cache-Control", "no-cache");
        try {
            if (c.getResponseCode() != 200) {
                throw new IOException("HTTP " + c.getResponseCode());
            }
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            in.close();
            return bos.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    private static void copy(File from, File to) throws IOException {
        InputStream in = new java.io.FileInputStream(from);
        OutputStream out = new FileOutputStream(to);
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        in.close();
        out.close();
    }

    private void message(String title, String text) {
        new AlertDialog.Builder(act).setTitle(title).setMessage(text).setPositiveButton("OK", null).show();
    }

    private void toast(String s) {
        Toast.makeText(act, s, Toast.LENGTH_SHORT).show();
    }
}
