package br.com.geofilho.ttsg;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.PowerManager;
import android.os.UserManager;
import android.provider.Settings;

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

    /** Apaga as falas registradas (fica na memória). */
    static void clearUtterances() {
        synchronized (LAST) {
            LAST.clear();
        }
    }

    /** Apaga as falas e os avisos. */
    static synchronized void clear(Context c) {
        clearUtterances();
        try {
            notes(c).edit().clear().apply();
        } catch (Exception ignored) {
        }
    }

    /** Dados do aparelho, do sistema e das configurações, para o relatório já ir completo. */
    private static String deviceInfo(Context c) {
        StringBuilder sb = new StringBuilder();
        SharedPreferences p = Prefs.get(c);
        PackageManager pm = c.getPackageManager();
        sb.append("Relatório gerado em: ")
                .append(new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.ROOT).format(new Date())).append('\n');
        sb.append("Aparelho: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" (").append(Build.DEVICE).append(")\n");
        sb.append("Android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT)
                .append(")\n");
        sb.append("Versão do Gerenciador: ").append(versionOf(pm, c.getPackageName())).append('\n');
        if (Build.VERSION.SDK_INT >= 24) {
            UserManager um = (UserManager) c.getSystemService(Context.USER_SERVICE);
            sb.append("Aparelho desbloqueado desde que religou: ")
                    .append(um != null && um.isUserUnlocked() ? "sim" : "não").append('\n');
        }
        sb.append("Motor de TTS padrão do sistema: ").append(secure(c, "tts_default_synth")).append('\n');
        long vu = VoiceCatalog.updatedAt(c);
        sb.append("Vozes de outros motores oferecidas aos aplicativos (Ariadne GPS e outros): ")
                .append(VoiceCatalog.count(c))
                .append(vu == 0 ? ", lista ainda não montada" : ", lista atualizada em "
                        + new java.text.SimpleDateFormat("dd/MM HH:mm", Locale.ROOT).format(new java.util.Date(vu)))
                .append('\n');
        sb.append("Teclado em uso: ").append(secure(c, "default_input_method")).append('\n');
        sb.append("Botões sem etiqueta (falar o nome escondido): ")
                .append(Prefs.unlabeledOn(Prefs.get(c)) ? "ligado" : "desligado").append('\n')
                .append(Unlabeled.report(c));
        String a11y = secure(c, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        sb.append("Leitores de tela e serviços de acessibilidade ativos: ")
                .append(a11y.equals("desconhecido") || a11y.isEmpty() ? "nenhum informado" : a11y.replace(":", ", "))
                .append('\n');
        sb.append("Motores de TTS instalados:\n");
        for (ResolveInfo r : EngineClient.installedEngines(c)) {
            String pkg = r.serviceInfo.packageName;
            sb.append("- ").append(r.loadLabel(pm)).append(" (").append(pkg).append("), versão ")
                    .append(versionOf(pm, pkg)).append('\n');
        }
        String bt = PhonePlayer.bluetoothOutputName(c);
        sb.append("Bluetooth de áudio conectado: ").append(bt == null ? "nenhum" : bt).append('\n');
        if (Build.VERSION.SDK_INT >= 23) {
            PowerManager pw = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            sb.append("Sem restrição de bateria para o Gerenciador: ")
                    .append(pw != null && pw.isIgnoringBatteryOptimizations(c.getPackageName()) ? "sim" : "não")
                    .append('\n');
        }
        sb.append("\nConfigurações do Gerenciador:\n");
        sb.append("Voz principal: motor ").append(orDefault(Prefs.engine(p, Prefs.MAIN)))
                .append(", idioma ").append(Prefs.language(p))
                .append(", voz ").append(orDefault(Prefs.voice(p, Prefs.MAIN)))
                .append(", velocidade ").append(Prefs.rate(p, Prefs.MAIN))
                .append("%, tonalidade ").append(Prefs.pitch(p, Prefs.MAIN))
                .append("%, volume ").append(Prefs.volume(p, Prefs.MAIN)).append("%\n");
        String emojiEngine = Prefs.engine(p, Prefs.EMOJI);
        sb.append("Voz para emojis: ");
        if (emojiEngine.isEmpty()) {
            sb.append("a mesma da voz principal");
        } else {
            sb.append("motor ").append(emojiEngine)
                    .append(", voz ").append(orDefault(Prefs.voice(p, Prefs.EMOJI)))
                    .append(", velocidade ").append(Prefs.rate(p, Prefs.EMOJI))
                    .append("%, tonalidade ").append(Prefs.pitch(p, Prefs.EMOJI))
                    .append("%, volume ").append(Prefs.volume(p, Prefs.EMOJI)).append('%');
        }
        sb.append('\n');
        sb.append("Agrupar repetidos: ").append(Prefs.groupRepeats(p) ? "sim" : "não").append('\n');
        String[] numbers = {"número inteiro", "um a um", "dois a dois"};
        String[] punct = {"nenhuma", "algumas", "todas"};
        sb.append("Leitura de números: ").append(pick(numbers, Prefs.numberMode(p)))
                .append(", velocidade dos números ").append(Prefs.numberRate(p)).append("%\n");
        sb.append("Leitura de pontuação: ").append(pick(punct, Prefs.punctuationMode(p))).append('\n');
        sb.append("Tradução automática: ").append(Prefs.translateOn(p) ? "ativada" : "desativada")
                .append(", origem ").append(Translator.displayName(Prefs.translateSource(p)))
                .append(", destino ").append(Translator.displayName(Prefs.translateTarget(p)))
                .append(", traduções guardadas ").append(Translator.savedCount())
                .append(", e-mail do MyMemory ").append(Prefs.myMemoryEmail(p).isEmpty() ? "não informado" : "informado")
                .append('\n');
        sb.append("Tradutor do Android: ").append(Build.VERSION.SDK_INT >= 31
                ? SystemTranslator.status(c, Prefs.translateTarget(p)) : "precisa do Android 12 ou mais novo").append('\n');
        sb.append("Manter o áudio do leitor de tela no telefone: ").append(Prefs.keepOnPhone(p) ? "sim" : "não")
                .append('\n');
        sb.append("Palavras no dicionário: ").append(Dictionary.get(c).list().size()).append('\n');
        sb.append("Registro das falas: ").append(Prefs.diagnosticsOn(p) ? "ativado" : "desativado").append('\n');
        return sb.toString();
    }

    private static String versionOf(PackageManager pm, String pkg) {
        try {
            PackageInfo pi = pm.getPackageInfo(pkg, 0);
            return pi.versionName + " (" + pi.versionCode + ")";
        } catch (Exception e) {
            return "desconhecida";
        }
    }

    private static String secure(Context c, String key) {
        try {
            String v = Settings.Secure.getString(c.getContentResolver(), key);
            return v == null ? "desconhecido" : v;
        } catch (Exception e) {
            return "desconhecido";
        }
    }

    private static String orDefault(String s) {
        return s == null || s.isEmpty() ? "padrão" : s;
    }

    private static String pick(String[] items, int i) {
        return items[i >= 0 && i < items.length ? i : 0];
    }

    static String report(Context c) {
        StringBuilder sb = new StringBuilder();
        try {
            sb.append(deviceInfo(c)).append('\n');
        } catch (Exception e) {
            sb.append("Não foi possível ler os dados do aparelho.\n\n");
        }
        String avisos = "";
        try {
            avisos = notes(c).getString("avisos", "").trim();
        } catch (Exception ignored) {
        }
        sb.append("Avisos sobre troca de motor, do mais novo para o mais antigo:\n");
        sb.append(avisos.isEmpty() ? "nenhum aviso." : avisos).append("\n\n");
        if (!Prefs.diagnosticsOn(Prefs.get(c))) {
            sb.append("Registro das falas desativado. Para registrar o que o leitor de tela envia ao motor, "
                    + "ative a chave Ativar diagnóstico, use o leitor de tela um pouco e veja o relatório de novo.");
            return sb.toString();
        }
        synchronized (LAST) {
            if (LAST.isEmpty()) {
                sb.append("O motor ainda não recebeu nenhum texto desde que o diagnóstico foi ativado. Confira se "
                        + "o Gerenciador de TTS G está escolhido como motor de TTS do sistema e use o leitor de tela "
                        + "um pouco.");
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
