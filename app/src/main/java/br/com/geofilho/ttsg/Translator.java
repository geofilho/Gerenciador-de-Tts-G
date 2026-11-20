package br.com.geofilho.ttsg;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Tradução automática de voz, pela internet (tradutor do Google, sem chave).
 * Para a fala continuar fluida:
 * - texto que já parece estar no idioma de destino nem é enviado;
 * - traduções ficam guardadas na memória (nomes de botões repetem muito);
 * - tempo limite curto: se a internet falhar, lê o texto original e só tenta
 *   de novo depois de alguns segundos.
 */
final class Translator {

    private static final String URL_GTX =
            "https://translate.googleapis.com/translate_a/single?client=gtx&dt=t&sl=auto&tl=";
    private static final int MAX_TEXT = 4500;
    private static final int CACHE_SIZE = 400;
    private static final long BACKOFF_MS = 15000;
    private static final String SAME = "\u0000";

    /** Idiomas oferecidos (códigos do tradutor). */
    static final String[] LANGUAGES = {
            "pt", "en", "es", "fr", "de", "it", "nl", "ru", "uk", "pl", "tr", "ar", "he", "hi",
            "ja", "ko", "zh-CN", "zh-TW", "id", "vi", "th", "el", "sv", "da", "no", "fi", "cs",
            "ro", "hu", "ca", "bg", "hr", "sk", "sr", "fa", "ms", "tl", "sw", "af", "la", "eo"
    };

