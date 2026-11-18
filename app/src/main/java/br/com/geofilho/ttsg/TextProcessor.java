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
                int count = 1;
                int next = end;
                if (group) {
                    // Conta repetições do mesmo emoji (com ou sem espaço entre eles).
                    while (true) {
                        int j = next;
                        while (j < n && text.charAt(j) == ' ') {
                            j++;
                        }
                        if (text.startsWith(cluster, j) && emojiEnd(text, j) == j + cluster.length()) {
                            count++;
                            next = j + cluster.length();
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
            if (emojis.length() > 0 && Character.isWhitespace(cp)) {
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
        if (normal.toString().trim().length() > 0) {
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
