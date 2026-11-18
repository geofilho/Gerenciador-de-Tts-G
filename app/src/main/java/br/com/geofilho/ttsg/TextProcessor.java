package br.com.geofilho.ttsg;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Separa o texto em trechos normais e trechos de emoji (para usar vozes
 * diferentes) e, se ativado, agrupa emojis e símbolos repetidos:
 * cinco corações vermelhos viram "❤️ 5" (o motor fala "coração vermelho 5")
 * e "****" vira "asterisco 4". A regra vale para 2 ou mais repetições.
 */
final class TextProcessor {

    static final class Segment {
        final String text;
        final boolean emoji;

        Segment(String text, boolean emoji) {
            this.text = text;
            this.emoji = emoji;
        }
    }

    private static final Map<Integer, String> SYMBOLS = new HashMap<>();

    static {
        String[][] names = {
                {".", "ponto"}, {",", "vírgula"}, {"!", "exclamação"}, {"?", "interrogação"},
                {"*", "asterisco"}, {"-", "hífen"}, {"_", "sublinhado"}, {"=", "igual"},
                {"+", "mais"}, {"#", "cerquilha"}, {"@", "arroba"}, {"&", "e comercial"},
                {"%", "por cento"}, {"$", "cifrão"}, {"/", "barra"}, {"\\", "barra invertida"},
                {"|", "barra vertical"}, {"~", "til"}, {"^", "circunflexo"}, {":", "dois pontos"},
                {";", "ponto e vírgula"}, {"'", "apóstrofo"}, {"\"", "aspas"}, {"(", "abre parênteses"},
                {")", "fecha parênteses"}, {"[", "abre colchetes"}, {"]", "fecha colchetes"},
                {"{", "abre chaves"}, {"}", "fecha chaves"}, {"<", "menor que"}, {">", "maior que"},
                {"`", "crase"}, {"•", "marcador"}, {"…", "reticências"}, {"–", "travessão"},
                {"—", "travessão"}, {"°", "grau"}, {"§", "parágrafo"}, {"€", "euro"}, {"£", "libra"},
                {"«", "abre aspas"}, {"»", "fecha aspas"}, {"·", "ponto médio"},
        };
        for (String[] n : names) {
            SYMBOLS.put(n[0].codePointAt(0), n[1]);
        }
    }

    private TextProcessor() {
    }

    static List<Segment> split(String text, boolean group) {
        List<Segment> out = new ArrayList<>();
        StringBuilder normal = new StringBuilder();
        StringBuilder emojis = new StringBuilder();
        int n = text.length();
        int i = 0;
        while (i < n) {
            int end = emojiEnd(text, i);
            if (end > i) {
                String cluster = text.substring(i, end);
                String key = baseKey(cluster);
                int count = 1;
                int next = end;
                if (group) {
                    // Conta repetições do mesmo emoji, mesmo com espaços (visíveis ou
                    // invisíveis) entre eles e com ou sem o seletor de cor (FE0F).
                    while (true) {
                        int j = next;
                        while (j < n && isGap(text.charAt(j))) {
                            j++;
                        }
                        int e = emojiEnd(text, j);
                        if (e > j && key.equals(baseKey(text.substring(j, e)))) {
                            count++;
                            next = e;
                        } else {
                            break;
                        }
                    }
                }
                flushNormal(out, normal, emojis);
                if (emojis.length() > 0) {
                    emojis.append(' ');
                }
                emojis.append(cluster);
                if (count > 1) {
                    emojis.append(' ').append(count);
                }
                i = next;
                continue;
            }

            int cp = text.codePointAt(i);
            int len = Character.charCount(cp);
            if (group && isSymbol(cp)) {
                int count = 1;
                int j = i + len;
                while (j < n && text.codePointAt(j) == cp) {
                    count++;
                    j += len;
                }
                if (count > 1) {
                    flushEmoji(out, emojis);
                    String name = SYMBOLS.get(cp);
                    normal.append(' ').append(name != null ? name : new String(Character.toChars(cp)))
                            .append(' ').append(count).append(' ');
                    i = j;
                    continue;
                }
            }
            if (emojis.length() > 0 && isGap((char) cp)) {
                // Espaço entre emojis não precisa virar um trecho separado.
                i += len;
                continue;
            }
            flushEmoji(out, emojis);
            normal.appendCodePoint(cp);
            i += len;
        }
        flushEmoji(out, emojis);
        flushNormal(out, normal, emojis);
        return out;
    }

    static final int NUMBERS_WHOLE = 0;
    static final int NUMBERS_ONE_BY_ONE = 1;
    static final int NUMBERS_PAIRS = 2;

