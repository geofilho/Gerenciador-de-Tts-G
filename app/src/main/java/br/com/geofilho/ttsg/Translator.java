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
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Tradução automática de voz, pela internet (tradutor do Google, sem chave).
 * Para a fala continuar fluida:
 * - texto que já parece estar no idioma de destino nem é enviado;
 * - traduções ficam guardadas na memória (nomes de botões repetem muito);
 * - tempo limite curto: se a internet falhar, lê o texto original e só tenta
 *   de novo depois de alguns segundos.
 */
final class Translator {

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

    /** Traduções em segundo plano: o leitor de tela não fica preso esperando a internet. */
    private static final ExecutorService EXEC = Executors.newFixedThreadPool(2, new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "traducao");
            t.setDaemon(true);
            return t;
        }
    });
    /** Tempo máximo que uma fala espera pela tradução; depois, lê o original. */
    private static final long WAIT_MS = 2500;
    private static volatile long lastNetwork;

    /** Usado pelo motor a cada fala: traduz se a função estiver ligada e for preciso. */
    static String maybeTranslate(Context c, String text, EngineClient.StopFlag stop) {
        SharedPreferences p = Prefs.get(c);
        if (!Prefs.translateOn(p)) {
            return text;
        }
        lastError = "";
        String out = translate(text, Prefs.translateSource(p), Prefs.translateTarget(p), false, stop);
        String err = lastError;
        if (!err.isEmpty()) {
            Diagnostics.note(c, "a tradução falhou: " + err + ".");
        }
        return out;
    }

    /** Traduz {@code text} de {@code src} ("auto" = detectar) para {@code dst}; devolve o original se não precisar ou falhar. */
    static String translate(String text, String src, String dst) {
        return translate(text, src, dst, false, null);
    }

    /**
     * {@code force}: usado pelo teste, tenta mesmo logo depois de uma falha.
     * {@code stop}: se o leitor de tela pular para outra fala, para de esperar
     * (a tradução termina em segundo plano e fica guardada).
     */
    static String translate(String text, String src, String dst, boolean force, EngineClient.StopFlag stop) {
        if (text == null || letters(text) < 2 || text.length() > MAX_TEXT) {
            return text;
        }
        // O leitor de tela costuma terminar com o tipo e o estado ("Settings, Botão", "Wi-Fi, Interruptor,
        // Ativado"): traduz só o conteúdo e devolve o final como estava.
        java.util.regex.Matcher m = TRAILING_ROLES.matcher(text);
        if (m.find() && m.start() > 0) {
            String head = text.substring(0, m.start());
            return translateText(head, src, dst, force, stop) + text.substring(m.start());
        }
        return translateText(text, src, dst, force, stop);
    }

    private static String translateText(final String text, final String src, final String dst, boolean force,
                                        EngineClient.StopFlag stop) {
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
        final String key = src + ">" + dst + ":" + text;
        synchronized (CACHE) {
            String hit = CACHE.get(key);
            if (hit != null) {
                return SAME.equals(hit) ? text : hit;
            }
        }
        if (System.currentTimeMillis() < failedUntil && !force) {
            return text;
        }
        Future<String> f = EXEC.submit(new Callable<String>() {
            @Override
            public String call() {
                return fetch(text, src, dst, key);
            }
        });
        // A fala espera no máximo 2,5 s; o teste da página espera até 8 s, para mostrar o motivo de uma falha.
        long end = System.currentTimeMillis() + (force ? 8000 : WAIT_MS);
        while (true) {
            try {
                String out = f.get(30, TimeUnit.MILLISECONDS);
                return out == null ? text : out;
            } catch (TimeoutException e) {
                if (stop != null && stop.stopped()) {
                    return text; // segue sem esperar; a tradução fica guardada quando chegar
                }
                if (System.currentTimeMillis() > end) {
                    if (force && lastError.isEmpty()) {
                        lastError = "o tradutor demorou demais para responder";
                    }
                    return text;
                }
            } catch (Exception e) {
                return text;
            }
        }
    }

    /** Consulta e guarda na memória. Retorna o texto a falar ou null se falhou. */
    private static String fetch(String text, String src, String dst, String key) {
        String[] result = request(text, src, dst);
        if (result == null) {
            failedUntil = System.currentTimeMillis() + BACKOFF_MS;
            return null;
        }
        String detected = base(result[1]);
        boolean keep = detected.equals(base(dst)) || (!"auto".equals(src) && !detected.isEmpty()
                && !detected.equals(base(src))) || result[0].trim().equalsIgnoreCase(text.trim());
        String out = keep || result[0].trim().isEmpty() ? text : result[0];
        synchronized (CACHE) {
            CACHE.put(key, out == text ? SAME : out);
        }
        return out;
    }

    /** Deixa a conexão com o tradutor pronta (sem esperar), para a primeira tradução sair rápido. */
    static void warm(Context c) {
        if (!Prefs.translateOn(Prefs.get(c)) || System.currentTimeMillis() - lastNetwork < 60000) {
            return;
        }
        final String dst = Prefs.translateTarget(Prefs.get(c));
        EXEC.submit(new Runnable() {
            @Override
            public void run() {
                request("hello", "en", dst);
            }
        });
    }

    /** Motivo da última falha, para o teste e o diagnóstico. */
    static volatile String lastError = "";

    private static final String BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36";
    /**
     * Endereços do tradutor do Google, em ordem. Cada um tem o seu limite de uso: quando um
     * responde 429 ("consultas demais"), ele descansa por um tempo crescente e o próximo é usado.
     */
    private static final String[] ENDPOINTS = {
            "https://translate.googleapis.com/translate_a/single?client=gtx&dt=t&sl=auto&tl=",
            "https://translate.googleapis.com/translate_a/t?client=gtx&sl=auto&tl=",
            "https://clients5.google.com/translate_a/t?client=dict-chrome-ex&sl=auto&tl=",
    };
    private static final long[] restUntil = new long[ENDPOINTS.length];
    private static final long[] restFor = new long[ENDPOINTS.length];
    private static final long REST_FIRST_MS = 60000;
    private static final long REST_MAX_MS = 600000;

    /** Só para testes fora do celular: simula um endereço respondendo 429. */
    static volatile int testLimitedEndpoint = -1;

    /** Consulta o tradutor. Retorna {texto traduzido, idioma detectado} ou null. */
    private static String[] request(String text, String src, String dst) {
        long now = System.currentTimeMillis();
        boolean limited = false;
        for (int i = 0; i < ENDPOINTS.length; i++) {
            if (now < restUntil[i]) {
                limited = true;
                continue; // descansando depois de um 429
            }
            // Textos longos não cabem no endereço: vão por POST, que só o primeiro endereço aceita.
            boolean post = text.length() > 1500;
            if (post && i > 0) {
                continue;
            }
            lastCode = 0;
            String[] r = request(i, text, dst, post);
            if (r != null) {
                restFor[i] = 0;
                lastError = "";
                lastNetwork = System.currentTimeMillis();
                return r;
            }
            if (lastCode == 429) {
                // Limite de uso do Google: descansa este endereço (1, 2, 4... até 10 min) e tenta o próximo.
                restFor[i] = restFor[i] == 0 ? REST_FIRST_MS : Math.min(REST_MAX_MS, restFor[i] * 2);
                restUntil[i] = System.currentTimeMillis() + restFor[i];
                limited = true;
                continue;
            }
            if (lastCode == 0) {
                return null; // sem internet ou sem resposta: não adianta tentar os outros agora
            }
        }
        // Reserva: MyMemory, outro serviço, com limite separado (uso gratuito oficial, sem chave).
        String[] r = myMemory(text, src, dst);
        if (r != null) {
            lastError = "";
            lastNetwork = System.currentTimeMillis();
            return r;
        }
        if (limited) {
            lastError = "o tradutor do Google limitou as consultas deste aparelho por alguns minutos (código 429)"
                    + (myMemoryError.isEmpty() ? "" : " e o tradutor reserva também falhou: " + myMemoryError)
                    + "; enquanto isso, os textos são lidos no idioma original";
        }
        return null;
    }

    // ----- MyMemory (reserva) -----

    private static final String MYMEMORY = "https://api.mymemory.translated.net/get?langpair=";
    private static volatile long myMemoryRestUntil;
    private static volatile long myMemoryRestFor;
    private static volatile String myMemoryError = "";
    /** Só para testes fora do celular: o que o MyMemory "respondeu". */
    static volatile String testMyMemoryBody;

    /**
     * Traduz pelo MyMemory. Ele aceita até 500 bytes por consulta e precisa do idioma de origem:
     * usa o escolhido, ou o adivinhado pelo texto, ou deixa ele detectar ("Autodetect").
     */
    private static String[] myMemory(String text, String src, String dst) {
        myMemoryError = "";
        if (System.currentTimeMillis() < myMemoryRestUntil) {
            myMemoryError = "limite do dia esgotado ou descansando";
            return null;
        }
        HttpURLConnection con = null;
        boolean ok = false;
        try {
            if (text.getBytes("UTF-8").length > 500) {
                myMemoryError = "texto longo demais para o tradutor reserva";
                return null;
            }
            String from = src;
            if ("auto".equals(from)) {
                String g = guess(text);
                from = g != null ? g : "Autodetect";
            }
            String body;
            int code;
            if (testMyMemoryBody != null) {
                body = testMyMemoryBody;
                code = 200;
            } else {
                String address = MYMEMORY + URLEncoder.encode(from + "|" + dst, "UTF-8")
                        + "&q=" + URLEncoder.encode(text, "UTF-8");
                con = (HttpURLConnection) new URL(address).openConnection();
                con.setConnectTimeout(2000);
                con.setReadTimeout(2500);
                con.setRequestProperty("User-Agent", BROWSER_UA);
                code = con.getResponseCode();
                InputStream in = code < 400 ? con.getInputStream() : con.getErrorStream();
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                if (in != null) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        bos.write(buf, 0, n);
                    }
                    in.close();
                }
                body = bos.toString("UTF-8");
            }
            org.json.JSONObject json = new org.json.JSONObject(body);
            int status = json.optInt("responseStatus", code);
            org.json.JSONObject data = json.optJSONObject("responseData");
            String translated = data != null ? data.optString("translatedText", "") : "";
            boolean quota = json.optBoolean("quotaFinished", false)
                    || translated.toUpperCase(Locale.ROOT).startsWith("MYMEMORY WARNING") || status == 429;
            if (quota) {
                // Limite diário do uso gratuito: descansa 1 hora (depois tenta de novo).
                myMemoryRestFor = 3600000;
                myMemoryRestUntil = System.currentTimeMillis() + myMemoryRestFor;
                myMemoryError = "limite gratuito do dia esgotado";
                return null;
            }
            if (status != 200 || translated.isEmpty()
                    || translated.toUpperCase(Locale.ROOT).startsWith("INVALID")) {
                myMemoryError = "respondeu com o código " + status;
                return null;
            }
            ok = true;
            String detected = data.optString("detectedLanguage", "");
            if (detected.isEmpty() && !"Autodetect".equals(from)) {
                detected = from;
            }
            return new String[]{translated, detected};
        } catch (java.net.UnknownHostException e) {
            myMemoryError = "não foi encontrado";
            return null;
        } catch (java.net.SocketTimeoutException e) {
            myMemoryError = "demorou demais";
            return null;
        } catch (Exception e) {
            myMemoryError = e.getClass().getSimpleName();
            return null;
        } finally {
            if (con != null && !ok) {
                con.disconnect();
            }
        }
    }

    /** Código HTTP da última consulta (0 = sem resposta). */
    private static volatile int lastCode;

    private static String[] request(int endpoint, String text, String dst, boolean post) {
        HttpURLConnection con = null;
        boolean ok = false;
        try {
            if (endpoint == testLimitedEndpoint || testLimitedEndpoint == 99) {
                lastCode = 429;
                lastError = "o tradutor respondeu com o código 429";
                return null;
            }
            String address = ENDPOINTS[endpoint] + URLEncoder.encode(dst, "UTF-8");
            if (!post) {
                address += "&q=" + URLEncoder.encode(text, "UTF-8");
            }
            con = (HttpURLConnection) new URL(address).openConnection();
            con.setConnectTimeout(2000);
            con.setReadTimeout(2500);
            con.setRequestProperty("User-Agent", BROWSER_UA);
            con.setRequestProperty("Accept", "*/*");
            if (post) {
                con.setRequestMethod("POST");
                con.setDoOutput(true);
                con.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
                byte[] body = ("q=" + URLEncoder.encode(text, "UTF-8")).getBytes("UTF-8");
                OutputStream os = con.getOutputStream();
                os.write(body);
                os.close();
            }
            int code = con.getResponseCode();
            lastCode = code;
            if (code != 200) {
                lastError = "o tradutor respondeu com o código " + code;
                return null;
            }
            InputStream in = con.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            in.close(); // lido até o fim: a conexão fica aberta para a próxima tradução
            String[] r = parse(bos.toString("UTF-8"));
            ok = r != null;
            return r;
        } catch (java.net.UnknownHostException e) {
            lastError = "sem internet ou o endereço do tradutor não foi encontrado";
            return null;
        } catch (java.net.SocketTimeoutException e) {
            lastError = "o tradutor demorou demais para responder";
            return null;
        } catch (Exception e) {
            lastError = e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : "");
            return null;
        } finally {
            // Só fecha a conexão quando deu erro; com sucesso ela é reaproveitada (bem mais rápido).
            if (con != null && !ok) {
                con.disconnect();
            }
        }
    }

    /**
     * Lê as duas respostas possíveis: a completa ([[["tradução","original",...],...],null,"en"...])
     * e a simples ([["tradução","en"]] ou ["tradução"]). Retorna {tradução, idioma} ou null.
     */
    static String[] parse(String body) throws org.json.JSONException {
        JSONArray root = new JSONArray(body);
        Object first = root.opt(0);
        if (first instanceof JSONArray && ((JSONArray) first).opt(0) instanceof JSONArray) {
            JSONArray parts = (JSONArray) first;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < parts.length(); i++) {
                JSONArray part = parts.optJSONArray(i);
                if (part != null && !part.isNull(0)) {
                    sb.append(part.optString(0));
                }
            }
            return new String[]{sb.toString(), root.optString(2, "")};
        }
        if (first instanceof JSONArray) {
            JSONArray pair = (JSONArray) first;
            return new String[]{pair.optString(0, ""), pair.optString(1, "")};
        }
        if (first instanceof String) {
            return new String[]{(String) first, ""};
        }
        return null;
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

    /** Idioma mais provável do texto entre os conhecidos (pt, en, es, fr, de, it), ou null se não der para saber. */
    static String guess(String text) {
        for (String lang : WORDS.keySet()) {
            if (looksLike(text, lang)) {
                return lang;
            }
        }
        return null;
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
