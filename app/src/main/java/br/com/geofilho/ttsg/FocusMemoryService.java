package br.com.geofilho.ttsg;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;

/**
 * "Lembrar o foco ao voltar" (teste): nos aplicativos escolhidos, acompanha o item de lista em que
 * está o foco do leitor de tela. Quando a pessoa entra nele (um grupo, uma pasta) e volta, coloca o
 * foco de novo nesse item, para continuar navegando de onde parou. Guarda um item por nível
 * (pasta dentro de pasta).
 *
 * Fica separado da fala: só recebe eventos dos aplicativos escolhidos (os outros, como apps de
 * banco, nem mandam eventos) e só procura o item na tela enquanto há um item guardado esperando a volta.
 */
public class FocusMemoryService extends AccessibilityService {

    private final Handler handler = new Handler(Looper.getMainLooper());

    /** Um item de lista lembrado: nome (início do texto, que não muda), texto completo, lista e aplicativo. */
    private static final class Entry {
        final String key;
        final String label;
        final String app;
        final AccessibilityNodeInfo list;
        long at;
        /** Quando entrou na pilha. */
        long pushedAt;

        Entry(String label, String app, AccessibilityNodeInfo list) {
            this.key = key(label);
            this.label = label;
            this.app = app;
            this.list = list;
            this.at = SystemClock.uptimeMillis();
        }
    }

