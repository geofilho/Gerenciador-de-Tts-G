package br.com.geofilho.ttsg;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Dicionário de tradução que vem dentro do aplicativo (res/raw/traducao_base.txt):
 * palavras e frases curtas comuns em aplicativos, do inglês para o português.
 * É consultado antes da internet: essas traduções saem na hora, sem depender de
 * nenhum serviço. O usuário não precisa mexer nele.
 */
final class BaseDictionary {

    private static volatile Map<String, String> words;

    private BaseDictionary() {
    }

    /** Carrega o arquivo (uma vez; é pequeno). */
    static void load(Context c) {
        if (words != null || c == null) {
            return;
        }
        synchronized (BaseDictionary.class) {
            if (words != null) {
                return;
            }
            Map<String, String> m = new HashMap<>(2048);
            try {
                BufferedReader r = new BufferedReader(new InputStreamReader(
                        c.getResources().openRawResource(R.raw.traducao_base), "UTF-8"));
                String line;
                while ((line = r.readLine()) != null) {
                    int tab = line.indexOf('\t');
                    if (tab > 0) {
                        m.put(normalize(line.substring(0, tab)), line.substring(tab + 1).trim());
                    }
                }
                r.close();
            } catch (Exception ignored) {
                // Sem o arquivo, segue só com as traduções guardadas e a internet.
            }
            words = m;
        }
    }

    /** Tradução do dicionário, ou null se o texto não estiver nele. */
    static String lookup(Context c, String text) {
        load(c);
        Map<String, String> m = words;
        if (m == null || m.isEmpty() || text == null) {
            return null;
        }
        String t = text.trim();
        if (t.isEmpty() || t.length() > 120) {
            return null;
        }
        String hit = m.get(normalize(t));
        String end = "";
        if (hit == null) {
            // Sem a pontuação final: "Settings." ou "Search…" também valem.
            int cut = t.length();
            while (cut > 0 && ".!?:;…".indexOf(t.charAt(cut - 1)) >= 0) {
                cut--;
            }
            if (cut == t.length() || cut == 0) {
                return null;
            }
            hit = m.get(normalize(t.substring(0, cut)));
            if (hit == null) {
                return null;
            }
            end = t.substring(cut);
        }
        return matchCase(t, hit) + end;
    }

    private static String normalize(String s) {
        return s.trim().replace('’', '\'').replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /** Se o original começa com maiúscula, a tradução também começa. */
    private static String matchCase(String original, String translated) {
        if (translated.isEmpty()) {
            return translated;
        }
        int i = 0;
        while (i < original.length() && !Character.isLetter(original.charAt(i))) {
            i++;
        }
        if (i >= original.length() || !Character.isUpperCase(original.charAt(i))) {
            return translated;
        }
        // Maiúscula no começo e depois de cada ponto final ("Algo deu errado. Tente novamente.").
        StringBuilder b = new StringBuilder(translated);
        boolean up = true;
        for (int k = 0; k < b.length(); k++) {
            char ch = b.charAt(k);
            if (up && Character.isLetter(ch)) {
                b.setCharAt(k, Character.toUpperCase(ch));
                up = false;
            } else if (ch == '.' || ch == '!' || ch == '?') {
                up = true;
            }
        }
        return b.toString();
    }
}
