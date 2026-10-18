package campus;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 大模型调用封装（兼容一切 OpenAI 风格的 /models + /chat/completions 接口）。
 *
 * 只用 JDK 自带的 java.net.http.HttpClient，不引入任何第三方 HTTP 库——
 * 这样 java/lib 目录为空也能跑，评委在自己的电脑上 clone 下来直接就能编译。
 *
 * 三件事：
 *   match()  —— 自动匹配「这把 Key 属于哪家服务商」，给出 baseUrl 与模型名；
 *   chat()   —— 一次对话补全；
 *   healthCached() —— 供 /api/health 读取（只读缓存，绝不在请求里发网络请求）。
 *
 * 关于自动匹配：Key 前缀（sk-）在 DeepSeek / Kimi / 通义 / OpenAI 之间高度重叠，
 * 光看前缀判断不出来，所以真正的判据是「拿 Key 去候选服务商的 /models 探一次，
 * 谁返回 200 就是谁」。按配置顺序串行尝试，第一个成功即锁定并缓存。
 *
 * 关于隐私：Key 只发给候选列表里的服务商，且一旦有一家接受就立即停止；
 * 界面上如果手动选定了服务商，候选就只剩那一家，不会到处散发。
 *
 * 关于超时：probe 的超时必须远小于前端的健康检查超时（app.js 里是 1500ms），
 * 否则前端会把「后端在探测」误判成「没有后端」，白白丢掉 Java 服务这条链路。
 * 因此服务启动时就把匹配结果算好并缓存，/api/health 只读缓存、不做网络请求。
 */
public final class Llm {

  /** 一个可用的大模型服务商。 */
  public static final class Provider {
    public final String id;
    public final String label;
    public final String baseUrl;
    public final String model;
    public final List<String> modelHints;

    Provider(String id, String label, String baseUrl, String model, List<String> modelHints) {
      this.id = id == null ? "" : id;
      this.label = (label == null || label.isEmpty()) ? this.id : label;
      this.baseUrl = trimTrailingSlash(baseUrl);
      this.model = model == null ? "" : model;
      this.modelHints = modelHints == null ? new ArrayList<>() : modelHints;
    }

    /** 派生出一个只改地址/模型名的新实例（custom 服务商走这条路）。 */
    Provider with(String newBase, String newModel) {
      String b = (newBase == null || newBase.trim().isEmpty()) ? baseUrl : newBase.trim();
      String m = (newModel == null || newModel.trim().isEmpty()) ? model : newModel.trim();
      return new Provider(id, label, b, m, modelHints);
    }
  }

  /** 匹配结果。status 取值与前端约定一致：ok / invalid_key / unreachable / no_key / no_provider。 */
  public static final class Match {
    public final String status;
    public final Provider provider;
    public final String model;
    public final String detail;

    Match(String status, Provider provider, String model, String detail) {
      this.status = status == null ? "unreachable" : status;
      this.provider = provider;
      this.model = model == null ? "" : model;
      this.detail = detail == null ? "" : detail;
    }

    static Match of(String status, Provider provider, String model, String detail) {
      return new Match(status, provider, model, detail);
    }

    public boolean ok() {
      return "ok".equals(status) && provider != null;
    }
  }

  /** 一次对话补全的结果。失败时 ok=false 并带上 error 文本，调用方据此回退离线答案。 */
  public static final class Chat {
    public final boolean ok;
    public final String content;
    public final String error;

    Chat(boolean ok, String content, String error) {
      this.ok = ok;
      this.content = content;
      this.error = error;
    }
  }

  private static final String LOCAL_PROPS = "config.local.properties";
  private static final long MATCH_TTL_MS = 120_000L;

  private static Map<String, Object> llmCfgCache;
  private static Match cachedMatch;
  private static long cachedMatchAt;
  private static String cachedMatchSig;

  private final Provider provider;
  private final String apiKey;
  private final String probePath;
  private final String chatPath;
  private final int probeTimeoutMs;
  private final int chatTimeoutMs;
  private final double temperature;
  private final HttpClient http;