    /** Item da lista em que o foco está agora. */
    private Entry current;
    /**
     * Itens em que a pessoa entrou, um por nível (pasta dentro de pasta): o de cima é o mais recente.
     * Ao voltar, o primeiro deles que reaparece na tela recebe o foco.
     */
    private final java.util.ArrayDeque<Entry> stack = new java.util.ArrayDeque<>();
    private static final int MAX_LEVELS = 20;
    /**
     * Item que parece ter sumido (o foco foi para outro item e ele não estava na tela). Só conta como
     * "entrou nele" se continuar sumido um pouco depois: a lista do Telegram se reorganiza sozinha.
     */
    private Entry maybeLeft;
    private long maybeLeftAt;
    /** Desde quando o item atual não aparece na tela (0 = está aparecendo). */
    private long currentMissingSince;
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
            focusSeen.clear();
            pollNoted = false;
            Diagnostics.note(this, "Lembrar o foco: ligado em " + android.text.TextUtils.join(", ", apps) + ".");
        } catch (Exception ignored) {
        }
    }

    /** Última rolagem da lista: sumir da tela logo depois de rolar não é "entrar no item". */
    private long lastScroll;
    private long lastWindowChange;

    /** O item pode ter sumido por rolagem? Só se rolou há pouco e a tela não mudou depois. */
    private boolean recentlyScrolled() {
        return SystemClock.uptimeMillis() - lastScroll < 1500 && lastWindowChange < lastScroll;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) {
            return;
        }
        int type = event.getEventType();
        noteFirst(event);
        if (type == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            // Só conta a rolagem da lista do item atual (a conversa que abre também rola).
            AccessibilityNodeInfo src = event.getSource();
            if (current == null || current.list == null || (src != null && src.equals(current.list))) {
                lastScroll = SystemClock.uptimeMillis();
            }
        } else if (type == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED
                || type == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            // O item da lista em que o foco está (ou que foi tocado). Não depende do toque: alguns apps,
            // como o Telegram, abrem o item sem avisar o toque.
            AccessibilityNodeInfo src = event.getSource();
            if (type == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED
                    && focusSeen.add(String.valueOf(event.getPackageName()))) {
                Diagnostics.note(this, "Lembrar o foco (detalhe): primeiro aviso de foco de " + event.getPackageName() + ".");
            }
            if (listItem(src) == null && type == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED) {
                noteDetail(src);
            }
            remember(src);
        } else {
            if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                lastWindowChange = SystemClock.uptimeMillis(); // tela ou título novo: navegação, não rolagem
            }
            // Alguns aplicativos (como o Telegram) não avisam quando o foco do leitor de tela muda:
            // quando a tela muda, pergunta onde o foco está (no máximo a cada 0,4 s).
            handler.removeCallbacks(poll);
            handler.postDelayed(poll, 400);
        }
        if (current == null && stack.isEmpty()) {
            return; // nada guardado: não faz mais nada
        }
        // Espera a tela assentar (o leitor de tela também mexe no foco ao voltar) e confere uma vez.
        handler.removeCallbacks(check);
        handler.postDelayed(check, stack.isEmpty() ? 700 : 350);
    }

    /** O foco está num item de lista: ele passa a ser o atual. Se o atual anterior sumiu, a pessoa entrou nele. */
    private void remember(AccessibilityNodeInfo source) {
        AccessibilityNodeInfo item = listItem(source);
        String label = item != null ? label(item) : null;
        if (label == null || label.length() < 2) {
            return; // não é item de lista: um botão, por exemplo
        }
        String pkg = source.getPackageName() != null ? source.getPackageName().toString() : "";
        if (current != null && current.label.equals(label) && current.app.equals(pkg)) {
            current.at = SystemClock.uptimeMillis();
            return;
        }
        if (current != null && current.app.equals(pkg)) {
            // Outro item do mesmo aplicativo. Se o atual ainda está na tela, a pessoa só andou pela lista.
            // Se sumiu sem a lista ter sido rolada, ela entrou nele (num grupo, numa pasta...), mesmo que a
            // lista seja a mesma (o Meus Arquivos troca os itens da mesma lista ao abrir uma pasta).
            AccessibilityNodeInfo root = root();
            if (root != null && find(root, current) == null && !recentlyScrolled()) {
                maybeLeft = current; // confirma na próxima conferência
                maybeLeftAt = SystemClock.uptimeMillis();
            }
        }
        if (recognized.add(pkg)) {
            AccessibilityNodeInfo list = item.getParent();
            Diagnostics.note(this, "Lembrar o foco (detalhe): reconheceu lista em " + pkg + ", item \""
                    + shortLabel(label) + "\" (" + shortClass(list != null ? list.getClassName() : null) + ").");
        }
        current = new Entry(label, pkg, item.getParent());
        currentMissingSince = 0;
    }

    /** A pessoa entrou no item: guarda na pilha, para voltar o foco a ele. */
    private void enter(Entry e) {
        if (!stack.isEmpty() && stack.peek().label.equals(e.label) && stack.peek().app.equals(e.app)) {
            return;
        }
        Entry top = stack.peek();
        boolean replaced = false;
        if (top != null && top.list != null && top.list.equals(e.list) && top.app.equals(e.app)
                && lastWindowChange <= top.pushedAt) {
            // Mesma lista e a tela não mudou: não é um nível novo (são mensagens saindo da tela dentro de
            // um grupo, por exemplo). Troca o de cima, em vez de empilhar.
            stack.pop();
            replaced = true;
        }
        stack.push(e);
        e.at = SystemClock.uptimeMillis();
        e.pushedAt = e.at;
        if (replaced) {
            if (current == e) {
                current = null;
            }
            return; // sem aviso: é só o item de dentro mudando
        }
        while (stack.size() > MAX_LEVELS) {
            stack.removeLast();
        }
        Diagnostics.note(this, "Lembrar o foco: entrou em \"" + e.key + "\" (" + e.app + "), nível " + stack.size()
                + "; esperando a volta.");
        if (!isChatApp(e.app)) {
            enteredAt = SystemClock.uptimeMillis();
            handler.removeCallbacks(firstItem);
            handler.postDelayed(firstItem, 900);
        }
        if (current == e) {
            current = null;
        }
    }

    // ----- ao entrar numa pasta: foco no primeiro item, se o leitor de tela não pôs em nenhum -----

    private long enteredAt;

    private final Runnable firstItem = new Runnable() {
        @Override
        public void run() {
            focusFirstItem();
        }
    };

    /** Aplicativos de conversa: ao abrir um grupo, o primeiro item seria a mensagem mais antiga. */
    private static boolean isChatApp(String pkg) {
        return pkg.startsWith("org.telegram") || pkg.equals("org.thunderdog.challegram")
                || pkg.startsWith("com.whatsapp");
    }

    private void focusFirstItem() {
        if (SystemClock.uptimeMillis() - enteredAt > 3000) {
            return;
        }
        AccessibilityNodeInfo root = root();
        if (root == null) {
            return;
        }
        AccessibilityNodeInfo focused = null;
        try {
            focused = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
        } catch (Exception ignored) {
        }
        if (focused != null && listItem(focused) != null) {
            return; // o leitor de tela já pôs o foco num item da lista
        }
        AccessibilityNodeInfo list = biggestList(root, 0);
        if (list == null) {
            return;
        }
        for (int i = 0; i < list.getChildCount(); i++) {
            AccessibilityNodeInfo child = list.getChild(i);
            String l = child != null ? label(child) : null;
            if (child != null && child.isVisibleToUser() && l != null && l.length() >= 2) {
                boolean ok = child.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS);
                Diagnostics.note(this, "Lembrar o foco: ao entrar, pôs o foco no primeiro item, \"" + key(l) + "\""
                        + (ok ? "." : ", mas o aplicativo não aceitou."));
                return;
            }
        }
    }

    /** A lista com mais itens na tela (a principal), sem contar barras de abas. */
    private static AccessibilityNodeInfo biggestList(AccessibilityNodeInfo n, int depth) {
        if (n == null || depth > 25) {
            return null;
        }
        AccessibilityNodeInfo best = isList(n) && !isTabBar(n) && n.getChildCount() > 0 ? n : null;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo found = biggestList(n.getChild(i), depth + 1);
            if (found != null && (best == null || found.getChildCount() > best.getChildCount())) {
                best = found;
            }
        }
        return best;
    }

    private void forget() {
        current = null;
        stack.clear();
        handler.removeCallbacks(check);
    }

    private AccessibilityNodeInfo root() {
        try {
            return getRootInActiveWindow();
        } catch (Exception e) {
            return null;
        }
    }

    private void checkReturn() {
        AccessibilityNodeInfo root = root();
        if (root == null) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        while (!stack.isEmpty() && now - stack.peekLast().at > KEEP_MS) {
            stack.removeLast(); // muito antigo: esquece
        }
        String pkg = root.getPackageName() != null ? root.getPackageName().toString() : "";
        // Voltou? O primeiro item da pilha (do mais recente para o mais antigo) que reaparece na tela.
        int level = 0;
        for (Entry e : stack) {
            level++;
            if (!e.app.equals(pkg)) {
                continue;
            }
            AccessibilityNodeInfo target = find(root, e);
            if (target != null) {
                for (int i = 0; i < level; i++) {
                    stack.pop(); // ele e os níveis mais fundos, que já ficaram para trás
                }
                current = e;
                e.at = now;
                restore(target, e);
                return;
            }
        }
        // O item que parecia ter sumido continua sumido: a pessoa entrou nele.
        if (maybeLeft != null && now - maybeLeftAt >= 400) {
            Entry m = maybeLeft;
            maybeLeft = null;
            if (m.app.equals(pkg) && find(root, m) == null && !recentlyScrolled()) {
                enter(m);
            }
        } else if (maybeLeft != null) {
            handler.removeCallbacks(check);
            handler.postDelayed(check, 450);
        }
        // O item atual sumiu da tela sem rolagem e sem outro foco: confirma de novo antes de contar.
        if (current != null && current.app.equals(pkg) && find(root, current) == null && !recentlyScrolled()) {
            if (currentMissingSince == 0) {
                currentMissingSince = now;
                handler.removeCallbacks(check);
                handler.postDelayed(check, 450);
            } else if (now - currentMissingSince >= 400) {
                currentMissingSince = 0;
                enter(current);
            }
        } else {
            currentMissingSince = 0;
        }
    }

    private void restore(AccessibilityNodeInfo target, Entry e) {
        restoring = e;
        maybeLeft = null;
        currentMissingSince = 0;
        if (target.isAccessibilityFocused()) {
            Diagnostics.note(this, "Lembrar o foco: voltou, e o foco já estava em \"" + e.key + "\".");
        } else {
            boolean ok = target.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS);
            Diagnostics.note(this, "Lembrar o foco: voltou; pôs o foco em \"" + e.key + "\""
                    + (ok ? ", conferindo se ficou." : ", mas o aplicativo não aceitou o foco."));
        }
        verifyAttempt = 0;
        handler.removeCallbacks(verify);
        handler.postDelayed(verify, 900);
    }

    // ----- conferência: o leitor de tela pode mudar o foco logo depois -----

    private Entry restoring;
    private int verifyAttempt;

    private final Runnable verify = new Runnable() {
        @Override
        public void run() {
            verifyFocus();
        }
    };

    /** Confere onde o foco ficou; se o leitor de tela o levou para outro lugar, põe de novo uma vez. */
    private void verifyFocus() {
        Entry e = restoring;
        if (e == null) {
            return;
        }
        AccessibilityNodeInfo root = root();
        if (root == null) {
            return;
        }
        AccessibilityNodeInfo target = find(root, e);
        AccessibilityNodeInfo focused = null;
        try {
            focused = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
        } catch (Exception ignored) {
        }
        String where = focused != null && label(focused) != null ? shortLabel(label(focused)) : "nenhum item deste aplicativo";
        AccessibilityNodeInfo focusedItem = focused != null ? listItem(focused) : null;
        String focusedLabel = focusedItem != null ? label(focusedItem) : null;
        boolean sameItem = focusedLabel != null && key(focusedLabel).equals(e.key);
        if ((target != null && target.isAccessibilityFocused()) || sameItem) {
            Diagnostics.note(this, "Lembrar o foco: confirmado, o foco ficou em \"" + e.key + "\".");
            restoring = null;
            return;
        }
        if (target == null) {
            if (verifyAttempt < 2) {
                // A lista pode estar se reorganizando (o Telegram muda a ordem quando chegam mensagens).
                verifyAttempt++;
                handler.postDelayed(verify, 700);
                return;
            }
            Diagnostics.note(this, "Lembrar o foco: ao conferir, \"" + e.key + "\" não estava mais na tela. Foco em: "
                    + where + ".");
            restoring = null;
            return;
        }
        if (verifyAttempt < 2) {
            verifyAttempt++;
            current = e; // o leitor de tela tirou o foco do item: volta a ser o atual e tenta de novo
            e.at = SystemClock.uptimeMillis();
            boolean ok = target.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS);
            Diagnostics.note(this, "Lembrar o foco: o foco foi para \"" + where + "\"; pus de novo em \""
                    + e.key + "\"" + (ok ? "." : ", mas o aplicativo não aceitou."));
            handler.postDelayed(verify, 900);
            return;
        }
        Diagnostics.note(this, "Lembrar o foco: não ficou em \"" + e.key + "\"; o foco está em \"" + where + "\".");
        restoring = null;
    }

    private static AccessibilityNodeInfo find(AccessibilityNodeInfo root, Entry e) {
        return find(root, e.key, e.label);
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
        for (int i = 0; n != null && i < 10; i++) {
            AccessibilityNodeInfo parent = n.getParent();
            if (parent != null && isList(parent)) {
                return isTabBar(parent) ? null : n; // abas ("Recentes", "Navegar") não são itens de lista
            }
            n = parent;
        }
        return null;
    }

    /** Barra de abas ou de navegação: uma linha só, na horizontal. Não é lembrada. */
    private static boolean isTabBar(AccessibilityNodeInfo list) {
        CharSequence c = list.getClassName();
        String cls = c != null ? c.toString() : "";
        if (cls.endsWith("HorizontalScrollView") || cls.contains("TabLayout") || cls.contains("TabWidget")
                || cls.contains("BottomNavigation") || cls.contains("NavigationBar")) {
            return true;
        }
        // Uma grade de uma linha só (as categorias e os recentes do Meus Arquivos) é lista de verdade:
        // só conta como barra de abas pelo tipo.
        return false;
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

    private final java.util.Set<String> focusSeen = new java.util.HashSet<>();
    private AccessibilityNodeInfo lastPolled;
    private boolean pollNoted;

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            pollFocus();
        }
    };

    /** Pergunta ao aplicativo onde está o foco do leitor de tela e guarda, se for item de lista. */
    private void pollFocus() {
        AccessibilityNodeInfo root;
        AccessibilityNodeInfo focused;
        try {
            root = getRootInActiveWindow();
            focused = root != null ? root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY) : null;
        } catch (Exception e) {
            return;
        }
        if (focused == null || focused.equals(lastPolled)) {
            return;
        }
        lastPolled = focused;
        if (!pollNoted) {
            pollNoted = true;
            String l = label(focused);
            Diagnostics.note(this, "Lembrar o foco (detalhe): perguntou o foco a " + focused.getPackageName() + ": \""
                    + (l != null ? shortLabel(l) : "sem texto") + "\" (" + shortClass(focused.getClassName())
                    + (listItem(focused) != null ? ", item de lista" : ", não é item de lista") + ").");
        }
        if (listItem(focused) == null) {
            noteDetail(focused);
        }
        remember(focused);
        if (current != null || !stack.isEmpty()) {
            handler.removeCallbacks(check);
            handler.postDelayed(check, stack.isEmpty() ? 700 : 350);
        }
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
        if (l == null || l.length() < 10) {
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

    /**
     * Palavras de tipo que alguns aplicativos põem antes do nome (o Telegram fala "Canal. Nome do canal.
     * Silenciado..."): não servem para identificar o item, então o nome vai junto.
     */
    private static final java.util.Set<String> TYPE_WORDS = new java.util.HashSet<>(java.util.Arrays.asList(
            "canal", "grupo", "supergrupo", "bot", "chat", "chat secreto", "conversa secreta", "fixado", "fixada",
            "contato", "verificado", "silenciado", "arquivado", "channel", "group", "supergroup", "secret chat",
            "pinned", "contact", "verified", "muted", "archived"));

    /**
     * Nome do item: o começo do texto, até a primeira vírgula, ponto ou quebra de linha. Se esse
     * começo for só o tipo ("Canal", "Grupo"), inclui os pedaços seguintes até chegar ao nome.
     */
    static String key(String label) {
        int pos = 0;
        int end = 0;
        for (int part = 0; part < 4; part++) {
            int next = nextBreak(label, pos);
            String piece = label.substring(pos, next).trim();
            end = next;
            if (!TYPE_WORDS.contains(piece.toLowerCase(java.util.Locale.ROOT)) || next >= label.length()) {
                break;
            }
            pos = Math.min(label.length(), next + 1); // pula o separador
        }
        String k = label.substring(0, end).trim();
        if (k.length() > 80) {
            k = k.substring(0, 80);
        }
        return k.length() >= 2 ? k : label;
    }

    /** Posição do próximo ", ", ". " ou quebra de linha a partir de {@code from} (ou o fim do texto). */
    private static int nextBreak(String label, int from) {
        int end = label.length();
        String[] marks = {", ", ". ", "\n"};
        for (String m : marks) {
            int i = label.indexOf(m, from);
            if (i >= from && i < end) {
                end = i;
            }
        }
        return end;
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(check);
        handler.removeCallbacks(verify);
        handler.removeCallbacks(poll);
        handler.removeCallbacks(firstItem);
        if (prefsListener != null) {
            Prefs.get(this).unregisterOnSharedPreferenceChangeListener(prefsListener);
        }
        super.onDestroy();
    }
}
