package br.com.geofilho.ttsg;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * "Lembrar o foco ao voltar" (teste), no modelo do SandFly:
 * - guarda o item de lista que a pessoa abriu: o que tinha o foco quando a tela trocou;
 * - só devolve o foco nos 2 segundos seguintes a uma troca de tela, e só se a pessoa não mexeu no foco;
 * - a lista se reorganizando (o Telegram quando chegam mensagens) não é troca de tela: troca de
 *   tela é a lista sumir, ou todos os itens dela mudarem sem a lista ter sido rolada.
 * Só recebe eventos dos aplicativos escolhidos.
 */
public class FocusMemoryService extends AccessibilityService {

    private final Handler handler = new Handler(Looper.getMainLooper());

    /** Um item de lista: nome (início do texto, que não muda), texto completo, aplicativo, lista e altura. */
    private static final class Entry {
        final String key;
        final String label;
        final String app;
        final AccessibilityNodeInfo list;
        final int centerY;
        final long at;

        Entry(String label, String app, AccessibilityNodeInfo list, int centerY) {
            this.key = key(label);
            this.label = label;
            this.app = app;
            this.list = list;
            this.centerY = centerY;
            this.at = SystemClock.uptimeMillis();
        }
    }

    /** Itens abertos, o mais recente no topo (pasta dentro de pasta: um por nível). */
    private final ArrayDeque<Entry> opened = new ArrayDeque<>();
    private static final int MAX_ITEMS = 16;
    private static final long KEEP_MS = 30 * 60 * 1000;
    /** Depois de uma troca de tela, o foco só volta a um item aberto dentro deste prazo (como no SandFly). */
    private static final long RESTORE_WINDOW_MS = 2000;
    /** Foco que muda depois disto, dentro do prazo, foi a pessoa: o serviço desiste. */
    private static final long AUTO_FOCUS_MS = 1000;

    /** Item da lista em que o foco está agora. */
    private Entry focusedItem;

    /** Retrato da tela: a lista do foco e os nomes dos itens visíveis nela. */
    private AccessibilityNodeInfo shotList;
    private final Set<String> shotKeys = new HashSet<>();
    private String shotApp = "";

    private long lastScroll;

    // Prazo depois de uma troca de tela
    private long windowStart = -1;
    private Entry pushedNow; // item aberto nesta troca de tela

    private final Runnable check = new Runnable() {
        @Override
        public void run() {
            checkScreen();
        }
    };

