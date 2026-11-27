package br.com.geofilho.ttsg;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Backup das configurações (vozes, leitura de números, tradução, chaves), do dicionário e dos
 * nomes dados aos botões sem etiqueta, num arquivo JSON, e restauração dele.
 * Não leva o diagnóstico nem as traduções guardadas da internet (elas voltam sozinhas com o uso).
 */
final class Backup {

    static final String FILE_NAME = "gerenciador_tts_g_backup.json";
    static final String APP = "Gerenciador de TTS G";
    /** Arquivos de preferências que vão no backup. */
    private static final String[] PREFS = {"config", "nomes_dos_botoes"};

    private Backup() {
    }

    /** Grava o backup. Devolve {palavras do dicionário, botões nomeados}. */
    static int[] write(Context c, OutputStream os) throws Exception {
        JSONObject prefs = new JSONObject();
        for (String name : PREFS) {
            prefs.put(name, dump(Prefs.storage(c).getSharedPreferences(name, Context.MODE_PRIVATE)));
        }
        JSONObject dict = Dictionary.get(c).exportJson();
        String version = "";
        try {
            version = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        JSONObject root = new JSONObject();
        root.put("app", APP).put("versao", version).put("configuracoes", prefs).put("dicionario", dict);
        os.write(root.toString(2).getBytes("UTF-8"));
        os.close();
        return new int[]{Dictionary.get(c).list().size(), Unlabeled.namedCount(c)};
    }

    private static JSONObject dump(SharedPreferences p) throws Exception {
        JSONObject data = new JSONObject();
        for (Map.Entry<String, ?> e : p.getAll().entrySet()) {
            Object v = e.getValue();
            JSONObject item = new JSONObject();
            if (v instanceof String) {
                item.put("tipo", "texto").put("valor", v);
            } else if (v instanceof Boolean) {
                item.put("tipo", "sim_nao").put("valor", v);
            } else if (v instanceof Integer) {
                item.put("tipo", "inteiro").put("valor", v);
            } else if (v instanceof Long) {
                item.put("tipo", "longo").put("valor", v);
            } else if (v instanceof Float) {
                item.put("tipo", "decimal").put("valor", ((Float) v).doubleValue());
            } else if (v instanceof Set) {
                JSONArray a = new JSONArray();
                for (Object o : (Set<?>) v) {
                    a.put(String.valueOf(o));
                }
                item.put("tipo", "lista").put("valor", a);
            } else {
                continue;
            }
            data.put(e.getKey(), item);
        }
        return data;
    }

    static String readAll(InputStream is) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        is.close();
        return bos.toString("UTF-8");
    }

    /** O texto é um backup do Gerenciador de TTS G? */
    static boolean isBackup(String text) {
        try {
            return APP.equals(new JSONObject(text).optString("app")) && new JSONObject(text).has("configuracoes");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Substitui as configurações, o dicionário e os nomes dos botões pelos do backup. Lança exceção
     * se não for um backup do Gerenciador de TTS G (aí nada é mudado).
     */
    static void restore(Context c, String text) throws Exception {
        JSONObject root = new JSONObject(text);
        if (!APP.equals(root.optString("app")) || !root.has("configuracoes")) {
            throw new IllegalArgumentException("não é um backup do Gerenciador de TTS G");
        }
        JSONObject prefs = root.getJSONObject("configuracoes");
        for (String name : PREFS) {
            JSONObject data = prefs.optJSONObject(name);
            if (data == null) {
                continue;
            }
            SharedPreferences.Editor ed = Prefs.storage(c).getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear();
            Iterator<String> it = data.keys();
            while (it.hasNext()) {
                String k = it.next();
                JSONObject item = data.optJSONObject(k);
                if (item == null) {
                    continue;
                }
                String t = item.optString("tipo");
                if ("texto".equals(t)) {
                    ed.putString(k, item.optString("valor"));
                } else if ("sim_nao".equals(t)) {
                    ed.putBoolean(k, item.optBoolean("valor"));
                } else if ("inteiro".equals(t)) {
                    ed.putInt(k, item.optInt("valor"));
                } else if ("longo".equals(t)) {
                    ed.putLong(k, item.optLong("valor"));
                } else if ("decimal".equals(t)) {
                    ed.putFloat(k, (float) item.optDouble("valor"));
                } else if ("lista".equals(t)) {
                    JSONArray a = item.optJSONArray("valor");
                    Set<String> set = new HashSet<>();
                    for (int i = 0; a != null && i < a.length(); i++) {
                        set.add(a.optString(i));
                    }
                    ed.putStringSet(k, set);
                }
            }
            ed.commit();
        }
        JSONObject dict = root.optJSONObject("dicionario");
        if (dict != null) {
            Dictionary.get(c).replaceAll(dict.toString());
        }
        Toggles.refresh(c);
    }
}
