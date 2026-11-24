package br.com.geofilho.ttsg;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;

/**
 * "Lembrar o foco ao voltar" (teste): nos aplicativos escolhidos, ao tocar num item de uma lista (por
 * exemplo, um grupo), guarda qual era. Quando a pessoa volta para a lista, coloca o foco do
 * leitor de tela de novo nesse item, para continuar navegando de onde parou.
 *
 * Fica separado da fala: só recebe eventos dos aplicativos escolhidos (os outros, como apps de
 * banco, nem mandam eventos) e só procura o item na tela enquanto há um item guardado esperando a volta.
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
    private String savedApp = "";
    private static final long KEEP_MS = 30 * 60 * 1000;

    private final Runnable check = new Runnable() {
        @Override
        public void run() {
            checkReturn();
        }
    };

    private android.content.SharedPreferences.OnSharedPreferenceChangeListener prefsListener;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        applyApps();
        prefsListener = new android.content.SharedPreferences.OnSharedPreferenceChangeListener() {
            @Override
            public void onSharedPreferenceChanged(android.content.SharedPreferences p, String key) {
                if ("focus_apps".equals(key)) {
                    applyApps();
                }
            }
        };
        Prefs.get(this).registerOnSharedPreferenceChangeListener(prefsListener);
    }

    /** Recebe eventos só dos aplicativos escolhidos. Nenhum escolhido: só do próprio Gerenciador (nada). */
    private void applyApps() {
        try {
            android.accessibilityservice.AccessibilityServiceInfo info = getServiceInfo();
            if (info == null) {
                return;
            }
            java.util.Set<String> apps = Prefs.focusApps(Prefs.get(this));
            if (apps.isEmpty()) {
                apps.add(getPackageName());
            }
            info.packageNames = apps.toArray(new String[0]);
            setServiceInfo(info);
            forget();
            detailNotes = 0;
            seenApps.clear();
            recognized.clear();
            Diagnostics.note(this, "Lembrar o foco: ligado em " + android.text.TextUtils.join(", ", apps) + ".");
        } catch (Exception ignored) {
        }
    }

    /** Última rolagem da lista: sumir da tela logo depois de rolar não é "entrar no item". */
    private long lastScroll;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) {
            return;
        }
        int type = event.getEventType();
        noteFirst(event);
        if (type == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            // Só conta a rolagem da lista do item guardado (a conversa que abre também rola).
            AccessibilityNodeInfo src = event.getSource();
            if (savedList == null || (src != null && src.equals(savedList))) {
                lastScroll = SystemClock.uptimeMillis();
            }
        } else if (!leftList && (type == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED
                || type == AccessibilityEvent.TYPE_VIEW_CLICKED)) {
            // O item da lista em que o foco está (ou que foi tocado) é o que será lembrado.
            // Não depende do toque: alguns apps, como o Telegram, abrem o item sem avisar o toque.
            AccessibilityNodeInfo src = event.getSource();
            if (listItem(src) == null && type == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED) {
                noteDetail(src);
            }
            remember(src);
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
        handler.postDelayed(check, leftList ? 350 : 700);
    }

    /** Lista (rolável) do item guardado: itens de outras listas, como as mensagens, não trocam o guardado. */
    private AccessibilityNodeInfo savedList;

    /** Item de uma lista rolável: guarda (sem aviso; o aviso sai só ao entrar e ao voltar). */
    private void remember(AccessibilityNodeInfo source) {
        AccessibilityNodeInfo item = listItem(source);
        String label = item != null ? label(item) : null;
        if (label == null || label.length() < 2) {
            return; // não é item de lista: um botão, por exemplo
        }
        AccessibilityNodeInfo list = item.getParent();
        String pkg = source.getPackageName() != null ? source.getPackageName().toString() : "";
        if (savedKey != null && savedList != null && list != null && !list.equals(savedList)
                && pkg.equals(savedApp)) {
            // Item de outra lista no mesmo aplicativo. Se o item guardado ainda está na tela, a pessoa
            // só passou para outra lista da mesma tela: o item novo passa a ser o guardado. Se não está,
            // ela entrou no item (as mensagens do grupo, por exemplo): continua com o guardado.
            AccessibilityNodeInfo root = null;
            try {
                root = getRootInActiveWindow();
            } catch (Exception ignored) {
            }
            if (root != null && find(root) == null) {
                if (SystemClock.uptimeMillis() - lastScroll > 1500) {
                    leftList = true;
                    Diagnostics.note(this, "Lembrar o foco: saiu da lista em " + savedApp + ", esperando a volta para \""
                            + savedKey + "\" (item: " + shortLabel(savedLabel) + ").");
                }
                return;
            }
        }
        if (recognized.add(pkg)) {
            Diagnostics.note(this, "Lembrar o foco (detalhe): reconheceu lista em " + pkg + ", item \""
                    + shortLabel(label) + "\" (" + shortClass(list != null ? list.getClassName() : null) + ").");
        }
        savedList = list;
        savedLabel = label;
        savedKey = key(label);
        savedApp = source != null && source.getPackageName() != null ? source.getPackageName().toString() : "";
        savedAt = SystemClock.uptimeMillis();
    }

    private void forget() {
        savedList = null;
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
            // O item sumiu da tela sem a lista ter sido rolada: a pessoa entrou nele (ou abriu
            // outra tela por cima). Agora espera a volta.
            if (target == null && SystemClock.uptimeMillis() - lastScroll > 1500) {
                leftList = true;
                Diagnostics.note(this, "Lembrar o foco: saiu da lista em " + savedApp + ", esperando a volta para \""
                        + savedKey + "\" (item: " + shortLabel(savedLabel) + ").");
            }
            return;
        }
        if (target == null) {
            return; // ainda dentro do item (ou em outra tela)
        }
        leftList = false; // de volta à lista: o item continua guardado até o foco ir para outro
        savedAt = SystemClock.uptimeMillis();
        restoreKey = savedKey;
        restoreLabel = savedLabel;
        restoreList = savedList;
        if (target.isAccessibilityFocused()) {
            Diagnostics.note(this, "Lembrar o foco: o foco já estava em \"" + savedKey + "\".");
        } else {
            boolean ok = target.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS);
            Diagnostics.note(this, "Lembrar o foco: pôs o foco em \"" + savedKey + "\""
                    + (ok ? "; conferindo se ficou." : ", mas o aplicativo não aceitou o foco."));
        }
        verifyAttempt = 0;
        handler.removeCallbacks(verify);
        handler.postDelayed(verify, 900);
    }

    // ----- conferência: o leitor de tela pode mudar o foco logo depois -----

    private String restoreKey;
    private String restoreLabel;
    private AccessibilityNodeInfo restoreList;
    private int verifyAttempt;

    private final Runnable verify = new Runnable() {
        @Override
        public void run() {
            verifyFocus();
        }
    };

    /** Confere onde o foco ficou; se o leitor de tela o levou para outro lugar, põe de novo uma vez. */
    private void verifyFocus() {
        if (restoreKey == null) {
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
        AccessibilityNodeInfo target = find(root, restoreKey, restoreLabel);
        AccessibilityNodeInfo focused = null;
        try {
            focused = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
        } catch (Exception ignored) {
        }
        String where = focused != null && label(focused) != null ? shortLabel(label(focused)) : "nenhum item deste aplicativo";
        if (target != null && target.isAccessibilityFocused()) {
            Diagnostics.note(this, "Lembrar o foco: confirmado, o foco ficou em \"" + restoreKey + "\".");
            restoreKey = null;
            return;
        }
        if (target == null) {
            Diagnostics.note(this, "Lembrar o foco: ao conferir, \"" + restoreKey + "\" não estava mais na tela. Foco em: "
                    + where + ".");
            restoreKey = null;
            return;
        }
        if (verifyAttempt == 0) {
            verifyAttempt = 1;
            // O leitor de tela tirou o foco do item: volta o item guardado e tenta de novo.
            savedKey = restoreKey;
            savedLabel = restoreLabel;
            savedList = restoreList;
            savedAt = SystemClock.uptimeMillis();
            boolean ok = target.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS);
            Diagnostics.note(this, "Lembrar o foco: o foco foi para \"" + where + "\"; pus de novo em \""
                    + restoreKey + "\"" + (ok ? "." : ", mas o aplicativo não aceitou."));
            handler.postDelayed(verify, 900);
            return;
        }
        Diagnostics.note(this, "Lembrar o foco: não ficou em \"" + restoreKey + "\"; o foco está em \"" + where + "\".");
        restoreKey = null;
    }

    /** Procura na tela o item guardado: começa com o mesmo nome e está numa lista rolável. */
    private AccessibilityNodeInfo find(AccessibilityNodeInfo root) {
        return find(root, savedKey, savedLabel);
    }

    private static AccessibilityNodeInfo find(AccessibilityNodeInfo root, String savedKey, String savedLabel) {
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
            if (parent != null && isList(parent)) {
                return n;
            }
            n = parent;
        }
        return null;
    }

    /** É uma lista? Rolável, ou que se apresenta como lista (alguns apps, como o Telegram, não dizem que rolam). */
    private static boolean isList(AccessibilityNodeInfo n) {
        if (n.isScrollable() || n.getCollectionInfo() != null) {
            return true;
        }
        CharSequence c = n.getClassName();
        String cls = c != null ? c.toString() : "";
        return cls.endsWith("RecyclerView") || cls.endsWith("ListView") || cls.endsWith("GridView")
                || cls.endsWith("RecyclerListView");
    }

    /** Aplicativos dos quais já chegou algum evento / já houve lista reconhecida (desde que o serviço ligou). */
    private final java.util.Set<String> seenApps = new java.util.HashSet<>();
    private final java.util.Set<String> recognized = new java.util.HashSet<>();

    /** Primeiro evento de cada aplicativo: mostra no relatório que o aplicativo está mandando informações. */
    private void noteFirst(AccessibilityEvent e) {
        CharSequence p = e.getPackageName();
        String pkg = p != null ? p.toString() : "?";
        if (!seenApps.add(pkg)) {
            return;
        }
        Diagnostics.note(this, "Lembrar o foco (detalhe): primeiro sinal de " + pkg + ": "
                + AccessibilityEvent.eventTypeToString(e.getEventType()) + ", " + shortClass(e.getClassName()) + ".");
    }

    /** Detalhes para o relatório, quando o foco cai em algo que não parece item de lista (só no teste). */
    private int detailNotes;

    private void noteDetail(AccessibilityNodeInfo source) {
        if (detailNotes >= 4) {
            return;
        }
        if (source == null) {
            detailNotes++;
            Diagnostics.note(this, "Lembrar o foco (detalhe): o aplicativo não informou o item com foco.");
            return;
        }
        String l = label(source);
        if (l == null || l.length() < 15) {
            return; // botões curtos ("Menu", "Pesquisar"): não interessam
        }
        detailNotes++;
        StringBuilder b = new StringBuilder();
        b.append("Lembrar o foco (detalhe): foco em ").append(source.getPackageName()).append(", \"");
        b.append(l != null ? shortLabel(l) : "sem texto").append("\" (").append(source.getClassName()).append("); acima:");
        AccessibilityNodeInfo p = source.getParent();
        for (int i = 0; p != null && i < 6; i++) {
            b.append(' ').append(shortClass(p.getClassName()));
            if (p.isScrollable()) {
                b.append("[rola]");
            }
            if (p.getCollectionInfo() != null) {
                b.append("[lista]");
            }
            b.append('(').append(p.getChildCount()).append(')');
            p = p.getParent();
        }
        Diagnostics.note(this, b.toString() + ".");
    }

    private static String shortClass(CharSequence c) {
        String s = c != null ? c.toString() : "?";
        return s.substring(s.lastIndexOf('.') + 1);
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

    private static String shortLabel(String l) {
        return l.length() > 80 ? l.substring(0, 80) + "…" : l;
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
        handler.removeCallbacks(verify);
        if (prefsListener != null) {
            Prefs.get(this).unregisterOnSharedPreferenceChangeListener(prefsListener);
        }
        super.onDestroy();
    }
}
