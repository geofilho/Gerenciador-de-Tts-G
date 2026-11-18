package br.com.geofilho.ttsg;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.List;

/**
 * Guarda na memória os últimos textos que o motor recebeu do leitor de tela
 * e como eles ficaram depois das regras. Serve para descobrir problemas.
 */
final class Diagnostics {
    private static final int MAX = 15;
    private static final ArrayDeque<String> LAST = new ArrayDeque<>();

    private Diagnostics() {
    }

    static void record(String received, List<TextProcessor.Segment> spoken, boolean emojiVoice) {
        if (received.trim().isEmpty()) {
            return; // pausas e sons do leitor de tela: não interessam
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Recebido: ").append(received).append('\n');
        sb.append("Códigos especiais: ").append(codes(received)).append('\n');
        sb.append("Falado: ");
        for (TextProcessor.Segment s : spoken) {
            sb.append(s.emoji && emojiVoice ? "[voz de emojis] " : "[voz principal] ").append(s.text).append(' ');
        }
        synchronized (LAST) {
            LAST.addFirst(sb.toString().trim());
            while (LAST.size() > MAX) {
                LAST.removeLast();
            }
        }
    }

    static String report() {
        synchronized (LAST) {
            if (LAST.isEmpty()) {
                return "O motor ainda não recebeu nenhum texto. Confira se o Gerenciador de TTS G "
                        + "está escolhido como motor de TTS do sistema e use o leitor de tela um pouco.";
            }
            StringBuilder sb = new StringBuilder("Últimos textos recebidos, do mais novo para o mais antigo.\n\n");
            int n = 1;
            for (Iterator<String> it = LAST.iterator(); it.hasNext(); n++) {
                sb.append(n).append(". ").append(it.next()).append("\n\n");
            }
            return sb.toString().trim();
        }
    }

    /** Lista os códigos dos caracteres que não são letras comuns (até 40). */
    private static String codes(String s) {
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (int i = 0; i < s.length() && count < 40; ) {
            int cp = s.codePointAt(i);
            if (cp > 0x7E) {
                if (!Character.isLetter(cp)) {
                    sb.append(Integer.toHexString(cp).toUpperCase()).append(' ');
                    count++;
                }
            }
            i += Character.charCount(cp);
        }
        return sb.length() == 0 ? "nenhum" : sb.toString().trim();
    }
}
