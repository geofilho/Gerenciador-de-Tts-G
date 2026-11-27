package br.com.geofilho.ttsg;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Vozes dos outros motores instalados, que o Gerenciador oferece aos aplicativos que deixam escolher a
 * voz (por exemplo, o Ariadne GPS: "voz para cruzamentos"). A lista fica guardada, para o aplicativo
 * recebê-la na hora, e é atualizada em segundo plano quando o motor abre e quando um motor é instalado,
 * removido ou atualizado.
 */
final class VoiceCatalog {

    private VoiceCatalog() {
    }

    /** Uma voz de outro motor: nome mostrado ao aplicativo, motor, nome da voz no motor e idioma. */
    static final class Entry {
        final String name;
        final String pkg;
        final String voice;
        final Locale locale;
        final int quality;
        final int latency;
        final boolean network;

        Entry(String name, String pkg, String voice, Locale locale, int quality, int latency, boolean network) {
            this.name = name;
            this.pkg = pkg;
            this.voice = voice;
            this.locale = locale;
            this.quality = quality;
            this.latency = latency;
            this.network = network;
        }
    }

    private static volatile Map<String, Entry> entries;
    private static volatile List<Voice> voices;
    private static volatile boolean refreshing;
    private static volatile long updatedAt;

    private static SharedPreferences store(Context c) {
        return Prefs.storage(c).getSharedPreferences("vozes_motores", Context.MODE_PRIVATE);
    }

    /** Lista guardada, como vozes para o Android (sem esperar nenhum motor). */
    static List<Voice> voices(Context c) {
        load(c);
        List<Voice> v = voices;
        return v == null ? Collections.<Voice>emptyList() : v;
    }

    /** Voz de outro motor com este nome, ou null. */
    static Entry find(Context c, String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        load(c);
        Map<String, Entry> m = entries;
        return m == null ? null : m.get(name);
    }

    static int count(Context c) {
        load(c);
        Map<String, Entry> m = entries;
        return m == null ? 0 : m.size();
    }

    static long updatedAt(Context c) {
        load(c);
        return updatedAt;
    }