    /**
     * Leitura de números: inteiro (sem mudança), um a um ("1234" vira "1 2 3 4")
     * ou dois a dois ("1234" vira "12 34"; se sobrar um, ele fica sozinho no fim).
     */
    static String numbers(String text, int mode) {
        if (mode == NUMBERS_WHOLE || text == null) {
            return text;
        }
        int step = mode == NUMBERS_PAIRS ? 2 : 1;
        StringBuilder out = null;
        int n = text.length();
        int i = 0;
        while (i < n) {
            char c = text.charAt(i);
            if (c >= '0' && c <= '9') {
                int j = i;
                while (j < n && text.charAt(j) >= '0' && text.charAt(j) <= '9') {
                    j++;
                }
                if (j - i > step) {
                    if (out == null) {
                        out = new StringBuilder(n + 16).append(text, 0, i);
                    }
                    for (int k = i; k < j; k += step) {
                        if (k > i) {
                            out.append(' ');
                        }
                        out.append(text, k, Math.min(k + step, j));
                    }
                } else if (out != null) {
                    out.append(text, i, j);
                }
                i = j;
            } else if ((c == '.' || c == ',' || c == ':') && i > 0 && i + 1 < n
                    && isDigit(text.charAt(i - 1)) && isDigit(text.charAt(i + 1))) {
                // "1.250" ou "12:30": separa a pontuação para o motor não juntar os números.
                if (out == null) {
                    out = new StringBuilder(n + 16).append(text, 0, i);
                }
                out.append(' ').append(c).append(' ');
                i++;
            } else {
                if (out != null) {
                    out.append(c);
                }
                i++;
            }
        }
        return out == null ? text : out.toString();
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    /** Junta tudo em um texto só (usado quando não há voz separada para emojis). */
    static String join(List<Segment> segments) {
        StringBuilder sb = new StringBuilder();
        for (Segment s : segments) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(s.text);
        }
        return sb.toString();
    }

    private static void flushNormal(List<Segment> out, StringBuilder normal, StringBuilder emojis) {
        if (normal.length() == 0) {
            return;
        }
        if (hasContent(normal)) {
            out.add(new Segment(normal.toString(), false));
        }
        normal.setLength(0);
    }

    private static void flushEmoji(List<Segment> out, StringBuilder emojis) {
        if (emojis.length() > 0) {
            out.add(new Segment(emojis.toString(), true));
            emojis.setLength(0);
        }
    }

    /** Espaços comuns e invisíveis que aparecem entre emojis. */
    static boolean isGap(char c) {
        return Character.isWhitespace(c) || c == '\u00A0' || c == '\u200B' || c == '\u200C'
                || c == '\u2060' || c == '\uFEFF' || c == '\u202F' || c == '\u2007';
    }

    private static boolean hasContent(CharSequence s) {
        for (int k = 0; k < s.length(); k++) {
            if (!isGap(s.charAt(k))) {
                return true;
            }
        }
        return false;
    }

    /** Emoji sem os seletores de variação, para comparar "❤" com "❤️". */
    private static String baseKey(String cluster) {
        return cluster.replace("\uFE0F", "").replace("\uFE0E", "");
    }

    private static boolean isSymbol(int cp) {
        return !Character.isLetterOrDigit(cp) && !Character.isWhitespace(cp) && !Character.isISOControl(cp);
    }

    /**
     * Se em {@code i} começa um emoji, retorna onde ele termina (incluindo
     * modificadores de pele, seletor de variação, sequências com ZWJ,
     * bandeiras e teclas como 1️⃣). Senão, retorna {@code i}.
     */
    static int emojiEnd(String s, int i) {
        int n = s.length();
        if (i >= n) {
            return i;
        }
        int cp = s.codePointAt(i);
        int j = i + Character.charCount(cp);

        // Bandeiras: par de indicadores regionais.
        if (isRegional(cp)) {
            if (j < n && isRegional(s.codePointAt(j))) {
                return j + Character.charCount(s.codePointAt(j));
            }
            return j;
        }
        // Teclas: 0-9, # ou * seguidos de FE0F? e 20E3.
        if ((cp >= '0' && cp <= '9') || cp == '#' || cp == '*') {
            int k = j;
            if (k < n && s.charAt(k) == '️') {
                k++;
            }
            if (k < n && s.charAt(k) == '⃣') {
                return k + 1;
            }
            return i;
        }
        boolean base = isEmojiBase(cp) || (j < n && s.charAt(j) == '️' && !Character.isLetterOrDigit(cp)
                && !Character.isWhitespace(cp));
        if (!base) {
            return i;
        }
        while (j < n) {
            int c = s.codePointAt(j);
            if (c == 0xFE0F || c == 0xFE0E || c == 0x20E3 || (c >= 0x1F3FB && c <= 0x1F3FF)
                    || (c >= 0xE0020 && c <= 0xE007F)) {
                j += Character.charCount(c);
            } else if (c == 0x200D && j + 1 < n) {
                int after = s.codePointAt(j + 1);
                if (isEmojiBase(after)) {
                    j += 1 + Character.charCount(after);
                } else {
                    break;
                }
            } else {
                break;
            }
        }
        return j;
    }

    private static boolean isRegional(int cp) {
        return cp >= 0x1F1E6 && cp <= 0x1F1FF;
    }

    private static boolean isEmojiBase(int cp) {
        return (cp >= 0x1F000 && cp <= 0x1FAFF)
                || (cp >= 0x2600 && cp <= 0x27BF)
                || (cp >= 0x2B00 && cp <= 0x2BFF)
                || (cp >= 0x2300 && cp <= 0x23FF)
                || cp == 0x203C || cp == 0x2049 || cp == 0x2122 || cp == 0x2139
                || cp == 0x3030 || cp == 0x303D || cp == 0x3297 || cp == 0x3299
                || cp == 0x24C2 || cp == 0x2934 || cp == 0x2935;
    }
}
