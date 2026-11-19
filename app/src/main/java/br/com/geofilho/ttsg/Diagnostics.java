package br.com.geofilho.ttsg;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * Diagnóstico: guarda na memória os últimos textos que o motor recebeu do
 * leitor de tela e como foram falados, e guarda à parte (sem perder ao
 * religar) os avisos sobre troca de motor.
 */
final class Diagnostics {
    private static final int MAX = 15;
    private static final int MAX_NOTES = 10;
    private static final ArrayDeque<String> LAST = new ArrayDeque<>();

    private Diagnostics() {
    }

    static void record(String received, List<TextProcessor.Segment> spoken, boolean emojiVoice, String engine) {
        if (received.trim().isEmpty()) {
            return; // pausas e sons do leitor de tela: não interessam
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Recebido: ").append(received).append('\n');
        sb.append("Códigos especiais: ").append(codes(received)).append('\n');
        sb.append("Motor: ").append(engine).append('\n');
        sb.append("Falado: ");
        for (TextProcessor.Segment s : spoken) {
            sb.append(s.emoji && emojiVoice ? "[voz de emojis] " : s.number ? "[números] " : "[voz principal] ")
                    .append(s.text).append(' ');
        }
        synchronized (LAST) {
            LAST.addFirst(sb.toString().trim());
            while (LAST.size() > MAX) {
                LAST.removeLast();
            }
        }
    }

    private static SharedPreferences notes(Context c) {
        return Prefs.storage(c).getSharedPreferences("diagnostico", Context.MODE_PRIVATE);
    }

    /** Registra um aviso com horário. Não repete o mesmo aviso seguido. */
    static synchronized void note(Context c, String msg) {
        try {
            SharedPreferences p = notes(c);
            if (msg.equals(p.getString("ultimo", ""))) {
                return;
            }
            String time = new SimpleDateFormat("dd/MM HH:mm:ss", Locale.ROOT).format(new Date());
            String all = time + ": " + msg + "\n" + p.getString("avisos", "");
            String[] lines = all.split("\n");
            StringBuilder keep = new StringBuilder();
            for (int i = 0; i < lines.length && i < MAX_NOTES; i++) {
                if (!lines[i].isEmpty()) {
                    keep.append(lines[i]).append('\n');
                }
            }
            p.edit().putString("avisos", keep.toString()).putString("ultimo", msg).apply();
        } catch (Exception ignored) {
            // Diagnóstico nunca pode atrapalhar a fala.
        }
    }

    static String report(Context c) {
        StringBuilder sb = new StringBuilder();
        String avisos = "";
        try {
            avisos = notes(c).getString("avisos", "").trim();
        } catch (Exception ignored) {
        }
        sb.append("Avisos sobre troca de motor, do mais novo para o mais antigo:\n");
        sb.append(avisos.isEmpty() ? "nenhum aviso." : avisos).append("\n\n");
        synchronized (LAST) {
            if (LAST.isEmpty()) {
                sb.append("O motor ainda não recebeu nenhum texto. Confira se o Gerenciador de TTS G "
                        + "está escolhido como motor de TTS do sistema e use o leitor de tela um pouco.");
                return sb.toString();
            }
            sb.append("Últimos textos recebidos, do mais novo para o mais antigo.\n\n");
            int n = 1;
            for (Iterator<String> it = LAST.iterator(); it.hasNext(); n++) {
                sb.append(n).append(". ").append(it.next()).append("\n\n");
            }
        }
        return sb.toString().trim();
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