    private final Runnable tryRestore = new Runnable() {
        @Override
        public void run() {
            restoreStep();
        }
    };

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            pollFocus();
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
            Set<String> apps = Prefs.focusApps(Prefs.get(this));
            if (apps.isEmpty()) {
                apps.add(getPackageName());
            }
            info.packageNames = apps.toArray(new String[0]);
            setServiceInfo(info);
            opened.clear();
            focusedItem = null;
            shotList = null;
            shotKeys.clear();
            closeWindow();
            seenApps.clear();
            recognized.clear();
            detailNotes = 0;
            Diagnostics.note(this, "Lembrar o foco: ligado em " + android.text.TextUtils.join(", ", apps) + ".");
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) {
            return;
        }
        noteFirst(event);
        int type = event.getEventType();
        if (type == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            AccessibilityNodeInfo src = event.getSource();
            if (shotList == null || (src != null && src.equals(shotList))) {
                lastScroll = SystemClock.uptimeMillis(); // a própria lista rolou: itens mudando não é troca de tela
            }
        } else if (type == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED) {
            onFocus(event.getSource());
        } else {
            // Alguns aplicativos (como o Telegram) nem sempre avisam a mudança de foco: pergunta.
            handler.removeCallbacks(poll);
            handler.postDelayed(poll, 400);
        }
        handler.removeCallbacks(check);
        handler.postDelayed(check, 300);
    }

    private void pollFocus() {
        AccessibilityNodeInfo root = root();
        if (root == null) {
            return;
        }
        AccessibilityNodeInfo f;
        try {
            f = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
        } catch (Exception e) {
            return;
        }
        if (f != null && (focusedItem == null || !f.equals(lastPolled))) {
            lastPolled = f;
            onFocus(f);
        }
    }

    private AccessibilityNodeInfo lastPolled;

    /** O foco do leitor de tela foi para {@code node}. */
    private void onFocus(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo item = listItem(node);
        String label = item != null ? label(item) : null;
        long now = SystemClock.uptimeMillis();
        if (item == null || label == null || label.length() < 2) {
            if (node != null && item == null) {
                noteDetail(node);
            }
            return; // não é item de lista (um botão, o título...)
        }
        String app = item.getPackageName() != null ? item.getPackageName().toString() : "";
        Entry e = new Entry(label, app, item.getParent(), centerY(item));
        if (windowStart < 0 && shotList != null
                && (e.list == null || !e.list.equals(shotList) || !shotKeys.contains(e.key))) {
            // Foco num item de outra lista, ou que não estava na tela: antes de aceitar, confere se a
            // tela trocou (o aviso de foco da tela nova pode chegar antes da conferência normal).
            handler.removeCallbacks(check);
            checkScreen();
        }
        if (windowStart >= 0 && now - windowStart > AUTO_FOCUS_MS
                && (focusedItem == null || !focusedItem.key.equals(e.key))) {
            // A pessoa mexeu no foco depois da troca de tela: o serviço não interfere mais.
            closeWindow();
        }
        if (recognized.add(app)) {
            Diagnostics.note(this, "Lembrar o foco (detalhe): reconheceu lista em " + app + ", item \""
                    + shortLabel(label) + "\".");
        }
        focusedItem = e;
        if (windowStart < 0) {
            takeShot(e.list, app); // retrato da tela atual (fora do prazo, para não confundir com a troca)
        }
    }

    /** Guarda os nomes dos itens visíveis da lista, para perceber depois se a tela trocou. */
    private void takeShot(AccessibilityNodeInfo list, String app) {
        if (list == null) {
            return;
        }
        shotList = list;
        shotApp = app;
        shotKeys.clear();
        shotKeys.addAll(visibleKeys(list));
    }

    private static Set<String> visibleKeys(AccessibilityNodeInfo list) {
        Set<String> keys = new HashSet<>();
        int n = Math.min(list.getChildCount(), 40);
        for (int i = 0; i < n; i++) {
            AccessibilityNodeInfo c = list.getChild(i);
            if (c != null && c.isVisibleToUser()) {
                String l = label(c);
                if (l != null && l.length() >= 2) {
                    keys.add(key(l));
                }
            }
        }
        return keys;
    }

    /**
     * A tela trocou? Sim se a lista do retrato sumiu, ou se nenhum dos itens dela continua lá sem a
     * lista ter sido rolada. Itens mudando de lugar (o Telegram reorganizando) não contam.
     */
    private void checkScreen() {
        if (windowStart >= 0 || shotList == null) {
            return;
        }
        boolean present;
        try {
            present = shotList.refresh() && shotList.isVisibleToUser();
        } catch (Exception e) {
            present = false;
        }
        if (present) {
            Set<String> now = visibleKeys(shotList);
            int same = 0;
            for (String k : now) {
                if (shotKeys.contains(k)) {
                    same++;
                }
            }
            boolean scrolled = SystemClock.uptimeMillis() - lastScroll < 1500;
            if (same > 0 || now.isEmpty() || scrolled) {
                if (same > 0 || scrolled) {
                    shotKeys.clear();
                    shotKeys.addAll(now); // a lista mudou aos poucos: atualiza o retrato
                }
                return;
            }
        }
        onScreenChange();
    }

    /** A tela trocou: o item que tinha o foco foi o aberto; e talvez seja a volta a uma lista anterior. */
    private void onScreenChange() {
        long now = SystemClock.uptimeMillis();
        while (!opened.isEmpty() && now - opened.peekLast().at > KEEP_MS) {
            opened.removeLast();
        }
        pushedNow = null;
        Entry f = focusedItem;
        if (f != null && f.app.equals(shotApp)) {
            Entry top = opened.peek();
            if (top == null || !(top.key.equals(f.key) && top.app.equals(f.app))) {
                opened.push(f);
                while (opened.size() > MAX_ITEMS) {
                    opened.removeLast();
                }
            }
            pushedNow = opened.peek();
        }
        focusedItem = null;
        shotList = null;
        shotKeys.clear();
        windowStart = now;
        handler.removeCallbacks(tryRestore);
        handler.postDelayed(tryRestore, 250);
    }

    /** Dentro do prazo: procura na tela o item aberto mais recente (fora o desta troca) e põe o foco nele. */
    private void restoreStep() {
        if (windowStart < 0) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        AccessibilityNodeInfo root = root();
        String app = root != null && root.getPackageName() != null ? root.getPackageName().toString() : "";
        if (root != null) {
            for (Entry e : opened) {
                if (e == pushedNow || !e.app.equals(app)) {
                    continue;
                }
                AccessibilityNodeInfo target = find(root, e);
                if (target != null) {
                    // Voltou à lista de onde esse item foi aberto: tira ele e os mais novos (níveis mais fundos).
                    Iterator<Entry> it = opened.iterator();
                    while (it.hasNext()) {
                        Entry x = it.next();
                        it.remove();
                        if (x == e) {
                            break;
                        }
                    }
                    restore(target, e);
                    return;
                }
            }
        }
        if (now - windowStart < RESTORE_WINDOW_MS) {
            handler.postDelayed(tryRestore, 250);
            return;
        }
        // Prazo acabou sem voltar a uma lista anterior: foi uma entrada.
        Entry p = pushedNow;
        closeWindow();
        if (p != null) {
            Diagnostics.note(this, "Lembrar o foco: entrou em \"" + p.key + "\" (" + p.app + "), nível " + opened.size() + ".");
            if (!isChatApp(p.app)) {
                focusFirstItem();
            }
        }
        retakeShot();
    }

    private void restore(AccessibilityNodeInfo target, Entry e) {
        closeWindow();
        if (target.isAccessibilityFocused()) {
            Diagnostics.note(this, "Lembrar o foco: voltou, e o foco já estava em \"" + e.key + "\".");
        } else {
            boolean ok = target.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS);
            Diagnostics.note(this, "Lembrar o foco: voltou; pôs o foco em \"" + e.key + "\""
                    + (ok ? "." : ", mas o aplicativo não aceitou o foco."));
            restoring = e;
            restoredAt = SystemClock.uptimeMillis();
            handler.removeCallbacks(verify);
            handler.postDelayed(verify, 800);
        }
        retakeShot();
    }

    private Entry restoring;
    private long restoredAt;

    private final Runnable verify = new Runnable() {
        @Override
        public void run() {
            verifyOnce();
        }
    };

    /** Uma conferência só: se o leitor de tela tirou o foco logo depois, põe de volta uma vez. */
    private void verifyOnce() {
        Entry e = restoring;
        restoring = null;
        if (e == null) {
            return;
        }
        AccessibilityNodeInfo root = root();
        if (root == null) {
            return;
        }
        AccessibilityNodeInfo target = find(root, e);
        if (target == null || target.isAccessibilityFocused()) {
            return;
        }
        AccessibilityNodeInfo f = null;
        try {
            f = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
        } catch (Exception ignored) {
        }
        AccessibilityNodeInfo fi = f != null ? listItem(f) : null;
        String fl = fi != null ? label(fi) : null;
        if (fl != null && key(fl).equals(e.key)) {
            return;
        }
        boolean ok = target.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS);
        Diagnostics.note(this, "Lembrar o foco: o leitor de tela levou o foco para \""
                + (fl != null ? shortLabel(fl) : "outro lugar") + "\"; pus de volta em \"" + e.key + "\""
                + (ok ? "." : ", sem sucesso."));
    }

    private void closeWindow() {
        windowStart = -1;
        pushedNow = null;
        handler.removeCallbacks(tryRestore);
    }

    /** Novo retrato depois da troca de tela: a lista do foco, ou a principal da tela. */
    private void retakeShot() {
        AccessibilityNodeInfo root = root();
        if (root == null) {
            return;
        }
        String app = root.getPackageName() != null ? root.getPackageName().toString() : "";
        AccessibilityNodeInfo f = null;
        try {
            f = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
        } catch (Exception ignored) {
        }
        AccessibilityNodeInfo item = f != null ? listItem(f) : null;
        if (item != null && label(item) != null) {
            focusedItem = new Entry(label(item), app, item.getParent(), centerY(item));
            takeShot(item.getParent(), app);
        } else {
            takeShot(biggestList(root, 0), app);
        }
    }

    // ----- ao entrar numa pasta: foco no primeiro item, se o leitor de tela não pôs em nenhum -----

    /** Aplicativos de conversa: ao abrir um grupo, o primeiro item seria a mensagem mais antiga. */
    private static boolean isChatApp(String pkg) {
        return pkg.startsWith("org.telegram") || pkg.equals("org.thunderdog.challegram")
                || pkg.startsWith("com.whatsapp");
    }

    private void focusFirstItem() {
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

    private AccessibilityNodeInfo root() {
        try {
            return getRootInActiveWindow();
        } catch (Exception e) {
            return null;
        }
    }

    private static int centerY(AccessibilityNodeInfo n) {
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        return r.centerY();
    }

    /**
     * Procura na tela o item: item de lista que começa com o mesmo nome. Com o texto igualzinho, é ele;
     * entre vários com o mesmo nome, o mais perto da altura em que estava (como no SandFly).
     */
    private static AccessibilityNodeInfo find(AccessibilityNodeInfo root, Entry e) {
        List<AccessibilityNodeInfo> found;
        try {
            found = root.findAccessibilityNodeInfosByText(e.key);
        } catch (Exception ex) {
            return null;
        }
        if (found == null) {
            return null;
        }
        AccessibilityNodeInfo best = null;
        int bestDist = Integer.MAX_VALUE;
        for (AccessibilityNodeInfo n : found) {
            AccessibilityNodeInfo item = listItem(n);
            String l = item != null ? label(item) : null;
            if (l == null || !key(l).equals(e.key) || !item.isVisibleToUser()) {
                continue;
            }
            if (l.equals(e.label)) {
                return item;
            }
            int d = Math.abs(centerY(item) - e.centerY);
            if (d < bestDist) {
                bestDist = d;
                best = item;
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
        handler.removeCallbacks(tryRestore);
        handler.removeCallbacks(verify);
        handler.removeCallbacks(poll);
        if (prefsListener != null) {
            Prefs.get(this).unregisterOnSharedPreferenceChangeListener(prefsListener);
        }
        super.onDestroy();
    }
}
