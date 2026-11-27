package br.com.geofilho.ttsg;

import android.content.Context;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "Falar o nome escondido dos botões sem etiqueta". O serviço de acessibilidade vê qual
 * elemento recebeu o foco do leitor de tela; se ele não tem texto nem descrição, procura um nome
 * nos textos de dentro dele e, se não houver, no código interno do elemento (btn_send vira
 * "enviar"). Quando o leitor de tela manda falar "sem etiqueta" logo depois, o motor troca por
 * "tipo, nome". Sem nome encontrado, continua "sem etiqueta".
 * O serviço e o motor rodam no mesmo processo, por isso o estado é compartilhado aqui.
 */
final class Unlabeled {

    private Unlabeled() {
    }

    // ---------- Estado do último foco (escrito pelo serviço, lido pelo motor) ----------

    private static final Object LOCK = new Object();
    private static long focusAt;      // quando chegou o último foco
    private static boolean done;      // já terminou de procurar o nome desse foco
    private static String name;       // nome encontrado (null = elemento com nome próprio ou nada achado)
    private static String kind = "";  // "Botão", "Imagem"...
    private static String source = ""; // de onde veio o nome
    private static String code = "";
    private static String pkg = "";
    private static boolean unnamed;   // o elemento não tinha texto nem descrição

    /** Últimos achados, para o diagnóstico. */
    private static final ArrayDeque<String> FOUND = new ArrayDeque<>();
    private static final int MAX_FOUND = 25;