    private static synchronized void load(Context c) {
        if (entries != null) {
            return;
        }
        Map<String, Entry> m = new LinkedHashMap<>();
        try {
            SharedPreferences p = store(c);
            updatedAt = p.getLong("atualizado", 0);
            JSONArray a = new JSONArray(p.getString("vozes", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Entry e = new Entry(o.getString("n"), o.getString("p"), o.getString("v"),
                        Locale.forLanguageTag(o.optString("l", "")), o.optInt("q", Voice.QUALITY_NORMAL),
                        o.optInt("t", Voice.LATENCY_NORMAL), o.optBoolean("r", false));
                m.put(e.name, e);
            }
        } catch (Exception ignored) {
            // Lista estragada: começa vazia e a próxima atualização refaz.
        }
        publish(m);
    }

    private static void publish(Map<String, Entry> m) {
        List<Voice> v = new ArrayList<>();
        for (Entry e : m.values()) {
            v.add(new Voice(e.name, e.locale, e.quality, e.latency, e.network, Collections.<String>emptySet()));
        }
        voices = v;
        entries = m;
    }

    /** Atualiza a lista em segundo plano (uma atualização por vez). */
    static void refreshLater(final Context ctx, long delayMs) {
        final Context c = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                if (refreshing) {
                    return;
                }
                refreshing = true;
                Thread t = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            refresh(c);
                        } catch (Exception ignored) {
                        } finally {
                            refreshing = false;
                        }
                    }
                }, "vozes-dos-motores");
                t.setDaemon(true);
                t.start();
            }
        }, delayMs);
    }

    /** Pergunta a cada motor instalado (menos o próprio Gerenciador) quais vozes ele tem. */
    private static void refresh(Context c) {
        PackageManager pm = c.getPackageManager();
        Map<String, Entry> m = new LinkedHashMap<>();
        Map<String, Integer> labelUse = new HashMap<>();
        List<ResolveInfo> engines = EngineClient.installedEngines(c);
        // Primeiro conta os nomes dos motores, para separar dois motores com o mesmo nome.
        List<String> labels = new ArrayList<>();
        for (ResolveInfo r : engines) {
            String label = String.valueOf(r.loadLabel(pm)).trim();
            labels.add(label);
            Integer n = labelUse.get(label);
            labelUse.put(label, n == null ? 1 : n + 1);
        }
        for (int i = 0; i < engines.size(); i++) {
            String pkg = engines.get(i).serviceInfo.packageName;
            String label = labels.get(i);
            if (labelUse.get(label) > 1) {
                label = label + " (" + pkg + ")";
            }
            List<Voice> found = askEngine(c, pkg);
            if (found.isEmpty()) {
                // O motor não respondeu a tempo: mantém as vozes dele que já estavam guardadas.
                load(c);
                Map<String, Entry> old = entries;
                if (old != null) {
                    for (Entry e : old.values()) {
                        if (e.pkg.equals(pkg)) {
                            m.put(e.name, e);
                        }
                    }
                }
                continue;
            }
            for (Voice v : found) {
                Locale l = v.getLocale() != null ? v.getLocale() : Locale.getDefault();
                String name = label + ": " + v.getName();
                m.put(name, new Entry(name, pkg, v.getName(), l, v.getQuality(), v.getLatency(),
                        v.isNetworkConnectionRequired()));
            }
        }
        JSONArray a = new JSONArray();
        try {
            for (Entry e : m.values()) {
                a.put(new JSONObject().put("n", e.name).put("p", e.pkg).put("v", e.voice)
                        .put("l", e.locale.toLanguageTag()).put("q", e.quality).put("t", e.latency)
                        .put("r", e.network));
            }
        } catch (Exception ignored) {
        }
        long now = System.currentTimeMillis();
        store(c).edit().putString("vozes", a.toString()).putLong("atualizado", now).apply();
        updatedAt = now;
        publish(m);
    }

    /** Vozes instaladas de um motor (espera no máximo 8 s pela abertura dele). */
    private static List<Voice> askEngine(final Context c, final String pkg) {
        final List<Voice> out = new ArrayList<>();
        final CountDownLatch ready = new CountDownLatch(1);
        final int[] status = {TextToSpeech.ERROR};
        final TextToSpeech[] holder = new TextToSpeech[1];
        // O TextToSpeech precisa ser criado na thread principal.
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                try {
                    holder[0] = new TextToSpeech(c, new TextToSpeech.OnInitListener() {
                        @Override
                        public void onInit(int s) {
                            status[0] = s;
                            ready.countDown();
                        }
                    }, pkg);
                } catch (Exception e) {
                    ready.countDown();
                }
            }
        });
        try {
            ready.await(8, TimeUnit.SECONDS);
            TextToSpeech t = holder[0];
            if (t != null && status[0] == TextToSpeech.SUCCESS) {
                Set<Voice> set = t.getVoices();
                if (set != null) {
                    for (Voice v : set) {
                        Set<String> f = v.getFeatures();
                        if (f != null && f.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)) {
                            continue; // voz que ainda precisa ser baixada no motor
                        }
                        out.add(v);
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            final TextToSpeech t = holder[0];
            if (t != null) {
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            t.shutdown();
                        } catch (Exception ignored) {
                        }
                    }
                });
            }
        }
        // Ordem estável: por idioma e depois por nome.
        Collections.sort(out, new java.util.Comparator<Voice>() {
            @Override
            public int compare(Voice a, Voice b) {
                String la = a.getLocale() == null ? "" : a.getLocale().toLanguageTag();
                String lb = b.getLocale() == null ? "" : b.getLocale().toLanguageTag();
                int r = la.compareTo(lb);
                return r != 0 ? r : a.getName().compareTo(b.getName());
            }
        });
        return out;
    }
}
