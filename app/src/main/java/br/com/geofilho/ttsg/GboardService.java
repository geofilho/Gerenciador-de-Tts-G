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
    private static final long PAUSE_MS = 450;

    /** Ícones da barra do Gboard quando não há sugestões (não são sugestões). */
    private static final Set<String> TOOLBAR = new HashSet<>(Arrays.asList(
            "emoji", "emojis", "gif", "adesivos", "figurinhas", "configurações", "tradutor", "traduzir",
            "microfone", "ditado por voz", "digitação por voz", "mais opções", "abrir menu de recursos",
            "área de transferência", "tema", "modo de uma mão", "pesquisar", "teclado flutuante",
            "voltar", "fechar", "settings", "translate", "voice typing", "clipboard", "more"));

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
        notes = 0;
        Diagnostics.note(this, "Sugestões do Gboard: serviço ligado.");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || !Prefs.gboardOn(Prefs.get(this))) {
            return;
        }
        CharSequence p = event.getPackageName();
        if (p == null || !GBOARD.contentEquals(p)) {
            return;
        }
        // Junta os avisos de uma mesma tecla e lê a barra uma vez.
        handler.removeCallbacks(look);
        handler.postDelayed(look, 120);
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
        String s = pending;
        if (s.isEmpty() || s.equals(lastSpoken) || !ready || tts == null) {
            return;
        }
        lastSpoken = s;
        Bundle params = new Bundle();
        tts.speak(s, TextToSpeech.QUEUE_FLUSH, params, "sugestao");
        if (notes < 6) {
            notes++;
            Diagnostics.note(this, "Sugestões do Gboard: falou \"" + s + "\".");
        }
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

    /** As sugestões da barra de cima do Gboard, da esquerda para a direita. */
    private List<AccessibilityNodeInfo> suggestionRow() {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        AccessibilityNodeInfo root = keyboardRoot();
        if (root == null) {
            return out;
        }
        Rect kb = new Rect();
        root.getBoundsInScreen(kb);
        List<AccessibilityNodeInfo> all = new ArrayList<>();
        collect(root, all, 0);
        if (all.isEmpty() || kb.height() <= 0) {
            return out;
        }
        int top = Integer.MAX_VALUE;
        for (AccessibilityNodeInfo n : all) {
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            top = Math.min(top, r.top);
        }
        // Faixa de cima: a barra de sugestões (cerca de um oitavo da altura do teclado).
        int band = top + Math.max(kb.height() / 8, 60);
        for (AccessibilityNodeInfo n : all) {
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            String t = text(n);
            if (r.centerY() <= band && !t.isEmpty() && !TOOLBAR.contains(t.toLowerCase(Locale.ROOT))) {
                out.add(n);
            }
        }
        Collections.sort(out, new Comparator<AccessibilityNodeInfo>() {
            @Override
            public int compare(AccessibilityNodeInfo a, AccessibilityNodeInfo b) {
                Rect ra = new Rect();
                Rect rb = new Rect();
                a.getBoundsInScreen(ra);
                b.getBoundsInScreen(rb);
                return ra.left - rb.left;
            }
        });
        noteRow(out, kb);
        return out;
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
                    if (r != null && r.getPackageName() != null && GBOARD.contentEquals(r.getPackageName())) {
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
    private void noteRow(List<AccessibilityNodeInfo> row, Rect kb) {
        if (rowNotes >= 4 || row.isEmpty()) {
            return;
        }
        rowNotes++;
        StringBuilder b = new StringBuilder("Sugestões do Gboard (detalhe): barra lida (teclado ")
                .append(kb.height()).append(" de altura):");
        for (AccessibilityNodeInfo n : row) {
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            String cls = n.getClassName() != null ? n.getClassName().toString() : "?";
            b.append(" [\"").append(text(n)).append("\" ").append(cls.substring(cls.lastIndexOf('.') + 1))
                    .append(" x=").append(r.left).append(" y=").append(r.centerY());
            if (n.isSelected()) {
                b.append(" selecionada");
            }
            if (n.getViewIdResourceName() != null) {
                b.append(" id=").append(n.getViewIdResourceName().replace(GBOARD + ":id/", ""));
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
        if (tts != null) {
            tts.shutdown();
        }
        super.onDestroy();
    }
}
