package br.com.geofilho.ttsg;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * "Ler automaticamente as sugestões do Gboard" (teste): observa só o teclado Gboard. Quando a
 * sugestão principal da barra de cima muda e a digitação pausa um instante, o Gerenciador fala a
 * sugestão. Se a pessoa volta a digitar, a fala da sugestão é interrompida na hora, para não
 * atrasar o eco do TalkBack. Nunca fala nada em campos de senha.
 */
public class GboardService extends AccessibilityService {

    static final String GBOARD = "com.google.android.inputmethod.latin";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextToSpeech tts;
    private boolean ready;

    /** Sugestões da última leitura da barra (para saber se mudou). */
    private String lastRow = "";
    /** Sugestão principal já falada (não repete a mesma). */
    private String lastSpoken = "";
    private String pending = "";

    /** Pausa na digitação antes de falar a sugestão. */
    private static final long PAUSE_MS = 250;

    /** Ícones da barra do Gboard quando não há sugestões (não são sugestões). */
    private static final Set<String> TOOLBAR = new HashSet<>(Arrays.asList(
            "emoji", "emojis", "gif", "adesivos", "figurinhas", "configurações", "tradutor", "traduzir",
            "microfone", "ditado por voz", "digitação por voz", "mais opções", "abrir menu de recursos",
            "área de transferência", "tema", "modo de uma mão", "pesquisar", "teclado flutuante",
            "voltar", "fechar", "settings", "translate", "voice typing", "clipboard", "more",
            "desfazer", "undo"));

    private final Runnable look = new Runnable() {
        @Override
        public void run() {
            lookAtRow();
        }
    };