  private Llm(Provider provider, String apiKey) {
    Map<String, Object> cfg = llmConfig();
    this.provider = provider;
    this.apiKey = apiKey == null ? "" : apiKey.trim();
    this.probePath = Json.str(cfg, "probePath", "/models");
    this.chatPath = Json.str(cfg, "chatPath", "/chat/completions");
    this.probeTimeoutMs = (int) Json.num(cfg, "probeTimeoutMs", 2500);
    this.chatTimeoutMs = (int) Json.num(cfg, "chatTimeoutMs", 30000);
    this.temperature = Json.num(cfg, "temperature", 0.3);
    this.http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(probeTimeoutMs))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();
  }

  public static Llm of(Provider provider, String apiKey) {
    return new Llm(provider, apiKey);
  }

  public String modelName() {
    return provider.model;
  }

  public String providerLabel() {
    return provider.label;
  }

  public String providerId() {
    return provider.id;
  }

  /** 用于界面显示的「联网大模型 · 服务商 · 模型」一行文案。 */
  public String sourceLabel() {
    return "联网大模型 · " + provider.label + (provider.model.isEmpty() ? "" : " · " + provider.model);
  }

  // ------------------------------------------------------------------ 配置

  /** 读取 config.json 的 llm 段（只读一次）。Java 与浏览器两侧读的必须是同一份。 */
  public static synchronized Map<String, Object> llmConfig() {
    if (llmCfgCache != null) return llmCfgCache;
    try {
      Map<String, Object> root = Data.load("config.json");
      llmCfgCache = root.get("llm") instanceof Map ? Json.obj(root.get("llm")) : new LinkedHashMap<>();
    } catch (RuntimeException e) {
      System.err.println("[llm] 读取 config.json 失败，退化为内置默认配置：" + e.getMessage());
      llmCfgCache = new LinkedHashMap<>();
    }
    return llmCfgCache;
  }

  public static List<Provider> providers() {
    List<Provider> out = new ArrayList<>();
    for (Object o : listOf(Json.arr(llmConfig().get("providers")))) {
      Map<String, Object> m = Json.obj(o);
      String id = Json.str(m, "id", "");
      if (id.isEmpty()) continue;
      List<String> hints = new ArrayList<>();
      for (Object h : listOf(Json.arr(m.get("modelHints")))) {
        if (h != null) hints.add(String.valueOf(h));
      }
      out.add(new Provider(id, Json.str(m, "label", id), Json.str(m, "baseUrl", ""),
          Json.str(m, "model", ""), hints));
    }
    return out;
  }

  public static String defaultProviderId() {
    return Json.str(llmConfig(), "defaultProvider", "deepseek");
  }

  /** 在线模式行为开关：always = 所有问题都交给大模型；auto = 仅置信度不足时。 */
  public static boolean alwaysOnline() {
    Object mode = llmConfig().get("mode");
    Map<String, Object> m = mode instanceof Map ? Json.obj(mode) : new LinkedHashMap<>();
    return "always".equalsIgnoreCase(Json.str(m, "onlineAsk", "always"));
  }

  /** 知识库未覆盖时是否允许大模型用通用常识作答。 */
  public static boolean kbFallbackToModel() {
    Object mode = llmConfig().get("mode");
    Map<String, Object> m = mode instanceof Map ? Json.obj(mode) : new LinkedHashMap<>();
    return Json.bool(m, "kbFallbackToModel", true);
  }

  // ------------------------------------------------------------------ 密钥解析

  /**
   * 决定用哪个 Key，优先级：界面临时填的 > 环境变量 > java/config.local.properties。
   *
   * 为什么界面填的优先级最高：演示时「测试密钥」按钮必须测的就是用户刚填的那把 Key，
   * 如果被环境变量顶掉，用户会看到「填了新的 Key 还是无效」这种无从排查的现象。
   */
  public static String resolveKey(String requestKey) {
    if (requestKey != null && !requestKey.trim().isEmpty()) return requestKey.trim();
    String env = firstEnv("CAMPUS_API_KEY", "DEEPSEEK_API_KEY");
    if (env != null) return env;
    return readLocalKey();
  }

  private static String firstEnv(String... names) {
    for (String n : names) {
      String v = System.getenv(n);
      if (v != null && !v.trim().isEmpty()) return v.trim();
    }
    return null;
  }

  /** 从 java/config.local.properties 读取（已加入 .gitignore，不会被提交）。 */
  public static String readLocalKey() {
    String[] candidates = { LOCAL_PROPS, "../" + LOCAL_PROPS, "../../" + LOCAL_PROPS };
    for (String c : candidates) {
      Path p = Paths.get(c).toAbsolutePath().normalize();
      if (!Files.isRegularFile(p)) continue;
      try {
        for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
          String s = line.trim();
          if (s.isEmpty() || s.startsWith("#")) continue;
          int eq = s.indexOf('=');
          if (eq < 0) continue;
          String k = s.substring(0, eq).trim();
          String v = s.substring(eq + 1).trim();
          if (v.isEmpty()) continue;
          if (k.equals("campus.api.key") || k.equals("deepseek.api.key")
              || k.equals("api.key") || k.equals("deepseekApiKey")) {
            return v;
          }
        }
      } catch (IOException e) {
        // 读不到就当没有，不影响离线运行
        System.err.println("[llm] 读取 " + p + " 失败：" + e.getMessage());
      }
    }
    return null;
  }

  /** Key 是否来自服务端配置（而非界面临时填），用于 /api/health 的 configured 判定。 */
  public static boolean hasServerKey() {
    return firstEnv("CAMPUS_API_KEY", "DEEPSEEK_API_KEY") != null || readLocalKey() != null;
  }

  // ------------------------------------------------------------------ 自动匹配

  /**
   * 自动匹配服务商。
   *
   * @param key          API Key
   * @param preferredId  界面上手动选定的服务商 id（可为 null，表示交给自动匹配）
   * @param customBase   选「自定义」时填的 baseUrl（可为 null）
   * @param customModel  选「自定义」时填的模型名（可为 null）
   */
  public static synchronized Match match(String key, String preferredId,
                                        String customBase, String customModel) {
    if (key == null || key.trim().isEmpty()) {
      return Match.of("no_key", null, "", "未配置密钥");
    }
    String k = key.trim();
    String sig = k.hashCode() + "|" + nz(preferredId) + "|" + nz(customBase) + "|" + nz(customModel);
    if (cachedMatch != null && sig.equals(cachedMatchSig)
        && System.currentTimeMillis() - cachedMatchAt < MATCH_TTL_MS) {
      return cachedMatch;
    }

    List<Provider> queue = candidateQueue(preferredId, customBase, customModel);
    if (queue.isEmpty()) {
      Match m = Match.of("no_provider", null, "", "config.json 里没有可用的服务商配置");
      remember(m, sig);
      return m;
    }

    boolean sawInvalid = false;
    String lastDetail = "";
    for (Provider p : queue) {
      if (p.baseUrl.isEmpty()) continue;
      Match m = probeOnce(p, k);
      if (m.ok()) {
        remember(m, sig);
        return m;
      }
      if ("invalid_key".equals(m.status)) {
        sawInvalid = true;
        lastDetail = p.label + " 拒绝了这把 Key（HTTP 401/403）";
      } else {
        lastDetail = p.label + "：" + m.detail;
        // 网络不通就不用再试其它服务商了——它们同样连不上，
        // 继续试只会让启动白白多等好几个超时。
        break;
      }
    }

    Match result = sawInvalid
        ? Match.of("invalid_key", null, "", lastDetail.isEmpty() ? "试过的服务商都拒绝了这个密钥" : lastDetail)
        : Match.of("unreachable", null, "", lastDetail.isEmpty() ? "无法连接到大模型服务" : lastDetail);
    remember(result, sig);
    return result;
  }

  /** 启动时预热：让 /api/health 能立刻给出状态，而不用在请求里现探测。 */
  public static Match warmUp(String key) {
    return match(key, null, null, null);
  }

  private static void remember(Match m, String sig) {
    cachedMatch = m;
    cachedMatchAt = System.currentTimeMillis();
    cachedMatchSig = sig;
  }

  private static String nz(String s) {
    return s == null ? "" : s.trim();
  }

  /** 候选顺序：手动选定 > 默认服务商 > 配置里的其余内置服务商。 */
  private static List<Provider> candidateQueue(String preferredId, String customBase, String customModel) {
    List<Provider> all = providers();
    List<Provider> queue = new ArrayList<>();

    String pref = nz(preferredId);
    if (!pref.isEmpty()) {
      Provider p = find(all, pref);
      if (p != null) queue.add(p.with(customBase, customModel));
    }
    if (queue.isEmpty()) {
      Provider d = find(all, defaultProviderId());
      if (d == null) d = firstNonCustom(all);
      if (d != null) queue.add(d);
    }
    for (Provider p : all) {
      // custom 没有固定地址，只能由界面显式选定，不参与自动兜底
      if ("custom".equals(p.id)) continue;
      boolean seen = false;
      for (Provider q : queue) if (q.id.equals(p.id)) { seen = true; break; }
      if (!seen) queue.add(p);
    }
    return queue;
  }

  private static Provider find(List<Provider> all, String id) {
    for (Provider p : all) if (p.id.equals(id)) return p;
    return null;
  }

  private static Provider firstNonCustom(List<Provider> all) {
    for (Provider p : all) if (!"custom".equals(p.id)) return p;
    return null;
  }

  /** 拿 Key 去一个服务商的 /models 探一次。 */
  private static Match probeOnce(Provider p, String key) {
    Llm llm = new Llm(p, key);
    try {
      HttpRequest req = HttpRequest.newBuilder(URI.create(p.baseUrl + llm.probePath))
          .timeout(Duration.ofMillis(llm.probeTimeoutMs))
          .header("Authorization", "Bearer " + key)
          .header("Accept", "application/json")
          .GET()
          .build();
      HttpResponse<String> res = llm.http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      int code = res.statusCode();
      if (code >= 200 && code < 300) {
        String model = pickModel(res.body(), p);
        return Match.of("ok", p.with(null, model), model, "");
      }
      if (code == 401 || code == 402 || code == 403) {
        return Match.of("invalid_key", null, "", "HTTP " + code + " " + brief(res.body()));
      }
      return Match.of("unreachable", null, "", "HTTP " + code + " " + brief(res.body()));
    } catch (IOException e) {
      return Match.of("unreachable", null, "", e.getClass().getSimpleName() + ": " + e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Match.of("unreachable", null, "", "探测被中断");
    } catch (RuntimeException e) {
      return Match.of("unreachable", null, "", String.valueOf(e.getMessage()));
    }
  }

  /** /models 返回里按 modelHints 挑一个模型名，挑不到就用配置里的默认模型。 */
  private static String pickModel(String body, Provider p) {
    try {
      Map<String, Object> m = Json.obj(Json.parse(body));
      List<Object> data = Json.arr(m.get("data"));
      if (data != null && !data.isEmpty()) {
        List<String> ids = new ArrayList<>();
        for (Object o : data) {
          String id = Json.str(Json.obj(o), "id", "");
          if (!id.isEmpty()) ids.add(id);
        }
        for (String hint : p.modelHints) {
          for (String id : ids) if (id.contains(hint)) return id;
        }
        if (!ids.isEmpty()) return ids.get(0);
      }
    } catch (RuntimeException ignored) {
      // 解析失败无所谓，模型名只是给界面显示与调用用
    }
    return p.model;
  }

  /** 供 /api/health 使用：读缓存的匹配结果，绝不在这里发起网络请求。 */
  public static synchronized Map<String, Object> healthCached() {
    Map<String, Object> out = new LinkedHashMap<>();
    String key = resolveKey(null);
    if (key == null) {
      out.put("status", "no_key");
      out.put("configured", false);
      out.put("reachable", false);
      out.put("valid", false);
      out.put("model", "");
      out.put("provider", "");
      out.put("providerLabel", "");
      return out;
    }
    Match m = cachedMatch != null ? cachedMatch : Match.of("unreachable", null, "", "尚未探测");
    out.put("status", m.status);
    out.put("configured", true);
    out.put("reachable", m.ok() || "invalid_key".equals(m.status));
    out.put("valid", m.ok());
    out.put("model", m.model);
    out.put("provider", m.provider == null ? "" : m.provider.id);
    out.put("providerLabel", m.provider == null ? "" : m.provider.label);
    if (!m.detail.isEmpty()) out.put("detail", m.detail);
    return out;
  }

  // ------------------------------------------------------------------ 对话

  /** 一次对话补全。 */
  public Chat chat(List<Map<String, Object>> messages) {
    if (apiKey.isEmpty()) return new Chat(false, null, "未配置密钥");
    if (provider.baseUrl.isEmpty()) return new Chat(false, null, "没有可用的服务商地址");

    Map<String, Object> bodyMap = new LinkedHashMap<>();
    bodyMap.put("model", provider.model);
    bodyMap.put("messages", messages);
    bodyMap.put("temperature", temperature);
    bodyMap.put("stream", Boolean.FALSE);

    try {
      HttpRequest req = HttpRequest.newBuilder(URI.create(provider.baseUrl + chatPath))
          .timeout(Duration.ofMillis(chatTimeoutMs))
          .header("Authorization", "Bearer " + apiKey)
          .header("Content-Type", "application/json; charset=utf-8")
          .header("Accept", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(Json.write(bodyMap), StandardCharsets.UTF_8))
          .build();
      HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      int code = res.statusCode();
      String body = res.body();

      if (code < 200 || code >= 300) {
        return new Chat(false, null, "HTTP " + code + " " + userMessage(body));
      }
      String content = contentOf(body);
      if (content == null || content.trim().isEmpty()) {
        return new Chat(false, null, "模型返回了空内容");
      }
      return new Chat(true, content.trim(), null);
    } catch (IOException e) {
      return new Chat(false, null, e.getClass().getSimpleName() + ": " + e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new Chat(false, null, "请求被中断");
    } catch (RuntimeException e) {
      return new Chat(false, null, e.getMessage());
    }
  }

  private static String contentOf(String body) {
    try {
      Map<String, Object> m = Json.obj(Json.parse(body));
      List<Object> choices = Json.arr(m.get("choices"));
      if (choices == null || choices.isEmpty()) return null;
      Map<String, Object> first = Json.obj(choices.get(0));
      Map<String, Object> msg = Json.obj(first.get("message"));
      return Json.str(msg, "content", null);
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static String apiErrorMessage(String body) {
    try {
      Map<String, Object> m = Json.obj(Json.parse(body));
      Map<String, Object> err = Json.obj(m.get("error"));
      String msg = Json.str(err, "message", null);
      if (msg != null) return msg;
    } catch (RuntimeException ignored) {
      // 落到下面统一截断
    }
    return brief(body);
  }

  /**
   * 给用户看的错误摘要。
   * 原始报错里带 request_id 一类的排障信息，直接显示在界面上既长又劝退，
   * 这里去掉它并压到一行，只留下「是 Key 无效还是网络不通」这个关键事实。
   */
  private static String userMessage(String body) {
    String msg = apiErrorMessage(body).replaceAll("\\(request_id:[^)]*\\)", "");
    msg = msg.replaceAll("\\s+", " ").trim();
    return msg.length() > 90 ? msg.substring(0, 90) + "…" : msg;
  }

  private static String brief(String s) {
    if (s == null) return "";
    String t = s.replaceAll("\\s+", " ").trim();
    return t.length() > 160 ? t.substring(0, 160) + "…" : t;
  }

  private static String trimTrailingSlash(String s) {
    String t = s == null ? "" : s.trim();
    while (t.endsWith("/")) t = t.substring(0, t.length() - 1);
    return t;
  }

  private static List<Object> listOf(List<Object> l) {
    return l == null ? new ArrayList<>() : l;
  }

  /** 由「配置 + 历史 + 知识库上下文」组装给模型的 messages。 */
  public static List<Map<String, Object>> buildMessages(String systemPrompt, List<Object> history,
                                                        int historyTurns, String userQuestion,
                                                        String kbContext) {
    List<Map<String, Object>> messages = new ArrayList<>();
    Map<String, Object> sys = new LinkedHashMap<>();
    sys.put("role", "system");
    sys.put("content", systemPrompt);
    messages.add(sys);

    if (history != null && historyTurns > 0) {
      int start = Math.max(0, history.size() - historyTurns * 2);
      for (int i = start; i < history.size(); i++) {
        Map<String, Object> h = Json.obj(history.get(i));
        String role = Json.str(h, "role", "");
        String content = Json.str(h, "content", "");
        if (content.isEmpty()) continue;
        if (!"user".equals(role) && !"assistant".equals(role)) continue;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("content", content);
        messages.add(m);
      }
    }

    Map<String, Object> user = new LinkedHashMap<>();
    user.put("role", "user");
    user.put("content", kbContext == null || kbContext.isEmpty()
        ? userQuestion
        : userQuestion + "\n\n【知识库参考】\n" + kbContext);
    messages.add(user);
    return messages;
  }
}
