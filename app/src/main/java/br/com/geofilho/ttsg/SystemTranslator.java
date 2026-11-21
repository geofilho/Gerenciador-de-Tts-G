package br.com.geofilho.ttsg;

import android.annotation.TargetApi;
import android.app.PendingIntent;
import android.content.Context;
import android.icu.util.ULocale;
import android.os.Build;
import android.os.CancellationSignal;
import android.util.SparseArray;
import android.view.translation.TranslationCapability;
import android.view.translation.TranslationContext;
import android.view.translation.TranslationManager;
import android.view.translation.TranslationRequest;
import android.view.translation.TranslationRequestValue;
import android.view.translation.TranslationResponse;
import android.view.translation.TranslationResponseValue;
import android.view.translation.TranslationSpec;
import android.view.translation.Translator;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Tradutor do próprio Android (Android 12+): funciona no aparelho, sem
 * internet e sem limite, quando o fabricante oferece esse serviço. Não pesa
 * nada no app, porque o tradutor é do sistema.
 */
@TargetApi(31)
final class SystemTranslator {

    private static final Executor DIRECT = new Executor() {
        @Override
        public void execute(Runnable r) {
            r.run();
        }
    };
    private static final Map<String, Translator> TRANSLATORS = new HashMap<>();
    /** Pares de idiomas que o sistema traduz no aparelho, por exemplo "en>pt". Null = ainda não consultado. */
    private static volatile Set<String> onDevice;
    private static volatile Set<String> downloadable;
    private static volatile long checkedAt;

    private SystemTranslator() {
    }

    static boolean supported() {
        return Build.VERSION.SDK_INT >= 31;
    }

    private static String lang(ULocale l) {
        String c = l.getLanguage();
        if ("zh".equals(c)) {
            return "TW".equals(l.getCountry()) || "Hant".equals(l.getScript()) ? "zh-tw" : "zh-cn";
        }
        return "iw".equals(c) ? "he" : "in".equals(c) ? "id" : c;
    }

    private static String key(String src, String dst) {
        return src.toLowerCase(Locale.ROOT) + ">" + dst.toLowerCase(Locale.ROOT);
    }