    private final Runnable say = new Runnable() {
        @Override
        public void run() {
            sayPending();
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        // Fala pelo próprio Gerenciador: mesma voz, velocidade e dicionário do leitor de tela.
        tts = new TextToSpeech(getApplicationContext(), new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                ready = status == TextToSpeech.SUCCESS;
            }
        }, getPackageName());
        followKeyboard();
        keyboardWatch = new android.database.ContentObserver(handler) {
            @Override
            public void onChange(boolean selfChange) {
                followKeyboard();
            }
        };
        try {
            getContentResolver().registerContentObserver(
                    android.provider.Settings.Secure.getUriFor("default_input_method"), false, keyboardWatch);
        } catch (Exception ignored) {
        }
        notes = 0;
        rowNotes = 0;
        Diagnostics.note(this, "Sugestões do Gboard: serviço ligado.");
    }

    /** Teclado em uso (o Gboard ou outro). O serviço só recebe eventos dele. */
    private String keyboardPkg = GBOARD;
    private android.database.ContentObserver keyboardWatch;

    private void followKeyboard() {
        String pkg = GBOARD;
        try {
            String ime = android.provider.Settings.Secure.getString(getContentResolver(), "default_input_method");
            if (ime != null && ime.indexOf('/') > 0) {
                pkg = ime.substring(0, ime.indexOf('/'));
            }
        } catch (Exception ignored) {
        }
        keyboardPkg = pkg;
        try {
            android.accessibilityservice.AccessibilityServiceInfo info = getServiceInfo();
            if (info != null) {
                info.packageNames = new String[]{pkg};
                setServiceInfo(info);
            }
        } catch (Exception ignored) {
        }
        lastRow = "";
        lastSpoken = "";
        rowNotes = 0;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || !Prefs.gboardOn(Prefs.get(this))) {
            return;
        }
        CharSequence p = event.getPackageName();
        if (p == null || !p.toString().equals(keyboardPkg)) {
            return;
        }
        // Junta os avisos de uma mesma tecla e lê a barra uma vez.
        handler.removeCallbacks(look);
        handler.postDelayed(look, 80);
    }

    /** Lê a barra de sugestões; se mudou, cala a sugestão anterior e agenda a nova para a pausa. */
    private void lookAtRow() {
        if (isPasswordField()) {
            stopSpeaking();
            pending = "";
            return;
        }
        List<AccessibilityNodeInfo> row = suggestionRow();
        StringBuilder sig = new StringBuilder();
        for (AccessibilityNodeInfo n : row) {
            sig.append(text(n)).append('|');
        }
        String now = sig.toString();
        if (now.equals(lastRow)) {
            return;
        }
        lastRow = now;
        stopSpeaking(); // a barra mudou: a pessoa digitou de novo
        handler.removeCallbacks(say);
        String main = mainSuggestion(row);
        if (main.isEmpty()) {
            pending = "";
            lastSpoken = "";
            return;
        }
        pending = main;
        handler.postDelayed(say, PAUSE_MS);
    }

    private void sayPending() {
        String s = withoutEmojiWord(pending);
        if (s.isEmpty() || s.equals(lastSpoken) || !ready || tts == null) {
            return;
        }
        lastSpoken = s;
        Bundle params = new Bundle();
        tts.speak(s, TextToSpeech.QUEUE_FLUSH, params, "sugestao");
        if (notes < 10) {
            notes++;
            Diagnostics.note(this, "Sugestões do Gboard: falou \"" + s + "\".");
        }
    }

    /**
     * Sugestão de emoji: o Gboard descreve como "Emoji 😔". Fala só o emoji, que o Gerenciador lê com a
     * voz dos emojis, como no resto do aplicativo.
     */
    static String withoutEmojiWord(String s) {
        String t = s.trim();
        String l = t.toLowerCase(Locale.ROOT);
        for (String w : new String[]{"emoji ", "emoji: ", "emoji, "}) {
            if (l.startsWith(w) && t.length() > w.length()) {
                return t.substring(w.length()).trim();
            }
        }
        return t;
    }

    private void stopSpeaking() {
        if (tts != null) {
            try {
                tts.stop();
            } catch (Exception ignored) {
            }
        }
    }

    // ----- barra de sugestões -----

    /**
     * As sugestões da barra de cima do Gboard, da esquerda para a direita. A barra fica na mesma altura
     * dos botões "Abrir o menu de recursos" e "Usar a digitação por voz" (identificador key_pos_header).
     * As sugestões não têm identificador; as teclas têm (D01, D02...) e ficam mais abaixo.
     */
    private List<AccessibilityNodeInfo> suggestionRow() {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        AccessibilityNodeInfo root = keyboardRoot();
        if (root == null) {
            return out;
        }
        List<AccessibilityNodeInfo> all = new ArrayList<>();
        collect(root, all, 0);
        if (all.isEmpty()) {
            return out;
        }
        boolean gboard = root.getPackageName() != null && GBOARD.contentEquals(root.getPackageName());
        if (!gboard) {
            otherKeyboardRow(all, out);
            sortByX(out);
            noteRow(out);
            return out;
        }
        // Altura da barra: a dos botões da barra de cima; sem eles, a do item sem identificador mais alto.
        int barY = Integer.MIN_VALUE;
        for (AccessibilityNodeInfo n : all) {
            String id = n.getViewIdResourceName();
            if (id != null && id.contains("key_pos_header")) {
                barY = centerY(n);
                break;
            }
        }
        if (barY == Integer.MIN_VALUE) {
            int top = Integer.MAX_VALUE;
            for (AccessibilityNodeInfo n : all) {
                if (n.getViewIdResourceName() == null) {
                    top = Math.min(top, centerY(n));
                }
            }
            if (top == Integer.MAX_VALUE) {
                return out;
            }
            barY = top;
        }
        for (AccessibilityNodeInfo n : all) {
            String t = text(n);
            CharSequence c = n.getClassName();
            String cls = c != null ? c.toString() : "";
            if (n.getViewIdResourceName() == null              // teclas e botões têm identificador
                    && Math.abs(centerY(n) - barY) <= 40        // na altura da barra de cima
                    && !cls.endsWith("Button")                   // "Edição de texto" e outros botões
                    && !t.isEmpty() && !isToolbar(t)) {
                out.add(n);
            }
        }
        sortByX(out);
        noteRow(out);
        return out;
    }

    private static void sortByX(List<AccessibilityNodeInfo> list) {
        Collections.sort(list, new Comparator<AccessibilityNodeInfo>() {
            @Override
            public int compare(AccessibilityNodeInfo a, AccessibilityNodeInfo b) {
                Rect ra = new Rect();
                Rect rb = new Rect();
                a.getBoundsInScreen(ra);
                b.getBoundsInScreen(rb);
                return ra.left - rb.left;
            }
        });
    }

    /**
     * Outros teclados (como os de código aberto): as sugestões ficam numa faixa cujo identificador
     * fala em sugestão ou candidato; sem isso, os textos (não descrições de teclas) da faixa de cima.
     */
    private static void otherKeyboardRow(List<AccessibilityNodeInfo> all, List<AccessibilityNodeInfo> out) {
        for (AccessibilityNodeInfo n : all) {
            if (inSuggestionStrip(n) && n.getText() != null && !isButton(n) && !isToolbar(text(n))) {
                out.add(n);
            }
        }
        if (!out.isEmpty()) {
            return;
        }
        int top = Integer.MAX_VALUE;
        for (AccessibilityNodeInfo n : all) {
            if (n.getText() != null && n.getText().length() > 0) {
                top = Math.min(top, centerY(n));
            }
        }
        for (AccessibilityNodeInfo n : all) {
            if (n.getText() != null && n.getText().length() > 0 && Math.abs(centerY(n) - top) <= 40
                    && !isButton(n) && !isToolbar(text(n))) {
                out.add(n);
            }
        }
    }

    private static boolean inSuggestionStrip(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo p = n;
        for (int i = 0; p != null && i < 6; i++) {
            String id = p.getViewIdResourceName();
            if (id != null) {
                String l = id.toLowerCase(Locale.ROOT);
                if (l.contains("suggest") || l.contains("candidate") || l.contains("strip")) {
                    return true;
                }
            }
            p = p.getParent();
        }
        return false;
    }

    private static boolean isButton(AccessibilityNodeInfo n) {
        CharSequence c = n.getClassName();
        return c != null && c.toString().endsWith("Button");
    }

    /** Botões da barra que não são sugestões, como "Desfazer" (que aparece depois de uma correção). */
    private static boolean isToolbar(String t) {
        String l = t.toLowerCase(Locale.ROOT);
        return TOOLBAR.contains(l) || l.startsWith("desfazer") || l.startsWith("undo");
    }

    private static int centerY(AccessibilityNodeInfo n) {
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        return r.centerY();
    }

    /** A sugestão principal: a marcada como selecionada; senão, a do meio (a que o espaço usa). */
    private static String mainSuggestion(List<AccessibilityNodeInfo> row) {
        if (row.isEmpty()) {
            return "";
        }
        for (AccessibilityNodeInfo n : row) {
            if (n.isSelected() || n.isChecked()) {
                return text(n);
            }
        }
        return text(row.get(row.size() >= 3 ? row.size() / 2 : 0));
    }

    /** Janela do teclado (precisa da lista de janelas interativas). */
    private AccessibilityNodeInfo keyboardRoot() {
        try {
            for (AccessibilityWindowInfo w : getWindows()) {
                if (w.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                    AccessibilityNodeInfo r = w.getRoot();
                    if (r != null) {
                        return r;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** Nós com texto, visíveis, folhas ou quase (os botões de sugestão). */
    private static void collect(AccessibilityNodeInfo n, List<AccessibilityNodeInfo> out, int depth) {
        if (n == null || depth > 30 || out.size() > 300) {
            return;
        }
        if (n.isVisibleToUser() && !text(n).isEmpty()) {
            out.add(n);
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            collect(n.getChild(i), out, depth + 1);
        }
    }

    private static String text(AccessibilityNodeInfo n) {
        CharSequence t = n.getText();
        if (t == null || t.toString().trim().isEmpty()) {
            t = n.getContentDescription();
        }
        return t == null ? "" : t.toString().trim();
    }

    /** Nunca lê sugestões quando o campo em que se digita é de senha. */
    private boolean isPasswordField() {
        try {
            AccessibilityNodeInfo app = getRootInActiveWindow();
            AccessibilityNodeInfo input = app != null ? app.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) : null;
            return input != null && input.isPassword();
        } catch (Exception e) {
            return false;
        }
    }

    // ----- relatório (só no teste) -----

    private int notes;
    private int rowNotes;

    /** As primeiras barras lidas vão para o relatório, para ajustar o reconhecimento das sugestões. */
    private void noteRow(List<AccessibilityNodeInfo> row) {
        if (rowNotes >= 8 || row.isEmpty()) {
            return;
        }
        rowNotes++;
        StringBuilder b = new StringBuilder("Sugestões do Gboard (detalhe): barra de " + keyboardPkg + ":");
        for (AccessibilityNodeInfo n : row) {
            b.append(" [").append(text(n));
            if (n.isSelected()) {
                b.append(", selecionada");
            }
            b.append(']');
        }
        Diagnostics.note(this, b.toString());
    }

    @Override
    public void onInterrupt() {
        stopSpeaking();
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(look);
        handler.removeCallbacks(say);
        if (keyboardWatch != null) {
            getContentResolver().unregisterContentObserver(keyboardWatch);
        }
        if (tts != null) {
            tts.shutdown();
        }
        super.onDestroy();
    }
}
