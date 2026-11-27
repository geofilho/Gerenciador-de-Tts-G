package br.com.geofilho.ttsg;

import android.content.Context;
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
 * "Falar o nome escondido dos botões sem etiqueta" (teste). O serviço de acessibilidade vê qual
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
    private static final int MAX_FOUND = 12;

    /** "sem etiqueta" e variações que o leitor de tela fala. */
    private static final Pattern PHRASE = Pattern.compile(
            "\\s*[,.]?\\s*\\b(sem etiqueta|sem r[óo]tulo|unlabell?ed)\\b\\s*[,.]?",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern ROLE = Pattern.compile(
            "\\b(bot[ãa]o|imagem|link|guia|chave|caixa de sele[çc][ãa]o|campo|button|image)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** Chamado pelo serviço assim que o foco muda, antes de procurar o nome. */
    static void focusStarted() {
        synchronized (LOCK) {
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
        if (text == null || text.length() > 300) {
            return text;
        }
        Matcher m = PHRASE.matcher(text);
        if (!m.find()) {
            return text;
        }
        String found;
        String type;
        String from;
        String id;
        String app;
        boolean noName;
        synchronized (LOCK) {
            long start = System.currentTimeMillis();
            // A fala pode chegar antes do aviso de foco: espera um pouco por um foco novo.
            while (start - focusAt > 1500 || !done) {
                long left = 400 - (System.currentTimeMillis() - start);
                if (left <= 0) {
                    break;
                }
                try {
                    LOCK.wait(left);
                } catch (InterruptedException e) {
                    break;
                }
            }
            if (!done || System.currentTimeMillis() - focusAt > 2000) {
                return text; // foco antigo: não arrisca falar o nome de outro botão
            }
            found = name;
            type = kind;
            from = source;
            id = code;
            app = pkg;
            noName = unnamed;
        }
        if (!noName) {
            return text;
        }
        if (found == null || found.isEmpty()) {
            remember("em " + app + ": nada encontrado"
                    + (id.isEmpty() ? ", sem código" : ", código " + id) + (type.isEmpty() ? "" : ", tipo " + type));
            return text;
        }
        String head = text.substring(0, m.start()).trim();
        String tail = text.substring(m.end()).trim();
        StringBuilder sb = new StringBuilder();
        boolean hasRole = ROLE.matcher(text).find();
        if (!hasRole && !type.isEmpty()) {
            sb.append(type).append(", ");
        }
        if (!head.isEmpty()) {
            sb.append(head).append(head.endsWith(",") ? " " : ", ");
        }
        sb.append(found);
        if (!tail.isEmpty()) {
            sb.append(", ").append(tail);
        }
        String out = sb.toString().replaceAll(",\\s*,", ",").trim();
        remember("em " + app + ": \"" + found + "\" (" + from + ")" + (type.isEmpty() ? "" : ", tipo " + type));
        return out;
    }

    private static void remember(String msg) {
        synchronized (FOUND) {
            if (!FOUND.isEmpty() && FOUND.peekFirst().endsWith(msg)) {
                return;
            }
            String time = new java.text.SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new java.util.Date());
            FOUND.addFirst(time + " " + msg);
            while (FOUND.size() > MAX_FOUND) {
                FOUND.removeLast();
            }
        }
    }

    /** Texto para o relatório de diagnóstico. */
    static String report() {
        synchronized (FOUND) {
            if (FOUND.isEmpty()) {
                return "nenhum ainda\n";
            }
            StringBuilder sb = new StringBuilder();
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
        String inner = innerTexts(n);
        if (inner != null) {
            focusDone(true, inner, type, "veio de dentro", id, appName);
            return;
        }
        String fromCode = fromCode(c, id);
        if (fromCode != null) {
            focusDone(true, fromCode, type, "veio do código " + shortId(id), id, appName);
            return;
        }
        focusDone(true, null, type, "", id, appName);
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
            "vg", "group", "widget", "action", "iconfont", "svg", "lottie", "anim", "the", "of", "to", "a", "an",
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
                "me", "eu", "mine", "eu"
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
                "play pause", "reproduzir ou pausar", "sign in", "entrar", "sign out", "sair", "log in", "entrar"
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