    private static final Map<String, String> CACHE = new LinkedHashMap<String, String>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> e) {
            return size() > CACHE_SIZE;
        }
    };
    private static volatile long failedUntil;

    private Translator() {
    }

    /** Idioma de destino padrão: o idioma do aparelho. */
    static String deviceLanguage() {
        Locale l = Locale.getDefault();
        String lang = l.getLanguage();
        if ("zh".equals(lang)) {
            return "TW".equals(l.getCountry()) || "HK".equals(l.getCountry()) ? "zh-TW" : "zh-CN";
        }
        if ("iw".equals(lang)) {
            return "he";
        }
        if ("in".equals(lang)) {
            return "id";
        }
        return lang;
    }

    static String displayName(String code) {
        if ("auto".equals(code)) {
            return "Automático";
        }
        String name = Locale.forLanguageTag(code).getDisplayName(Locale.getDefault());
        return name.isEmpty() ? code : name.substring(0, 1).toUpperCase(Locale.getDefault()) + name.substring(1);
    }

    /** Usado pelo motor a cada fala: traduz se a função estiver ligada e for preciso. */
    static String maybeTranslate(Context c, String text) {
        SharedPreferences p = Prefs.get(c);
        if (!Prefs.translateOn(p)) {
            return text;
        }
        return translate(text, Prefs.translateSource(p), Prefs.translateTarget(p));
    }

    /** Traduz {@code text} de {@code src} ("auto" = detectar) para {@code dst}; devolve o original se não precisar ou falhar. */
    static String translate(String text, String src, String dst) {
        if (text == null || letters(text) < 2 || text.length() > MAX_TEXT) {
            return text;
        }
        // O leitor de tela costuma terminar com o tipo e o estado ("Settings, Botão", "Wi-Fi, Interruptor,
        // Ativado"): traduz só o conteúdo e devolve o final como estava.
        java.util.regex.Matcher m = TRAILING_ROLES.matcher(text);
        if (m.find() && m.start() > 0) {
            String head = text.substring(0, m.start());
            return translateText(head, src, dst) + text.substring(m.start());
        }
        return translateText(text, src, dst);
    }

    private static String translateText(String text, String src, String dst) {
        if (letters(text) < 2) {
            return text;
        }
        // O leitor de tela junta "Botão", "Ativado" etc. ao texto: não contam na verificação.
        String check = stripRoles(text);
        if (letters(check) < 2) {
            return text;
        }
        if (looksLike(check, dst)) {
            return text; // já está no idioma de destino: nem consulta a internet
        }
        String key = src + ">" + dst + ":" + text;
        synchronized (CACHE) {
            String hit = CACHE.get(key);
            if (hit != null) {
                return SAME.equals(hit) ? text : hit;
            }
        }
        if (System.currentTimeMillis() < failedUntil) {
            return text;
        }
        String[] result = request(text, dst);
        if (result == null) {
            failedUntil = System.currentTimeMillis() + BACKOFF_MS;
            return text;
        }
        String detected = base(result[1]);
        boolean keep = detected.equals(base(dst)) || (!"auto".equals(src) && !detected.isEmpty()
                && !detected.equals(base(src)));
        String out = keep || result[0].trim().isEmpty() ? text : result[0];
        synchronized (CACHE) {
            CACHE.put(key, out == text ? SAME : out);
        }
        return out;
    }

    /** Consulta o tradutor. Retorna {texto traduzido, idioma detectado} ou null. */
    private static String[] request(String text, String dst) {
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL(URL_GTX + URLEncoder.encode(dst, "UTF-8")).openConnection();
            con.setConnectTimeout(2500);
            con.setReadTimeout(3500);
            con.setRequestMethod("POST");
            con.setDoOutput(true);
            con.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            byte[] body = ("q=" + URLEncoder.encode(text, "UTF-8")).getBytes("UTF-8");
            OutputStream os = con.getOutputStream();
            os.write(body);
            os.close();
            if (con.getResponseCode() != 200) {
                return null;
            }
            InputStream in = con.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            in.close();
            JSONArray root = new JSONArray(bos.toString("UTF-8"));
            StringBuilder sb = new StringBuilder();
            JSONArray parts = root.optJSONArray(0);
            if (parts != null) {
                for (int i = 0; i < parts.length(); i++) {
                    JSONArray part = parts.optJSONArray(i);
                    if (part != null && !part.isNull(0)) {
                        sb.append(part.optString(0));
                    }
                }
            }
            return new String[]{sb.toString(), root.optString(2, "")};
        } catch (Exception e) {
            return null;
        } finally {
            if (con != null) {
                con.disconnect();
            }
        }
    }

    private static String base(String code) {
        if (code == null) {
            return "";
        }
        String c = code.toLowerCase(Locale.ROOT);
        if (c.startsWith("zh")) {
            return c; // chinês simplificado e tradicional são diferentes
        }
        int dash = c.indexOf('-');
        return dash > 0 ? c.substring(0, dash) : c;
    }

    private static int letters(String s) {
        int n = 0;
        for (int i = 0; i < s.length() && n < 2; i++) {
            if (Character.isLetter(s.charAt(i))) {
                n++;
            }
        }
        return n;
    }

    /** Tipos e estados que o leitor de tela fala junto (em português). */
    private static final java.util.regex.Pattern ROLES = java.util.regex.Pattern.compile(
            "(?iu)\\b(botão|interruptor|ativado|desativado|não marcado|marcado|caixa de seleção|link|título|"
                    + "campo de edição|controle deslizante|guia|lista|selecionado|imagem|janela|cabeçalho|"
                    + "menu|toque duas vezes para ativar|toque duas vezes e mantenha pressionado|"
                    + "lista suspensa|em lista|item|de)\\b");

    /** Tipos e estados no fim do texto, com as vírgulas e pontos entre eles. */
    private static final java.util.regex.Pattern TRAILING_ROLES = java.util.regex.Pattern.compile(
            "(?iu)(?:[,.;]?\\s*\\b(?:botão|interruptor|ativado|desativado|não marcado|marcado|caixa de seleção|"
                    + "link|título(?: \\d)?|campo de edição|controle deslizante|guia|selecionado|imagem|janela|"
                    + "cabeçalho|menu|lista suspensa|em lista|toque duas vezes para ativar|"
                    + "toque duas vezes e mantenha pressionado)\\b\\s*)+[,.;]?\\s*$");

    private static String stripRoles(String text) {
        return ROLES.matcher(text).replaceAll(" ");
    }

    // ----- verificação rápida no aparelho -----

    private static final Map<String, Set<String>> WORDS = new HashMap<>();
    private static final Map<String, String> MARKS = new HashMap<>();

    static {
        words("pt", "de a o que e do da em um para é com não uma os no se na por mais as dos como mas foi ao "
                + "ele das tem à seu sua ou ser quando muito há nos já está eu também só pelo pela até isso ela "
                + "entre era depois sem mesmo aos ter seus quem nas me esse eles estão você tinha foram essa num "
                + "nem suas meu às minha numa pelos elas qual será nós tenho lhe deles essas esses pelas este "
                + "dele vocês meus minhas nosso nossa dela esta estes estas aquele aquela isto então aqui");
        words("en", "the of and to a in is it you that he was for on are with as i his they be at one have "
                + "this from or had by not but what all were we when your can said there an each which she do "
                + "how their if will up other about out many then them these so some her would make like him "
                + "into time has look two more go see no way could people my than first been who its now get "
                + "button settings off on new back open close search");
        words("es", "de la que el en y a los se del las un por con no una su para es al lo como más pero sus "
                + "le ya o este sí porque esta entre cuando muy sin sobre también me hasta hay donde quien desde "
                + "todo nos durante todos uno les ni otros ese eso ellos esto antes algunos qué unos yo otro "
                + "otra él tanto esa estos mucho nada muchos cual poco ella estar estas algo nosotros");
        words("fr", "de la le et les des en un du une que est pour qui dans par sur pas plus au ne se ce il "
                + "sont avec ou son elle nous vous je tu mais comme leur aux cette ses été être avoir");
        words("de", "der die und in den von zu das mit sich des auf für ist im dem nicht ein eine als auch es "
                + "an werden aus er hat dass sie nach wird bei einer um am sind noch wie einem über einen so "
                + "zum war haben nur oder aber vor zur bis mehr durch man");
        words("it", "di e il la che in a per un è del non una sono le con si da i gli al come ma della lo più "
                + "se ha anche ci questo nel alla dei delle");
        MARKS.put("pt", "ãõ");
        MARKS.put("es", "ñ¿¡");
        MARKS.put("de", "ß");
        MARKS.put("fr", "œèëîïù"); // sem ê, û: comuns em português
    }

    private static void words(String lang, String list) {
        WORDS.put(lang, new HashSet<>(java.util.Arrays.asList(list.split(" "))));
    }

    /** O texto parece estar no idioma {@code lang}? (Só para pular a consulta; na dúvida, responde não.) */
    static boolean looksLike(String text, String lang) {
        String l = base(lang);
        Set<String> mine = WORDS.get(l);
        if (mine == null) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        Map<String, Integer> score = new HashMap<>();
        int total = 0;
        for (String w : lower.split("[^\\p{L}]+")) {
            if (w.isEmpty()) {
                continue;
            }
            total++;
            for (Map.Entry<String, Set<String>> e : WORDS.entrySet()) {
                if (e.getValue().contains(w)) {
                    Integer s = score.get(e.getKey());
                    score.put(e.getKey(), s == null ? 1 : s + 1);
                }
            }
        }
        for (Map.Entry<String, String> e : MARKS.entrySet()) {
            for (int i = 0; i < e.getValue().length(); i++) {
                if (lower.indexOf(e.getValue().charAt(i)) >= 0) {
                    Integer s = score.get(e.getKey());
                    score.put(e.getKey(), (s == null ? 0 : s) + 2);
                }
            }
        }
        Integer own = score.get(l);
        if (own == null || own == 0) {
            return false;
        }
        for (Map.Entry<String, Integer> e : score.entrySet()) {
            if (!e.getKey().equals(l) && e.getValue() >= own) {
                return false; // empate ou outro idioma na frente: na dúvida, consulta
            }
        }
        return own >= 2 || (total <= 3 && own >= 1);
    }
}