    /** "sem etiqueta" e variações que o leitor de tela fala. */
    private static final Pattern PHRASE = Pattern.compile(
            "\\s*[,.]?\\s*\\b(sem etiqueta|sem marcador|sem r[óo]tulo|unlabell?ed)\\b\\s*[,.]?",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern ROLE = Pattern.compile(
            "\\b(bot[ãa]o|imagem|link|guia|chave|caixa de sele[çc][ãa]o|campo|button|image)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** Chamado pelo serviço assim que o foco muda, antes de procurar o nome. */
    /** Focos recebidos pelo serviço desde que o processo começou (diagnóstico). */
    private static int focusCount;
    private static String lastFocusApp = "";

    static void focusStarted() {
        synchronized (LOCK) {
            focusCount++;
            focusAt = System.currentTimeMillis();
            done = false;
            name = null;
            unnamed = false;
            kind = "";
            source = "";
            code = "";
            pkg = "";
        }
    }

    /** Chamado pelo serviço com o resultado da procura. */
    static void focusDone(boolean noName, String found, String type, String from, String id, String app) {
        synchronized (LOCK) {
            unnamed = noName;
            name = found;
            kind = type == null ? "" : type;
            source = from == null ? "" : from;
            code = id == null ? "" : id;
            pkg = app == null ? "" : app;
            done = true;
            LOCK.notifyAll();
        }
    }

    /**
     * Usado pelo motor: se o texto tem "sem etiqueta" e acabou de haver um foco num elemento sem
     * nome, troca pelo nome encontrado. Espera um instante se a procura ainda não terminou.
     */
    static String replace(Context c, String text) {
        if (text == null || text.trim().isEmpty() || text.length() > 200) {
            return text;
        }
        // Descrição que é código ("home_square_icon_live"): o leitor de tela fala o código; troca por nome legível.
        String readable = readableCodes(c, text);
        if (!readable.equals(text)) {
            remember(c, "código falado: \"" + text + "\" trocado por \"" + readable + "\"");
            text = readable;
        }
        Matcher m = PHRASE.matcher(text);
        boolean phrase = m.find();
        // Fala sem conteúdo próprio: só "sem etiqueta", tipo ("Botão", "Imagem"), estado ou dica de toque.
        boolean bare = phrase || isBare(text);
        String found;
        String type;
        String from;
        String app;
        boolean noName;
        synchronized (LOCK) {
            long start = System.currentTimeMillis();
            if (bare) {
                // A fala pode chegar antes do aviso de foco: espera um pouco por um foco novo. Só "Botão",
                // "Ativado" e parecidos sem foco recente quase sempre são outra fala (uma chave que mudou):
                // espera bem pouco, para não atrasar o leitor de tela.
                long limit = !phrase && start - focusAt > 1500 ? 80 : 400;
                while (start - focusAt > 1500 || !done) {
                    long left = limit - (System.currentTimeMillis() - start);
                    if (left <= 0) {
                        break;
                    }
                    try {
                        LOCK.wait(left);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }
            boolean fresh = done && System.currentTimeMillis() - focusAt <= 1500;
            if (!fresh) {
                if (bare) {
                    remember(c, "\"" + text + "\": não trocado, "
                            + (focusAt == 0 ? "o serviço ainda não recebeu nenhum foco"
                            : !done ? "a procura do nome não terminou a tempo"
                            : "o último foco foi há " + (System.currentTimeMillis() - focusAt) + " ms"));
                }
                return text;
            }
            found = name;
            type = kind;
            from = source;
            app = pkg;
            noName = unnamed;
        }
        if (!noName) {
            if (phrase) {
                remember(c, "\"" + text + "\" em " + app + ": não trocado, o elemento focado tem texto ou descrição");
            }
            return text;
        }
        if (!bare) {
            // O leitor de tela já falou algum conteúdo (por exemplo, os textos de dentro): não mexe.
            return text;
        }
        if (found == null || found.isEmpty()) {
            remember(c, "fala depois do foco sem nome em " + app + ": \"" + text + "\", não trocado (nada encontrado)");
            return text;
        }
        String out;
        if (phrase) {
            String head = text.substring(0, m.start()).trim();
            String tail = text.substring(m.end()).trim();
            StringBuilder sb = new StringBuilder();
            if (!ROLE.matcher(text).find() && !type.isEmpty()) {
                sb.append(type).append(", ");
            }
            if (!head.isEmpty()) {
                sb.append(head).append(head.endsWith(",") ? " " : ", ");
            }
            sb.append(found);
            if (!tail.isEmpty()) {
                sb.append(", ").append(tail);
            }
            out = sb.toString();
        } else {
            // Sem "sem etiqueta": põe o nome logo depois do tipo ("Botão" vira "Botão, enviar").
            Matcher r = ROLE.matcher(text);
            if (r.find()) {
                out = text.substring(0, r.end()) + ", " + found + text.substring(r.end());
            } else {
                out = (type.isEmpty() ? "" : type + ", ") + found + ", " + text.trim();
            }
        }
        out = out.replaceAll(",\\s*,", ",").replaceAll(",\\s*\\.", ".").trim();
        remember(c, "fala em " + app + ": \"" + text + "\" trocada por \"" + out + "\" (" + from + ")");
        return out;
    }

    private static final Pattern SPOKEN_CODE = Pattern.compile("\\b[a-z][a-z0-9]*(_[a-z0-9]+)+\\b");

    /** Troca cada código no texto ("dd_module_product_card_1") pelo nome legível ("produto"). */
    static String readableCodes(Context c, String text) {
        if (text.indexOf('_') < 0) {
            return text;
        }
        Matcher m = SPOKEN_CODE.matcher(text);
        StringBuffer sb = new StringBuffer();
        boolean changed = false;
        while (m.find()) {
            String name = fromCode(c, m.group());
            if (name == null) {
                name = ""; // código só de estrutura (tangram_view_container): não fala nada
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(name));
            changed = true;
        }
        if (!changed) {
            return text;
        }
        m.appendTail(sb);
        String out = sb.toString().replaceAll("\\s*\\.\\s*\\.", ".").replaceAll("^\\s*[.,]\\s*", "").trim();
        return out.isEmpty() ? text : out;
    }

    /** Palavras que o leitor de tela fala sozinhas num elemento sem nome. */
    private static final Pattern BARE_WORDS = Pattern.compile(
            "(?iu)toque duas vezes( e (mantenha|segure)( pressionado)?)? para \\p{L}+( \\p{L}+)?"
                    + "|double[- ]tap to \\p{L}+|bot[ãa]o|imagem|link|guia|chave|interruptor|caixa de sele[çc][ãa]o"
                    + "|campo|ativad[oa]|desativad[oa]|selecionad[oa]|n[ãa]o selecionad[oa]|marcad[oa]|desmarcad[oa]"
                    + "|button|image|em lista|fora da lista|carregando|barra de progresso|rolagem|role com dois dedos"
                    + "|exibindo itens? \\d+( a \\d+)? de \\d+");

    private static boolean isBare(String text) {
        if (text.length() > 120) {
            return false;
        }
        String rest = BARE_WORDS.matcher(text).replaceAll(" ");
        rest = rest.replaceAll("[\\p{Punct}\\s\\d…]+", "");
        return rest.isEmpty();
    }

    /** Guarda o achado na memória e em arquivo (não se perde se o Android fechar o Gerenciador). */
    private static void remember(Context c, String msg) {
        if (!Prefs.diagnosticsOn(Prefs.get(c))) {
            return; // sem o diagnóstico ligado, não gasta tempo anotando
        }
        synchronized (FOUND) {
            load(c);
            if (!FOUND.isEmpty() && FOUND.peekFirst().endsWith(msg)) {
                return;
            }
            String time = new java.text.SimpleDateFormat("dd/MM HH:mm:ss", Locale.ROOT).format(new java.util.Date());
            FOUND.addFirst(time + " " + msg.replace('\n', ' '));
            while (FOUND.size() > MAX_FOUND) {
                FOUND.removeLast();
            }
            StringBuilder sb = new StringBuilder();
            for (String s : FOUND) {
                sb.append(s).append('\n');
            }
            try {
                store(c).edit().putString("achados", sb.toString()).apply();
            } catch (Exception ignored) {
            }
        }
    }

    private static boolean loaded;

    private static android.content.SharedPreferences store(Context c) {
        return Prefs.storage(c).getSharedPreferences("sem_etiqueta", Context.MODE_PRIVATE);
    }

    private static void load(Context c) {
        if (loaded) {
            return;
        }
        loaded = true;
        try {
            String all = store(c).getString("achados", "");
            for (String line : all.split("\n")) {
                if (!line.isEmpty() && FOUND.size() < MAX_FOUND) {
                    FOUND.addLast(line);
                }
            }
        } catch (Exception ignored) {
        }
    }

    /** Texto para o relatório de diagnóstico. */
    static String report(Context c) {
        synchronized (FOUND) {
            load(c);
            StringBuilder sb = new StringBuilder();
            sb.append("Focos do leitor de tela recebidos pelo serviço desde que o Gerenciador abriu: ")
                    .append(focusCount).append(lastFocusApp.isEmpty() ? "" : ", o último em " + lastFocusApp)
                    .append('\n');
            if (FOUND.isEmpty()) {
                return sb.append("Nenhum elemento sem nome encontrado ainda.\n").toString();
            }
            for (String s : FOUND) {
                sb.append("- ").append(s).append('\n');
            }
            return sb.toString();
        }
    }

    // ---------- Procura do nome (roda no serviço) ----------

    /** Procura o nome do elemento focado e grava o resultado para o motor. */
    static void inspect(Context c, AccessibilityNodeInfo n, CharSequence app) {
        String appName = app != null ? app.toString() : "";
        synchronized (LOCK) {
            lastFocusApp = appName;
        }
        if (n == null) {
            focusDone(false, null, "", "", "", appName);
            return;
        }
        if (!empty(n.getText()) || !empty(n.getContentDescription()) || n.getLabeledBy() != null) {
            focusDone(false, null, "", "", "", appName);
            return;
        }
        String type = type(n);
        String id = n.getViewIdResourceName();
        if (id == null && android.os.Build.VERSION.SDK_INT >= 33) {
            id = n.getUniqueId(); // identificador opcional do Android 13+, quando o aplicativo preenche
        }
        CharSequence clsSeq = n.getClassName();
        String cls = clsSeq == null ? "?" : clsSeq.toString().substring(clsSeq.toString().lastIndexOf('.') + 1);
        Rect r = boundsDp(c, n);
        String where = where(c, r);
        // Lembra o elemento, para a pessoa poder dar um nome a ele (tela "Dar nome ao botão").
        if (isRegularApp(c, appName)) {
            synchronized (LOCK) {
                targetPkg = appName;
                targetCls = cls;
                targetRect = r;
                targetWhere = where;
                targetType = type;
            }
        }
        // 1. Nome que a própria pessoa deu.
        String own = ownName(c, appName, cls, r);
        if (own != null) {
            found(c, own, type, "nome que você deu", id, appName, cls, where);
            return;
        }
        // 2. Dica, balão de ajuda e nomes de ações escondidos no próprio elemento.
        String hidden = hiddenText(n);
        if (hidden != null) {
            found(c, hidden, type, "veio de uma dica escondida", id, appName, cls, where);
            return;
        }
        innerCode = null;
        String inner = innerTexts(n);
        if (inner != null) {
            found(c, inner, type, "veio de dentro", id, appName, cls, where);
            return;
        }
        String fromCode = fromCode(c, id);
        if (fromCode != null) {
            found(c, fromCode, type, "veio do código " + shortId(id), id, appName, cls, where);
            return;
        }
        String fromInner = fromCode(c, innerCode);
        if (fromInner != null) {
            found(c, fromInner, type, "veio do código de dentro " + innerCode, id, appName, cls, where);
            return;
        }
        focusDone(true, null, type, "", id, appName);
        remember(c, "foco sem nome em " + appName + ": nada encontrado, "
                + (id == null ? "sem código" : "código " + shortId(id)) + ", classe " + cls + ", " + where);
    }

    private static void found(Context c, String name, String type, String from, String id, String app,
                              String cls, String where) {
        focusDone(true, name, type, from, id, app);
        remember(c, "foco sem nome em " + app + ": achou \"" + name + "\" (" + from + "), classe " + cls
                + ", " + where);
    }

    /** Textos que o leitor de tela às vezes não fala: dica, balão de ajuda, nome das ações. */
    private static String hiddenText(AccessibilityNodeInfo n) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 28 && !empty(n.getTooltipText())) {
                return clean(n.getTooltipText());
            }
            if (android.os.Build.VERSION.SDK_INT >= 26 && !empty(n.getHintText())) {
                return clean(n.getHintText());
            }
            for (AccessibilityNodeInfo.AccessibilityAction a : n.getActionList()) {
                CharSequence l = a.getLabel();
                if (!empty(l) && l.length() <= 60) {
                    return clean(l);
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    // ---------- Nomes que a pessoa dá aos botões ----------

    private static String targetPkg = "";
    private static String targetCls = "";
    private static Rect targetRect;
    private static String targetWhere = "";
    private static String targetType = "";

    /** Último elemento sem nome focado num aplicativo comum: {aplicativo, classe, lugar, tipo}; null se nenhum. */
    static String[] target() {
        synchronized (LOCK) {
            if (targetRect == null) {
                return null;
            }
            return new String[]{targetPkg, targetCls, targetWhere, targetType};
        }
    }

    /** Nome já dado ao último elemento, ou null. */
    static String targetName(Context c) {
        String pkg;
        String cls;
        Rect r;
        synchronized (LOCK) {
            pkg = targetPkg;
            cls = targetCls;
            r = targetRect;
        }
        return r == null ? null : ownName(c, pkg, cls, r);
    }

    /** Dá um nome ao último elemento sem nome (vazio apaga). */
    static void nameTarget(Context c, String name) {
        String pkg;
        String cls;
        Rect r;
        synchronized (LOCK) {
            pkg = targetPkg;
            cls = targetCls;
            r = targetRect;
        }
        if (r == null) {
            return;
        }
        android.content.SharedPreferences p = names(c);
        android.content.SharedPreferences.Editor e = p.edit();
        String old = ownKey(c, pkg, cls, r);
        if (old != null) {
            e.remove(old);
        }
        if (name != null && !name.trim().isEmpty()) {
            e.putString(pkg + "|" + cls + "|" + r.left + "," + r.top + "," + r.right + "," + r.bottom, name.trim());
        }
        e.apply();
    }

    /** Esquece o último botão sem nome (depois de nomear, o botão da página volta a mostrar a explicação). */
    static void forgetTarget() {
        synchronized (LOCK) {
            targetRect = null;
        }
    }

    static int namedCount(Context c) {
        return names(c).getAll().size();
    }

    /** Nomes dados, cada um {chave, aplicativo, nome, lugar}. */
    static List<String[]> listNames(Context c) {
        List<String[]> out = new ArrayList<>();
        for (Map.Entry<String, ?> e : names(c).getAll().entrySet()) {
            String k = e.getKey();
            String[] parts = k.split("\\|");
            if (parts.length != 3 || !(e.getValue() instanceof String)) {
                continue;
            }
            String place = "";
            String[] v = parts[2].split(",");
            if (v.length == 4) {
                try {
                    place = where(c, new Rect(Integer.parseInt(v[0]), Integer.parseInt(v[1]),
                            Integer.parseInt(v[2]), Integer.parseInt(v[3])));
                } catch (NumberFormatException ignored) {
                }
            }
            out.add(new String[]{k, parts[0], (String) e.getValue(), place});
        }
        return out;
    }

    static void renameKey(Context c, String key, String name) {
        if (name == null || name.trim().isEmpty()) {
            names(c).edit().remove(key).apply();
        } else {
            names(c).edit().putString(key, name.trim()).apply();
        }
    }

    private static android.content.SharedPreferences names(Context c) {
        return Prefs.storage(c).getSharedPreferences("nomes_dos_botoes", Context.MODE_PRIVATE);
    }

    private static String ownName(Context c, String pkg, String cls, Rect r) {
        String k = ownKey(c, pkg, cls, r);
        return k == null ? null : names(c).getString(k, null);
    }

    /** Chave do nome guardado para um elemento parecido: mesmo aplicativo e tipo, mesmo lugar e tamanho (folga de 24dp). */
    private static String ownKey(Context c, String pkg, String cls, Rect r) {
        if (r == null || pkg.isEmpty()) {
            return null;
        }
        String prefix = pkg + "|" + cls + "|";
        String best = null;
        int bestDiff = Integer.MAX_VALUE;
        for (String k : names(c).getAll().keySet()) {
            if (!k.startsWith(prefix)) {
                continue;
            }
            String[] v = k.substring(prefix.length()).split(",");
            if (v.length != 4) {
                continue;
            }
            try {
                int l = Integer.parseInt(v[0]);
                int t = Integer.parseInt(v[1]);
                int rt = Integer.parseInt(v[2]);
                int b = Integer.parseInt(v[3]);
                int dx = Math.abs((l + rt) / 2 - r.centerX());
                int dy = Math.abs((t + b) / 2 - r.centerY());
                int dw = Math.abs((rt - l) - r.width());
                int dh = Math.abs((b - t) - r.height());
                if (dx <= 24 && dy <= 24 && dw <= 24 && dh <= 24 && dx + dy + dw + dh < bestDiff) {
                    bestDiff = dx + dy + dw + dh;
                    best = k;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return best;
    }

    private static Rect boundsDp(Context c, AccessibilityNodeInfo n) {
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        float d = c.getResources().getDisplayMetrics().density;
        return new Rect(Math.round(r.left / d), Math.round(r.top / d), Math.round(r.right / d), Math.round(r.bottom / d));
    }

    /** Lugar do elemento na tela, falado ("em cima, à direita"). */
    private static String where(Context c, Rect r) {
        android.util.DisplayMetrics m = c.getResources().getDisplayMetrics();
        float w = m.widthPixels / m.density;
        float h = m.heightPixels / m.density;
        String v = r.centerY() < h / 3 ? "em cima" : r.centerY() > h * 2 / 3 ? "embaixo" : "no meio da tela";
        String s = r.centerX() < w / 3 ? "à esquerda" : r.centerX() > w * 2 / 3 ? "à direita" : "no centro";
        return v + ", " + s + ", " + r.width() + " por " + r.height() + " dp";
    }

    private static boolean isRegularApp(Context c, String pkg) {
        return !pkg.isEmpty() && !pkg.equals(c.getPackageName()) && !pkg.equals("com.android.systemui")
                && !pkg.contains("launcher") && !pkg.contains("inputmethod") && !pkg.contains("keyboard")
                && !pkg.contains("pixelboard");
    }

    private static boolean empty(CharSequence s) {
        return s == null || s.toString().trim().isEmpty();
    }

    private static String type(AccessibilityNodeInfo n) {
        CharSequence cls = n.getClassName();
        String k = cls != null ? cls.toString() : "";
        if (k.endsWith("CheckBox")) {
            return "Caixa de seleção";
        }
        if (k.endsWith("Switch")) {
            return "Chave";
        }
        if (k.endsWith("Button") || n.isClickable() || n.isLongClickable()) {
            return "Botão";
        }
        if (k.endsWith("ImageView")) {
            return "Imagem";
        }
        return "";
    }

    /** Textos e descrições dos elementos de dentro (até 3, procurando em até 60 elementos). */
    private static String innerTexts(AccessibilityNodeInfo root) {
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        List<String> texts = new ArrayList<>();
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo ch = root.getChild(i);
            if (ch != null) {
                queue.add(ch);
            }
        }
        int visited = 0;
        int length = 0;
        while (!queue.isEmpty() && visited < 60 && texts.size() < 3 && length < 120) {
            AccessibilityNodeInfo n = queue.poll();
            visited++;
            if (!n.isVisibleToUser()) {
                continue;
            }
            String t = clean(n.getContentDescription());
            if (t.isEmpty()) {
                t = clean(n.getText());
            }
            if (CODE_LIKE.matcher(t).matches()) {
                if (innerCode == null) {
                    innerCode = t;
                }
                t = ""; // "skinny_banner_collection_view": é código, não texto (o código do elemento é tentado depois)
            }
            if (!t.isEmpty() && !texts.contains(t)) {
                texts.add(t);
                length += t.length();
            }
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo ch = n.getChild(i);
                if (ch != null) {
                    queue.add(ch);
                }
            }
        }
        if (texts.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (String t : texts) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(t);
        }
        String out = sb.toString();
        return out.length() > 150 ? out.substring(0, 150) : out;
    }

    /** Primeiro texto de dentro que era código (usado se nada melhor aparecer). Só a linha principal usa. */
    private static String innerCode;

    private static final Pattern CODE_LIKE = Pattern.compile("[a-z0-9]+(_[a-z0-9]+)+");

    private static String clean(CharSequence s) {
        return s == null ? "" : s.toString().replace('\n', ' ').trim();
    }

    private static String shortId(String id) {
        if (id == null) {
            return "";
        }
        int slash = id.indexOf('/');
        return slash >= 0 ? id.substring(slash + 1) : id;
    }

    /** Partes do código que não dizem nada (prefixos e nomes de estrutura). */
    private static final Set<String> NOISE = new HashSet<>(Arrays.asList(
            "btn", "button", "bt", "ic", "icon", "iv", "img", "image", "imageview", "imgbtn", "ib", "tv", "txt",
            "text", "textview", "view", "layout", "container", "wrapper", "holder", "root", "content", "frame",
            "id", "tangram", "item", "cell", "cover", "bg", "background", "fl", "ll", "rl", "cl", "lay", "lyt",
            "vg", "group", "square", "module", "dd", "hotspot", "skinny", "collection", "tangram", "widget", "action", "iconfont", "svg", "lottie", "anim", "the", "of", "to", "a", "an",
            "and", "for", "on", "in", "my", "new", "main", "custom", "default", "common", "base", "rn", "android"));

    /** Palavras comuns em códigos de botões. */
    private static final Map<String, String> WORDS = new HashMap<>();
    /** Frases inteiras (depois de tirar os prefixos). */
    private static final Map<String, String> PHRASES = new HashMap<>();

    static {
        String[] w = {
                "send", "enviar", "back", "voltar", "close", "fechar", "search", "pesquisar", "menu", "menu",
                "more", "mais opções", "share", "compartilhar", "like", "curtir", "cart", "carrinho",
                "add", "adicionar", "delete", "excluir", "remove", "remover", "edit", "editar",
                "settings", "configurações", "setting", "configurações", "play", "reproduzir", "pause", "pausar",
                "stop", "parar", "next", "próximo", "prev", "anterior", "previous", "anterior", "home", "início",
                "profile", "perfil", "notification", "notificações", "notifications", "notificações",
                "notify", "notificações", "bell", "notificações", "chat", "conversa", "message", "mensagem",
                "messages", "mensagens", "msg", "mensagem", "camera", "câmera", "scan", "escanear",
                "scanner", "escanear", "qr", "QR code", "qrcode", "QR code", "favorite", "favorito",
                "favourite", "favorito", "fav", "favorito", "filter", "filtrar", "sort", "ordenar",
                "help", "ajuda", "info", "informações", "copy", "copiar", "download", "baixar",
                "upload", "enviar arquivo", "refresh", "atualizar", "reload", "atualizar", "clear", "limpar",
                "cancel", "cancelar", "ok", "ok", "confirm", "confirmar", "done", "concluir", "expand", "expandir",
                "collapse", "recolher", "arrow", "seta", "left", "esquerda", "right", "direita", "up", "cima",
                "down", "baixo", "mic", "microfone", "voice", "voz", "emoji", "emoji", "attach", "anexar",
                "attachment", "anexo", "gallery", "galeria", "photo", "foto", "picture", "foto", "call", "ligar",
                "phone", "telefone", "video", "vídeo", "location", "localização", "map", "mapa",
                "wallet", "carteira", "pix", "Pix", "coupon", "cupom", "coupons", "cupons", "voucher", "cupom",
                "vouchers", "cupons", "live", "ao vivo", "follow", "seguir", "store", "loja", "shop", "loja",
                "order", "pedido", "orders", "pedidos", "payment", "pagamento", "pay", "pagar",
                "balance", "saldo", "hide", "ocultar", "show", "mostrar", "eye", "olho", "visibility", "mostrar ou ocultar",
                "logo", "logotipo", "avatar", "foto do perfil", "top", "topo", "bottom", "fim", "open", "abrir",
                "save", "salvar", "star", "estrela", "rating", "avaliação", "rate", "avaliar", "question", "pergunta",
                "plus", "mais", "minus", "menos", "user", "usuário", "account", "conta", "card", "cartão",
                "scroll", "rolar", "list", "lista", "grid", "grade", "option", "opção", "options", "opções",
                "overflow", "mais opções", "dots", "mais opções", "kebab", "mais opções", "hamburger", "menu",
                "drawer", "menu", "nav", "navegação", "navigation", "navegação", "tab", "guia", "keyboard", "teclado",
                "password", "senha", "login", "entrar", "logout", "sair", "exit", "sair", "sign", "entrar",
                "register", "cadastrar", "invest", "investir", "transfer", "transferir", "extract", "extrato",
                "statement", "extrato", "receipt", "comprovante", "invoice", "fatura", "bill", "boleto",
                "barcode", "código de barras", "credit", "crédito", "debit", "débito", "loan", "empréstimo",
                "insurance", "seguro", "gift", "presente", "reward", "recompensa", "rewards", "recompensas",
                "cashback", "cashback", "points", "pontos", "coin", "moedas", "coins", "moedas",
                "flash", "relâmpago", "sale", "oferta", "deal", "oferta", "deals", "ofertas", "banner", "banner",
                "product", "produto", "products", "produtos", "category", "categoria", "categories", "categorias",
                "price", "preço", "quantity", "quantidade", "qty", "quantidade", "buy", "comprar", "checkout", "finalizar compra",
                "delivery", "entrega", "shipping", "frete", "address", "endereço", "contact", "contato",
                "contacts", "contatos", "calendar", "calendário", "date", "data", "time", "hora", "clock", "relógio",
                "alarm", "alarme", "timer", "temporizador", "volume", "volume", "mute", "silenciar", "sound", "som",
                "speaker", "alto-falante", "fullscreen", "tela cheia", "full", "cheia", "screen", "tela",
                "zoom", "zoom", "rotate", "girar", "crop", "cortar", "lock", "bloquear", "unlock", "desbloquear",
                "trash", "lixeira", "bin", "lixeira", "archive", "arquivar", "pin", "fixar", "bookmark", "salvar",
                "report", "denunciar", "block", "bloquear", "translate", "traduzir", "language", "idioma",
                "theme", "tema", "dark", "escuro", "light", "claro", "news", "notícias", "feed", "feed",
                "story", "story", "stories", "stories", "comment", "comentar", "comments", "comentários",
                "reply", "responder", "forward", "encaminhar", "retry", "tentar de novo", "skip", "pular",
                "submit", "enviar", "apply", "aplicar", "select", "selecionar", "check", "marcar", "toggle", "alternar",
                "switch", "chave", "dismiss", "dispensar", "minimize", "minimizar", "maximize", "maximizar",
                "service", "atendimento", "support", "suporte", "faq", "perguntas frequentes", "feedback", "opinião",
                "new", "novo", "home", "início", "explore", "explorar", "discover", "descobrir", "mall", "shopping",
                "me", "eu", "mine", "eu", "shopeevip", "Shopee VIP", "vip", "VIP", "shopee", "Shopee",
                "pesquisa", "pesquisar", "recommend", "recomendados", "recommendation", "recomendados"
        };
        for (int i = 0; i + 1 < w.length; i += 2) {
            WORDS.put(w[i], w[i + 1]);
        }
        String[] p = {
                "scroll top", "voltar ao topo", "back top", "voltar ao topo", "go top", "voltar ao topo",
                "more options", "mais opções", "more menu", "mais opções", "toolbar back", "voltar",
                "nav back", "voltar", "navigation back", "voltar", "up", "voltar", "home up", "voltar",
                "eye password", "mostrar senha", "password toggle", "mostrar senha", "show password", "mostrar senha",
                "hide password", "ocultar senha", "password visibility", "mostrar senha", "qr scan", "escanear QR code",
                "scan qr", "escanear QR code", "shopping cart", "carrinho", "add cart", "adicionar ao carrinho",
                "flash sale", "oferta relâmpago", "customer service", "atendimento", "live chat", "conversa",
                "clear search", "limpar pesquisa", "search clear", "limpar pesquisa", "voice search", "pesquisa por voz",
                "show balance", "mostrar saldo", "hide balance", "ocultar saldo", "balance visibility", "mostrar ou ocultar saldo",
                "eye balance", "mostrar ou ocultar saldo", "close dialog", "fechar", "dialog close", "fechar",
                "play pause", "reproduzir ou pausar", "sign in", "entrar", "product card", "produto", "live card", "ao vivo", "sign out", "sair", "log in", "entrar"
        };
        for (int i = 0; i + 1 < p.length; i += 2) {
            PHRASES.put(p[i], p[i + 1]);
        }
    }

    private static final Pattern VOWEL = Pattern.compile("[aeiouy]");

    /** Nome legível a partir do código interno (btn_send → "enviar"); null se for código embaralhado. */
    static String fromCode(Context c, String id) {
        if (id == null || id.trim().isEmpty()) {
            return null;
        }
        String s = shortId(id);
        // Separa camelCase, sublinhado, hífen, ponto e números.
        s = s.replaceAll("([a-z])([A-Z])", "$1 $2").replaceAll("([A-Z]+)([A-Z][a-z])", "$1 $2");
        s = s.replaceAll("[_\\-.:]+", " ").replaceAll("[0-9]+", " ").toLowerCase(Locale.ROOT).trim();
        if (s.isEmpty()) {
            return null;
        }
        List<String> words = new ArrayList<>();
        for (String t : s.split("\\s+")) {
            if (t.isEmpty() || NOISE.contains(t)) {
                continue;
            }
            boolean known = WORDS.containsKey(t);
            // Código embaralhado (a1b, xz, q): parte curta ou sem vogal que não é palavra conhecida.
            if (!known && (t.length() < 3 || !VOWEL.matcher(t).find())) {
                continue;
            }
            words.add(t);
        }
        if (words.size() > 1 && words.get(0).equals("home")) {
            words.remove(0); // "home_..." na Shopee é só o nome da tela inicial
        }
        if (words.isEmpty()) {
            return null;
        }
        String phrase = join(words);
        String hit = PHRASES.get(phrase);
        if (hit != null) {
            return hit;
        }
        hit = BaseDictionary.lookup(c, phrase);
        if (hit != null && !hit.equalsIgnoreCase(phrase)) {
            return hit;
        }
        // Palavra por palavra, sem repetir a mesma tradução.
        List<String> out = new ArrayList<>();
        for (String t : words) {
            String tr = WORDS.get(t);
            if (tr == null) {
                String d = BaseDictionary.lookup(c, t);
                tr = d != null ? d.toLowerCase(Locale.ROOT) : t;
            }
            if (!out.contains(tr)) {
                out.add(tr);
            }
        }
        return join(out);
    }

    private static String join(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (String t : parts) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(t);
        }
        return sb.toString();
    }
}
