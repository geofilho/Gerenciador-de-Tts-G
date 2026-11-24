package br.com.geofilho.ttsg;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;

/**
 * "Lembrar o foco ao voltar" (teste, só no Telegram): ao tocar num item de uma lista (por
 * exemplo, um grupo), guarda qual era. Quando a pessoa volta para a lista, coloca o foco do
 * leitor de tela de novo nesse item, para continuar navegando de onde parou.
 *
 * Fica separado da fala: só recebe eventos do Telegram (packageNames no XML) e só procura o
 * item na tela enquanto há um item guardado esperando a volta.
 */
public class FocusMemoryService extends AccessibilityService {

    private final Handler handler = new Handler(Looper.getMainLooper());

    /** Texto completo do item tocado (nome do grupo, última mensagem...). */
    private String savedLabel;
    /** Início do texto, que não muda: o nome do grupo. É por ele que o item é procurado. */
    private String savedKey;
    /** Já saiu da lista (o item sumiu da tela)? Só depois disso a volta é esperada. */
    private boolean leftList;
    private long savedAt;
    private static final long KEEP_MS = 30 * 60 * 1000;

    private final Runnable check = new Runnable() {
        @Override
        public void run() {
            checkReturn();
        }
    };

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) {
            return;
        }
        if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            remember(event.getSource());
            return;
        }
        if (savedKey == null) {
            return; // nada guardado: não faz nada (sem custo)
        }
        if (SystemClock.uptimeMillis() - savedAt > KEEP_MS) {
            forget();
            return;
        }
        // Espera a tela assentar (o leitor de tela também mexe no foco ao voltar) e confere uma vez.
        handler.removeCallbacks(check);
        handler.postDelayed(check, leftList ? 350 : 600);
    }

    /** Tocou num item: se ele faz parte de uma lista rolável, guarda. */
    private void remember(AccessibilityNodeInfo source) {
        AccessibilityNodeInfo item = listItem(source);
        String label = item != null ? label(item) : null;
        if (label == null || label.length() < 2) {
            return; // não é item de lista: um botão, por exemplo
        }
        if (savedKey != null && leftList) {
            // Já está dentro do grupo: toques ali (numa mensagem, por exemplo) não trocam o item
            // guardado. Só troca se a lista com o grupo guardado estiver na tela de novo.
            AccessibilityNodeInfo root = null;
            try {
                root = getRootInActiveWindow();
            } catch (Exception ignored) {
            }
            if (root == null || find(root) == null) {
                return;
            }
        }
        savedLabel = label;
        savedKey = key(label);
        leftList = false;
        savedAt = SystemClock.uptimeMillis();
        handler.removeCallbacks(check);
        handler.postDelayed(check, 800);
    }

    private void forget() {
        savedLabel = null;
        savedKey = null;
        leftList = false;
        handler.removeCallbacks(check);
    }

    private void checkReturn() {
        if (savedKey == null) {
            return;
        }
        AccessibilityNodeInfo root;
        try {
            root = getRootInActiveWindow();
        } catch (Exception e) {
            return;
        }
        if (root == null) {
            return;
        }
        AccessibilityNodeInfo target = find(root);
        if (!leftList) {
            if (target == null) {
                leftList = true; // entrou no grupo: agora espera a volta para a lista
            }
            return;
        }
        if (target == null) {
            return; // ainda dentro do grupo (ou em outra tela)
        }
        String key = savedKey;
        forget();
        if (!target.isAccessibilityFocused()) {
            boolean ok = target.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS);
            Diagnostics.note(this, "Lembrar o foco: voltou para \"" + key + "\"" + (ok ? "." : ", mas o Telegram não aceitou o foco."));
        }
    }

    /** Procura na tela o item guardado: começa com o mesmo nome e está numa lista rolável. */
    private AccessibilityNodeInfo find(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> found;
        try {
            found = root.findAccessibilityNodeInfosByText(savedKey);
        } catch (Exception e) {
            return null;
        }
        if (found == null) {
            return null;
        }
        AccessibilityNodeInfo best = null;
        for (AccessibilityNodeInfo n : found) {
            AccessibilityNodeInfo item = listItem(n);
            String l = item != null ? label(item) : null;
            if (l == null || !l.startsWith(savedKey)) {
                continue;
            }
            if (l.equals(savedLabel)) {
                return item; // igualzinho: é ele
            }
            if (best == null) {
                best = item; // mesmo nome, última mensagem diferente
            }
        }
        return best;
    }

    /** Sobe a partir do nó até o item cujo pai é a lista rolável. */
    private static AccessibilityNodeInfo listItem(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo n = node;
        for (int i = 0; n != null && i < 6; i++) {
            AccessibilityNodeInfo parent = n.getParent();
            if (parent != null && parent.isScrollable()) {
                return n;
            }
            n = parent;
        }
        return null;
    }

    /** O que o leitor de tela fala do item: a descrição ou o texto dele e dos filhos. */
    private static String label(AccessibilityNodeInfo n) {
        CharSequence d = n.getContentDescription();
        if (d != null && d.toString().trim().length() > 0) {
            return d.toString().trim();
        }
        StringBuilder b = new StringBuilder();
        collectText(n, b, 0);
        String s = b.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static void collectText(AccessibilityNodeInfo n, StringBuilder b, int depth) {
        if (n == null || depth > 4 || b.length() > 300) {
            return;
        }
        CharSequence t = n.getText();
        if (t != null && t.length() > 0) {
            if (b.length() > 0) {
                b.append(", ");
            }
            b.append(t);
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            collectText(n.getChild(i), b, depth + 1);
        }
    }

    /** Nome do grupo: o começo do texto, até a primeira vírgula, ponto ou quebra de linha. */
    static String key(String label) {
        int end = label.length();
        String[] marks = {", ", ". ", "\n"};
        for (String m : marks) {
            int i = label.indexOf(m);
            if (i > 0 && i < end) {
                end = i;
            }
        }
        String k = label.substring(0, end).trim();
        if (k.length() > 60) {
            k = k.substring(0, 60);
        }
        return k.length() >= 2 ? k : label;
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(check);
        super.onDestroy();
    }
}