    /** Consulta (no máximo a cada 10 min) quais idiomas o sistema traduz. Não chamar na thread da tela. */
    static void refresh(Context c, boolean force) {
        if (!supported() || (!force && onDevice != null && System.currentTimeMillis() - checkedAt < 600000)) {
            return;
        }
        Set<String> local = new java.util.HashSet<>();
        Set<String> dl = new java.util.HashSet<>();
        try {
            TranslationManager tm = c.getSystemService(TranslationManager.class);
            if (tm != null) {
                Set<TranslationCapability> caps = tm.getOnDeviceTranslationCapabilities(
                        TranslationSpec.DATA_FORMAT_TEXT, TranslationSpec.DATA_FORMAT_TEXT);
                if (caps != null) {
                    for (TranslationCapability cap : caps) {
                        String k = key(lang(cap.getSourceSpec().getLocale()), lang(cap.getTargetSpec().getLocale()));
                        if (cap.getState() == TranslationCapability.STATE_ON_DEVICE) {
                            local.add(k);
                        } else if (cap.getState() == TranslationCapability.STATE_AVAILABLE_TO_DOWNLOAD
                                || cap.getState() == TranslationCapability.STATE_DOWNLOADING) {
                            dl.add(k);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            // Aparelho sem o serviço de tradução do sistema.
        }
        onDevice = Collections.unmodifiableSet(local);
        downloadable = Collections.unmodifiableSet(dl);
        checkedAt = System.currentTimeMillis();
    }

    /** Situação para mostrar na página e no diagnóstico. */
    static String status(Context c, String dst) {
        if (!supported()) {
            return "não disponível: precisa do Android 12 ou mais novo";
        }
        refresh(c, false);
        Set<String> local = onDevice;
        Set<String> dl = downloadable;
        int toDst = 0;
        int dlToDst = 0;
        String d = dstBase(dst);
        for (String k : local) {
            if (k.endsWith(">" + d)) {
                toDst++;
            }
        }
        for (String k : dl) {
            if (k.endsWith(">" + d)) {
                dlToDst++;
            }
        }
        if (local.isEmpty() && dl.isEmpty()) {
            return "não oferecido pelo fabricante deste aparelho";
        }
        if (toDst > 0) {
            return "disponível no aparelho, sem internet, para " + toDst + " idioma(s) de origem"
                    + (local.contains(key("en", d)) ? ", inclusive inglês" : "");
        }
        if (dlToDst > 0) {
            return "existe, mas o idioma ainda não foi baixado; use o botão Baixar idiomas do tradutor do Android";
        }
        return "existe, mas não traduz para o idioma de destino escolhido";
    }

    /** O idioma de destino tem algum idioma para baixar no tradutor do sistema? */
    static boolean canDownload() {
        Set<String> dl = downloadable;
        return dl != null && !dl.isEmpty();
    }

    /** Tela do sistema para baixar os idiomas do tradutor, ou null se não houver. */
    static PendingIntent settingsIntent(Context c) {
        if (!supported()) {
            return null;
        }
        try {
            TranslationManager tm = c.getSystemService(TranslationManager.class);
            return tm == null ? null : tm.getOnDeviceTranslationSettingsActivityIntent();
        } catch (Throwable e) {
            return null;
        }
    }

    private static String dstBase(String dst) {
        String d = dst.toLowerCase(Locale.ROOT);
        return d.startsWith("zh") ? d : (d.indexOf('-') > 0 ? d.substring(0, d.indexOf('-')) : d);
    }

    /**
     * Traduz no aparelho. {@code src}: idioma de origem conhecido (o tradutor do sistema precisa
     * dele). Retorna o texto traduzido ou null se o sistema não puder traduzir esse par.
     */
    static String translate(Context c, String text, String src, String dst, long timeoutMs) {
        if (!supported() || src == null) {
            return null;
        }
        refresh(c, false);
        String s = dstBase(src);
        String d = dstBase(dst);
        Set<String> local = onDevice;
        if (local == null || !local.contains(key(s, d))) {
            return null;
        }
        try {
            Translator t = translator(c, s, d, timeoutMs);
            if (t == null) {
                return null;
            }
            final String[] out = new String[1];
            final CountDownLatch done = new CountDownLatch(1);
            TranslationRequest req = new TranslationRequest.Builder()
                    .setTranslationRequestValues(Collections.singletonList(TranslationRequestValue.forText(text)))
                    .build();
            CancellationSignal cancel = new CancellationSignal();
            t.translate(req, cancel, DIRECT, new Consumer<TranslationResponse>() {
                @Override
                public void accept(TranslationResponse r) {
                    if (r != null && r.getTranslationStatus() == TranslationResponse.TRANSLATION_STATUS_SUCCESS) {
                        SparseArray<TranslationResponseValue> values = r.getTranslationResponseValues();
                        if (values != null && values.size() > 0) {
                            TranslationResponseValue v = values.valueAt(0);
                            if (v != null && v.getStatusCode() == TranslationResponseValue.STATUS_SUCCESS
                                    && v.getText() != null) {
                                out[0] = v.getText().toString();
                            }
                        }
                    }
                    if (r == null || r.isFinalResponse()) {
                        done.countDown();
                    }
                }
            });
            if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                cancel.cancel();
                return null;
            }
            return out[0];
        } catch (Throwable e) {
            return null;
        }
    }

    private static Translator translator(Context c, String s, String d, long timeoutMs) throws InterruptedException {
        String k = key(s, d);
        synchronized (TRANSLATORS) {
            Translator t = TRANSLATORS.get(k);
            if (t != null && !t.isDestroyed()) {
                return t;
            }
        }
        TranslationManager tm = c.getSystemService(TranslationManager.class);
        if (tm == null) {
            return null;
        }
        final Translator[] made = new Translator[1];
        final CountDownLatch ready = new CountDownLatch(1);
        TranslationContext ctx = new TranslationContext.Builder(
                new TranslationSpec(new ULocale(s), TranslationSpec.DATA_FORMAT_TEXT),
                new TranslationSpec(new ULocale(d), TranslationSpec.DATA_FORMAT_TEXT)).build();
        tm.createOnDeviceTranslator(ctx, DIRECT, new Consumer<Translator>() {
            @Override
            public void accept(Translator t) {
                made[0] = t;
                ready.countDown();
            }
        });
        if (!ready.await(Math.max(timeoutMs, 2000), TimeUnit.MILLISECONDS) || made[0] == null) {
            return null;
        }
        synchronized (TRANSLATORS) {
            TRANSLATORS.put(k, made[0]);
        }
        return made[0];
    }
}
