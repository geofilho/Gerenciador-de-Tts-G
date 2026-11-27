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
    /** A sugestão a falar é a correção marcada (o espaço insere)? */
    private boolean pendingCorrection;
    /** Tom das sugestões que só entram tocando (complementos e previsões); a correção usa o tom normal. */
    private static final float SUGGESTION_PITCH = 1.3f;

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
        prefsWatch = new android.content.SharedPreferences.OnSharedPreferenceChangeListener() {
            @Override
            public void onSharedPreferenceChanged(android.content.SharedPreferences sp, String key) {
                if ("unlabeled_on".equals(key)) {
                    followKeyboard();
                }
            }
        };
        Prefs.get(this).registerOnSharedPreferenceChangeListener(prefsWatch);
    }

    /** Liga ou desliga a escuta de todos os aplicativos quando a chave dos botões sem etiqueta muda. */
    private android.content.SharedPreferences.OnSharedPreferenceChangeListener prefsWatch;

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
                // Com a função dos botões sem etiqueta ligada, recebe o foco de todos os aplicativos.
                info.packageNames = Prefs.unlabeledOn(Prefs.get(this)) ? null : new String[]{pkg};
                setServiceInfo(info);
            }
        } catch (Exception ignored) {
        }
        lastRow = "";
        lastSpoken = "";
        rowNotes = 0;
        dumpNotes = 0;
    }

    private String notedKeyboard = "";
    private int dumpNotes;

    /**
     * Teclado que não é o Gboard e nenhuma sugestão reconhecida: registra o que há na parte de cima do
     * teclado (textos, tipos, identificadores e alturas), para ajustar o reconhecimento.
     */
    private void dumpTop(List<AccessibilityNodeInfo> all) {
        // Só usado nos testes.
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) {
            return;
        }
        android.content.SharedPreferences prefs = Prefs.get(this);
        if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED) {
            if (Prefs.unlabeledOn(prefs)) {
                CharSequence app = event.getPackageName();
                if (app != null && app.toString().equals(keyboardPkg)) {
                    return; // teclas do teclado: o leitor de tela já fala
                }
                Unlabeled.focusStarted();
                AccessibilityNodeInfo n = null;
                try {
                    n = event.getSource();
                } catch (Exception ignored) {
                }
                try {
                    Unlabeled.inspect(this, n, app);
                } catch (Exception e) {
                    Unlabeled.focusDone(false, null, "", "", "", "");
                }
            }
            return;
        }
        if (!Prefs.gboardOn(prefs)) {
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
        boolean visible = keyboardRoot() != null;
        boolean justOpened = visible && !keyboardVisible;
        keyboardVisible = visible;
        if (!visible) {
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
        if (justOpened) {
            // Teclado acabou de abrir: a barra ainda não é resultado da digitação (pode ser o que está
            // na área de transferência, por exemplo). Só fala depois que a pessoa digitar.
            pending = "";
            return;
        }
        String main = mainSuggestion(row, typedWord(), getResources().getDisplayMetrics().widthPixels / 2);
        if (main.isEmpty()) {
            pending = "";
            lastSpoken = "";
            return;
        }
        pending = main;
        pendingCorrection = false;
        for (AccessibilityNodeInfo n : row) {
            if ((n.isSelected() || n.isChecked()) && text(n).equals(main)) {
                pendingCorrection = true; // a correção marcada: é o que o espaço vai colocar
            }
        }
        handler.postDelayed(say, PAUSE_MS);
    }

    private void sayPending() {
        String s = withoutEmojiWord(pending);
        if (s.isEmpty() || s.equals(lastSpoken) || !ready || tts == null) {
            return;
        }
        lastSpoken = s;
        Bundle params = new Bundle();
        // Correção (o espaço insere): voz normal. Sugestão (só tocando): tom um pouco mais agudo.
        tts.setPitch(pendingCorrection ? 1.0f : SUGGESTION_PITCH);
        tts.speak(s, TextToSpeech.QUEUE_FLUSH, params, "sugestao");
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
        // Jeito do Gboard: o próprio Gboard e teclados feitos a partir dele (como o Pixelboard), que têm a
        // mesma barra com os botões key_pos_header.
        boolean gboard = root.getPackageName() != null && GBOARD.contentEquals(root.getPackageName());
        for (int i = 0; !gboard && i < all.size(); i++) {
            String id = all.get(i).getViewIdResourceName();
            gboard = id != null && id.contains("key_pos_header");
        }
        if (!gboard) {
            otherKeyboardRow(all, out);
            if (out.isEmpty()) {
                dumpTop(all);
            }
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
                    && !t.isEmpty() && !isToolbar(t) && !isClipboard(n)) {
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
            if (inSuggestionStrip(n) && n.getText() != null && !isButton(n) && !isToolbar(text(n)) && !isClipboard(n)) {
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
                    && !isButton(n) && !isToolbar(text(n)) && !isClipboard(n)) {
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

    /** Item da área de transferência na barra (o texto copiado): nunca é falado. */
    private static boolean isClipboard(AccessibilityNodeInfo n) {
        CharSequence d = n.getContentDescription();
        String l = d != null ? d.toString().toLowerCase(Locale.ROOT) : "";
        String id = n.getViewIdResourceName() != null ? n.getViewIdResourceName().toLowerCase(Locale.ROOT) : "";
        return l.contains("transferência") || l.contains("transferencia") || l.contains("clipboard")
                || l.contains("colar") || l.contains("paste") || id.contains("clip");
    }

    /** Botões da barra que não são sugestões, como "Desfazer" (que aparece depois de uma correção). */
    private static boolean isToolbar(String t) {
        String l = t.toLowerCase(Locale.ROOT);
        return TOOLBAR.contains(l) || l.startsWith("desfazer") || l.startsWith("undo")
                // Imagens, figurinhas e GIFs sugeridos ("Imagem Não"): não são palavras para falar.
                || l.startsWith("imagem") || l.startsWith("image") || l.startsWith("gif")
                || l.startsWith("figurinha") || l.startsWith("adesivo") || l.startsWith("sticker");
    }

    private static int centerY(AccessibilityNodeInfo n) {
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        return r.centerY();
    }

    /** A sugestão principal: a marcada como selecionada; senão, a do meio (a que o espaço usa). */
    /**
     * A sugestão a falar:
     * - a marcada como selecionada (a correção que o espaço vai colocar);
     * - sem nenhuma marcada (digitando ou depois do espaço): a palavra no centro da barra;
     * - nada, se for igual ao que já foi digitado.
     */
    private static String mainSuggestion(List<AccessibilityNodeInfo> row, String typed, int screenCenter) {
        if (row.isEmpty()) {
            return "";
        }
        for (AccessibilityNodeInfo n : row) {
            if (n.isSelected() || n.isChecked()) {
                String t = text(n);
                return typed != null && t.equalsIgnoreCase(typed) ? "" : t; // igual ao digitado: nada a avisar
            }
        }
        // Sem correção marcada (digitando, como "n" que mostra "não", ou depois do espaço): a palavra no
        // centro da barra (a posição da sugestão principal). Emojis só se não
        // houver nenhuma palavra.
        List<AccessibilityNodeInfo> words = new ArrayList<>();
        for (AccessibilityNodeInfo n : row) {
            if (!isEmojiSuggestion(text(n))) {
                words.add(n);
            }
        }
        List<AccessibilityNodeInfo> pick = words.isEmpty() ? row : words;
        String t = text(nearestToCenter(pick, screenCenter)); // centro da tela: onde fica a sugestão principal
        return typed != null && t.equalsIgnoreCase(typed) ? "" : t; // igual ao digitado: nada a avisar
    }

    private static boolean isEmojiSuggestion(String t) {
        String l = t.toLowerCase(Locale.ROOT);
        if (l.startsWith("emoji")) {
            return true;
        }
        int cp = t.isEmpty() ? 0 : t.codePointAt(0);
        return cp >= 0x1F000 || (cp >= 0x2600 && cp <= 0x27BF) || (cp >= 0x2B00 && cp <= 0x2BFF);
    }

    private static AccessibilityNodeInfo nearestToCenter(List<AccessibilityNodeInfo> list, int center) {
        AccessibilityNodeInfo best = list.get(0);
        int bestDist = Integer.MAX_VALUE;
        for (AccessibilityNodeInfo n : list) {
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            int d = Math.abs(r.centerX() - center);
            if (d < bestDist) {
                bestDist = d;
                best = n;
            }
        }
        return best;
    }

    private boolean keyboardVisible;

    /**
     * Palavra que está sendo digitada (as letras logo antes do cursor), "" logo depois de um espaço,
     * ou null se não der para saber. O texto do campo é lido na hora e não é guardado.
     */
    private String typedWord() {
        try {
            AccessibilityNodeInfo app = getRootInActiveWindow();
            AccessibilityNodeInfo input = app != null ? app.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) : null;
            if (input == null) {
                return null;
            }
            if (android.os.Build.VERSION.SDK_INT >= 26 && input.isShowingHintText()) {
                return "";
            }
            CharSequence t = input.getText();
            if (t == null) {
                return "";
            }
            int end = input.getTextSelectionEnd();
            if (end < 0 || end > t.length()) {
                end = t.length();
            }
            int start = end;
            while (start > 0 && Character.isLetterOrDigit(Character.codePointBefore(t, start))) {
                start -= Character.charCount(Character.codePointBefore(t, start));
            }
            return t.subSequence(start, end).toString();
        } catch (Exception e) {
            return null;
        }
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
        // Só usado nos testes: o relatório não registra mais as barras lidas.
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
        if (prefsWatch != null) {
            Prefs.get(this).unregisterOnSharedPreferenceChangeListener(prefsWatch);
        }
        if (tts != null) {
            tts.shutdown();
        }
        super.onDestroy();
    }
}
