package br.com.geofilho.ttsg;

import android.content.Context;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.common.model.RemoteModelManager;
import com.google.mlkit.common.sdkinternal.MlKitContext;
import com.google.mlkit.nl.languageid.LanguageIdentification;
import com.google.mlkit.nl.languageid.LanguageIdentifier;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.TranslateRemoteModel;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Tradutor no aparelho (Google ML Kit): traduz sem internet e sem limite, depois de baixar
 * os pacotes de idioma (cerca de 30 MB cada). Funciona em qualquer aparelho com os serviços
 * do Google, inclusive Samsung, que não oferece o tradutor do próprio Android.
 */
final class DeviceTranslator {

    private DeviceTranslator() {
    }

    private static final Map<String, Translator> CLIENTS = new HashMap<>();
    private static volatile Set<String> downloaded;
    private static volatile long checkedAt;
    private static volatile boolean downloading;
    /** O download em andamento espera o Wi-Fi (automático); o botão pode baixar na hora mesmo assim. */
    private static volatile boolean waitingWifi;
    private static volatile String downloadError = "";
    private static volatile boolean autoTried;
    private static LanguageIdentifier identifier;

    /** Prepara o ML Kit (depois de religar, antes da senha, ele ainda não pode funcionar). */
    private static boolean ready(Context c) {
        try {
            MlKitContext.initializeIfNeeded(c.getApplicationContext());
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /** Código do idioma no ML Kit, ou null se ele não traduz esse idioma. */
    static String code(String lang) {
        if (lang == null) {
            return null;
        }
        String l = lang.toLowerCase(Locale.ROOT);
        int dash = l.indexOf('-');
        if (dash > 0 && !l.startsWith("zh")) {
            l = l.substring(0, dash);
        }
        if (l.startsWith("zh")) {
            l = "zh";
        }
        try {
            return TranslateLanguage.fromLanguageTag(l);
        } catch (Throwable e) {
            return null;
        }
    }

    /** Idiomas já baixados (consulta guardada por 1 minuto). */
    static Set<String> downloaded(Context c) {
        Set<String> d = downloaded;
        long now = System.currentTimeMillis();
        if (d != null && now - checkedAt < 60000) {
            return d;
        }
        Set<String> out = new HashSet<>();
        if (ready(c)) {
            try {
                Set<TranslateRemoteModel> models = Tasks.await(
                        RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel.class),
                        3, TimeUnit.SECONDS);
                for (TranslateRemoteModel m : models) {
                    out.add(m.getLanguage());
                }
            } catch (Throwable ignored) {
                return d != null ? d : out;
            }
        }
        downloaded = out;
        checkedAt = now;
        return out;
    }

    /** Pode traduzir de {@code src} para {@code dst} agora, sem internet? */
    static boolean canTranslate(Context c, String src, String dst) {
        String s = code(src);
        String d = code(dst);
        if (s == null || d == null) {
            return false;
        }
        Set<String> have = downloaded(c);
        return have.contains(s) && have.contains(d);
    }

    /**
     * Idioma do texto, pelo identificador do ML Kit (vem dentro do app, não precisa baixar).
     * Retorna null se não tiver certeza.
     */
    static String identify(Context c, String text, long timeoutMs) {
        if (!ready(c)) {
            return null;
        }
        try {
            LanguageIdentifier id;
            synchronized (DeviceTranslator.class) {
                if (identifier == null) {
                    identifier = LanguageIdentification.getClient();
                }
                id = identifier;
            }
            String lang = Tasks.await(id.identifyLanguage(text), timeoutMs, TimeUnit.MILLISECONDS);
            return lang == null || "und".equals(lang) ? null : lang;
        } catch (Throwable e) {
            return null;
        }
    }

    /** Traduz no aparelho. Retorna null se não puder (idioma não baixado, erro ou demora). */
    static String translate(Context c, String text, String src, String dst, long timeoutMs) {
        String s = code(src);
        String d = code(dst);
        if (s == null || d == null || s.equals(d) || !canTranslate(c, src, dst)) {
            return null;
        }
        try {
            Translator t;
            String key = s + ">" + d;
            synchronized (CLIENTS) {
                t = CLIENTS.get(key);
                if (t == null) {
                    t = Translation.getClient(new TranslatorOptions.Builder()
                            .setSourceLanguage(s).setTargetLanguage(d).build());
                    CLIENTS.put(key, t);
                }
            }
            String out = Tasks.await(t.translate(text), timeoutMs, TimeUnit.MILLISECONDS);
            return out == null || out.trim().isEmpty() ? null : out;
        } catch (Throwable e) {
            return null;
        }
    }

    interface Done {
        void done(boolean ok, String error);
    }

    /**
     * Baixa os pacotes de inglês e do idioma de destino (e da origem, se escolhida).
     * {@code wifiOnly}: o download automático só usa Wi-Fi; o botão da página usa qualquer rede.
     */
    static void download(final Context c, final String src, final String dst, final boolean wifiOnly,
                         final Done done) {
        if (downloading && !waitingWifi) {
            return; // já está baixando na hora, pelo botão
        }
        // Downloads pelo Wi-Fi podem se somar (o ML Kit não baixa o mesmo idioma duas vezes);
        // o botão passa na frente de um download que ainda espera o Wi-Fi.
        downloading = true;
        waitingWifi = wifiOnly;
        downloadError = "";
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean ok = true;
                String err = "";
                if (!ready(c)) {
                    ok = false;
                    err = "o tradutor no aparelho ainda não pode ser usado; desbloqueie o celular e tente de novo";
                } else {
                    Set<String> langs = new HashSet<>();
                    langs.add(TranslateLanguage.ENGLISH);
                    String d = code(dst);
                    if (d != null) {
                        langs.add(d);
                    }
                    String s = code(src);
                    if (s != null) {
                        langs.add(s);
                    }
                    if (wifiOnly) {
                        // Automático: não baixa de novo um idioma que a pessoa excluiu.
                        langs.removeAll(removed(c));
                    } else {
                        // Botão: a pessoa pediu, então esses idiomas voltam a valer.
                        for (String l : langs) {
                            unmarkRemoved(c, l);
                        }
                    }
                    DownloadConditions.Builder cond = new DownloadConditions.Builder();
                    if (wifiOnly) {
                        cond.requireWifi();
                    }
                    for (String l : langs) {
                        try {
                            Tasks.await(RemoteModelManager.getInstance().download(
                                    new TranslateRemoteModel.Builder(l).build(), cond.build()), 15, TimeUnit.MINUTES);
                        } catch (Throwable e) {
                            ok = false;
                            err = "não foi possível baixar agora; confira a internet e o espaço livre";
                        }
                    }
                }
                checkedAt = 0; // consulta de novo o que foi baixado
                downloadError = err;
                if (waitingWifi == wifiOnly) {
                    downloading = false;
                    waitingWifi = false;
                }
                if (done != null) {
                    done.done(ok, err);
                }
            }
        }, "baixar-tradutor").start();
    }

    /** Os idiomas escolhidos mudaram: o download automático pode tentar de novo. */
    static void resetAuto() {
        autoTried = false;
    }

    /**
     * Com a tradução ligada, baixa sozinho pelo Wi-Fi o que faltar: uma vez quando o app abre e
     * de novo quando os idiomas de origem ou de destino mudam.
     */
    static void autoDownload(Context c, String src, String dst) {
        if (autoTried || (downloading && !waitingWifi)) {
            return;
        }
        autoTried = true;
        if (canTranslate(c, "en", dst) && ("auto".equals(src) || canTranslate(c, src, dst))) {
            return;
        }
        download(c, "auto".equals(src) ? null : src, dst, true, null);
    }

    /** Baixando na hora (pelo botão): o botão fica escondido até terminar. */
    static boolean isDownloading() {
        return downloading && !waitingWifi;
    }

    // ----- idiomas baixados: lista e exclusão -----

    private static final String REMOVED = "device_removed";

    /** Idiomas que a pessoa excluiu (o download automático não baixa de novo). */
    static Set<String> removed(Context c) {
        Set<String> out = new HashSet<>();
        for (String l : Prefs.get(c).getString(REMOVED, "").split(",")) {
            if (!l.isEmpty()) {
                out.add(l);
            }
        }
        return out;
    }

    private static void saveRemoved(Context c, Set<String> set) {
        StringBuilder b = new StringBuilder();
        for (String l : set) {
            if (b.length() > 0) {
                b.append(',');
            }
            b.append(l);
        }
        Prefs.get(c).edit().putString(REMOVED, b.toString()).apply();
    }

    /** A pessoa escolheu esse idioma de novo (origem ou destino): o automático pode baixar. */
    static void unmarkRemoved(Context c, String lang) {
        String l = code(lang);
        Set<String> set = removed(c);
        if (l != null && set.remove(l)) {
            saveRemoved(c, set);
        }
    }

    /** Idiomas baixados, consultados agora, em ordem alfabética do nome. */
    static java.util.List<String> downloadedList(Context c) {
        checkedAt = 0;
        java.util.List<String> list = new java.util.ArrayList<>(downloaded(c));
        java.util.Collections.sort(list, new java.util.Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                return name(a).compareTo(name(b));
            }
        });
        return list;
    }

    /** Nome do idioma em português, por exemplo "Inglês". */
    static String name(String code) {
        String n = new Locale(code).getDisplayLanguage(new Locale("pt", "BR"));
        if (n == null || n.isEmpty()) {
            return code;
        }
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }

    /** Exclui o pacote do idioma. Retorna null se deu certo, ou o motivo da falha. */
    static String delete(Context c, String lang) {
        if (!ready(c)) {
            return "o tradutor no aparelho ainda não pode ser usado; desbloqueie o celular e tente de novo";
        }
        try {
            synchronized (CLIENTS) {
                java.util.Iterator<Map.Entry<String, Translator>> it = CLIENTS.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<String, Translator> e = it.next();
                    String[] pair = e.getKey().split(">");
                    if (lang.equals(pair[0]) || lang.equals(pair[1])) {
                        e.getValue().close();
                        it.remove();
                    }
                }
            }
            Tasks.await(RemoteModelManager.getInstance().deleteDownloadedModel(
                    new TranslateRemoteModel.Builder(lang).build()), 30, TimeUnit.SECONDS);
            Set<String> set = removed(c);
            set.add(lang);
            saveRemoved(c, set);
            checkedAt = 0;
            return null;
        } catch (Throwable e) {
            checkedAt = 0;
            return "não foi possível excluir agora";
        }
    }

    /** Frase de estado para a página de tradução. */
    static String status(Context c, String src, String dst) {
        if (downloading) {
            return waitingWifi
                    ? "baixando os idiomas pelo Wi-Fi; se estiver sem Wi-Fi, use o botão abaixo para baixar agora"
                    : "baixando os idiomas, isso pode levar alguns minutos";
        }
        String d = code(dst);
        if (d == null) {
            return "não traduz para o idioma de destino escolhido";
        }
        boolean en = canTranslate(c, "en", dst);
        boolean own = "auto".equals(src) || canTranslate(c, src, dst);
        if (en && own) {
            return "pronto, traduz sem internet. Toque para ver ou excluir os idiomas baixados";
        }
        String err = downloadError;
        boolean any = !downloaded(c).isEmpty();
        return "idiomas não baixados" + (err.isEmpty() ? "" : " (" + err + ")")
                + (any ? ". Toque para ver ou excluir os idiomas baixados" : "");
    }
}
