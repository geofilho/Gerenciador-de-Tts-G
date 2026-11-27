package br.com.geofilho.ttsg;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dicionário de pronúncia no estilo do Eloquence: aceita qualquer palavra ou
 * frase, inclusive com símbolos e pontuação, sem diferenciar maiúsculas.
 * Quando a entrada começa ou termina com letra/número, só troca a palavra
 * inteira (assim "vc" não mexe em "avcs").
 */
final class Dictionary {

    static final class Entry {
        final String word;
        final String replacement;

        Entry(String word, String replacement) {
            this.word = word;
            this.replacement = replacement;
        }
    }

    private static Dictionary instance;

    private final File file;
    private final List<Entry> entries = new ArrayList<>();
    private volatile Pattern pattern;
    private volatile Map<String, String> lookup = new HashMap<>();

    static synchronized Dictionary get(Context c) {
        if (instance == null) {
            instance = new Dictionary(new File(Prefs.storage(c).getFilesDir(), "dicionario.json"));
        }
        return instance;
    }

    private Dictionary(File file) {
        this.file = file;
        try {
            if (file.exists()) {
                parseInto(readAll(new FileInputStream(file)), entries);
            }
        } catch (IOException | JSONException ignored) {
            // Arquivo corrompido: começa vazio.
        }
        rebuild();
    }

    synchronized List<Entry> list() {
        return new ArrayList<>(entries);
    }

    /** Adiciona ou substitui (mesma palavra, sem diferenciar maiúsculas). */
    synchronized void put(String word, String replacement) {
        putNoSave(word, replacement);
        saveAndRebuild();
    }

    /** Edita trocando a palavra antiga pela nova. */
    synchronized void replace(String oldWord, String word, String replacement) {
        removeNoSave(oldWord);
        putNoSave(word, replacement);
        saveAndRebuild();
    }

    synchronized void remove(String word) {
        removeNoSave(word);
        saveAndRebuild();
    }

    private void putNoSave(String word, String replacement) {
        word = word.trim();
        if (word.isEmpty()) {
            return;
        }
        removeNoSave(word);
        entries.add(new Entry(word, replacement == null ? "" : replacement));
    }

    private void removeNoSave(String word) {
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (entries.get(i).word.equalsIgnoreCase(word.trim())) {
                entries.remove(i);
            }
        }
    }

    /** Aplica o dicionário ao texto. Seguro para chamar de qualquer thread. */
    String apply(String text) {
        Pattern p = pattern;
        if (p == null || text == null || text.isEmpty()) {
            return text;
        }
        Matcher m = p.matcher(text);
        if (!m.find()) {
            return text;
        }
        Map<String, String> map = lookup;
        StringBuffer out = new StringBuffer(text.length() + 16);
        do {
            String found = m.group();
            String rep = map.get(found.toLowerCase(Locale.ROOT));
            if (rep == null) {
                rep = found;
            }
            m.appendReplacement(out, Matcher.quoteReplacement(rep));
        } while (m.find());
        m.appendTail(out);
        return out.toString();
    }

    // ----- importar / exportar -----

    synchronized void exportTo(OutputStream os) throws IOException {
        try {
            os.write(toJson().toString(2).getBytes("UTF-8"));
        } catch (JSONException e) {
            throw new IOException(e);
        } finally {
            os.close();
        }
    }

    /** Importa juntando com o que já existe. Retorna quantas entradas foram lidas. */
    synchronized int importFrom(InputStream is) throws IOException {
        String text = readAll(is);
        List<Entry> read = new ArrayList<>();
        try {
            parseInto(text, read);
        } catch (JSONException notJson) {
            parseLines(text, read);
        }
        for (Entry e : read) {
            putNoSave(e.word, e.replacement);
        }
        saveAndRebuild();
        return read.size();
    }

    /** Dicionário inteiro em JSON (para o backup). */
    synchronized JSONObject exportJson() throws JSONException {
        return toJson();
    }

    /** Troca o dicionário inteiro pelo do backup. */
    synchronized void replaceAll(String json) throws JSONException {
        List<Entry> read = new ArrayList<>();
        parseInto(json, read);
        entries.clear();
        for (Entry e : read) {
            putNoSave(e.word, e.replacement);
        }
        saveAndRebuild();
    }

    // ----- interno -----

    private void saveAndRebuild() {
        sort();
        rebuild();
        try {
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(toJson().toString().getBytes("UTF-8"));
            fos.close();
        } catch (IOException | JSONException ignored) {
        }
    }

    private void sort() {
        final Collator col = Collator.getInstance(new Locale("pt", "BR"));
        col.setStrength(Collator.SECONDARY);
        Collections.sort(entries, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                return col.compare(a.word, b.word);
            }
        });
    }

    /** Monta uma única expressão regular (mais longas primeiro) para trocar tudo numa passada. */
    private void rebuild() {
        if (entries.isEmpty()) {
            pattern = null;
            lookup = new HashMap<>();
            return;
        }
        List<Entry> byLength = new ArrayList<>(entries);
        Collections.sort(byLength, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                return b.word.length() - a.word.length();
            }
        });
        Map<String, String> map = new HashMap<>();
        StringBuilder re = new StringBuilder();
        for (Entry e : byLength) {
            if (re.length() > 0) {
                re.append('|');
            }
            String w = e.word;
            if (Character.isLetterOrDigit(w.codePointAt(0))) {
                re.append("(?<![\\p{L}\\p{N}])");
            }
            re.append(Pattern.quote(w));
            if (Character.isLetterOrDigit(w.codePointBefore(w.length()))) {
                re.append("(?![\\p{L}\\p{N}])");
            }
            map.put(w.toLowerCase(Locale.ROOT), e.replacement);
        }
        lookup = map;
        pattern = Pattern.compile(re.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    private JSONObject toJson() throws JSONException {
        JSONArray arr = new JSONArray();
        for (Entry e : entries) {
            JSONObject o = new JSONObject();
            o.put("palavra", e.word);
            o.put("substituicao", e.replacement);
            arr.put(o);
        }
        JSONObject root = new JSONObject();
        root.put("app", "Gerenciador de TTS G");
        root.put("versao", 1);
        root.put("entradas", arr);
        return root;
    }

    private static void parseInto(String json, List<Entry> out) throws JSONException {
        String t = json.trim();
        JSONArray arr = t.startsWith("[") ? new JSONArray(t) : new JSONObject(t).getJSONArray("entradas");
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            String w = o.optString("palavra", "");
            if (!w.trim().isEmpty()) {
                out.add(new Entry(w, o.optString("substituicao", "")));
            }
        }
    }

    /** Texto simples: uma entrada por linha, "palavra=substituição" ou separado por TAB. */
    private static void parseLines(String text, List<Entry> out) throws IOException {
        BufferedReader br = new BufferedReader(new StringReader(text));
        String line;
        while ((line = br.readLine()) != null) {
            int sep = line.indexOf('\t');
            if (sep < 0) {
                sep = line.lastIndexOf('=');
            }
            if (sep > 0) {
                String w = line.substring(0, sep);
                if (!w.trim().isEmpty()) {
                    out.add(new Entry(w, line.substring(sep + 1)));
                }
            }
        }
    }

    private static String readAll(InputStream is) throws IOException {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            String s = bos.toString("UTF-8");
            return s.startsWith("﻿") ? s.substring(1) : s;
        } finally {
            is.close();
        }
    }

}
