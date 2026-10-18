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
 * DeepSeek 调用封装（兼容一切 OpenAI 风格的 /models + /chat/completions 接口）。
 *
 * 只用 JDK 自带的 java.net.http.HttpClient，不引入任何第三方 HTTP 库——
 * 这样 java/lib 目录为空也能跑，评委在自己的电脑上 clone 下来直接就能编译。
 *
 * 两件事：
 *   probe() —— 探测「有没有网、Key 是否有效、模型是否可用」，供 /api/health 用；
 *   chat()  —— 一次对话补全，供分级路由在离线检索置信度不足时兜底。
 *
 * 关于超时：probe 的超时必须远小于前端的健康检查超时（app.js 里是 1500ms），
 * 否则前端会把「后端在探测」误判成「没有后端」，白白丢掉 Java 服务这条链路。
 * 因此服务启动时就把探测结果算好并缓存，/api/health 只读缓存、不做网络请求。
 */
public final class Llm {

  /** 探测结果。status 取值与前端约定一致：no_key / unreachable / invalid_key / ok。 */
  public static final class Probe {
    public final String status;
    public final boolean reachable;
    public final boolean valid;
    public final String model;
    public final String detail;

    Probe(String status, boolean reachable, boolean valid, String model, String detail) {
      this.status = status;
      this.reachable = reachable;
      this.valid = valid;
      this.model = model == null ? "" : model;
      this.detail = detail == null ? "" : detail;
    }

    public Map<String, Object> toJson() {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("status", status);
      m.put("reachable", reachable);
      m.put("valid", valid);
      m.put("model", model);
      if (!detail.isEmpty()) m.put("detail", detail);
      return m;
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
  private static final long PROBE_TTL_MS = 60_000L;

  private static Probe cachedProbe;
  private static long cachedProbeAt;
  private static String cachedProbeKey;

  private final Map<String, Object> cfg;
  private final String baseUrl;
  private final String model;
  private final String probePath;
  private final String chatPath;
  private final int probeTimeoutMs;
  private final int chatTimeoutMs;
  private final double temperature;
  private final String apiKey;
  private final HttpClient http;

  public Llm(Map<String, Object> llmConfig, String apiKey) {
    this.cfg = llmConfig == null ? new LinkedHashMap<>() : llmConfig;
    this.baseUrl = trimTrailingSlash(Json.str(cfg, "baseUrl", "https://api.deepseek.com"));
    this.model = Json.str(cfg, "model", "deepseek-chat");
    this.probePath = Json.str(cfg, "probePath", "/models");
    this.chatPath = Json.str(cfg, "chatPath", "/chat/completions");
    this.probeTimeoutMs = (int) Json.num(cfg, "probeTimeoutMs", 2500);
    this.chatTimeoutMs = (int) Json.num(cfg, "chatTimeoutMs", 30000);
    this.temperature = Json.num(cfg, "temperature", 0.3);
    this.apiKey = apiKey == null ? "" : apiKey.trim();
    this.http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(probeTimeoutMs))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();
  }

  public String modelName() {
    return model;
  }

  public boolean hasKey() {
    return !apiKey.isEmpty();
  }

  // ------------------------------------------------------------------ 密钥解析

  /**
   * 决定用哪个 Key，优先级：界面临时填的 > 环境变量 > java/config.local.properties。
   *
   * 为什么界面填的优先级最高：演示时「测试密钥」按钮必须测的就是用户刚填的那把 Key，
   * 如果被环境变量顶掉，用户会看到「填了新的 Key 还是无效」这种无从排查的现象。
   * 环境变量与本地配置文件则是「零配置自动联网」的便利通道。
   */
  public static String resolveKey(String requestKey) {
    if (requestKey != null && !requestKey.trim().isEmpty()) return requestKey.trim();
    String env = System.getenv("DEEPSEEK_API_KEY");
    if (env != null && !env.trim().isEmpty()) return env.trim();
    return readLocalKey();
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
          if (k.equals("deepseek.api.key") || k.equals("api.key") || k.equals("deepseekApiKey")) {
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
    String env = System.getenv("DEEPSEEK_API_KEY");
    if (env != null && !env.trim().isEmpty()) return true;
    return readLocalKey() != null;
  }

  /** 供 /api/health 使用：读缓存的探测结果，绝不在这里发起网络请求。 */
  public static synchronized Map<String, Object> healthCached() {
    Map<String, Object> out = new LinkedHashMap<>();
    String key = resolveKey(null);
    if (key == null) {
      out.put("status", "no_key");
      out.put("configured", false);
      out.put("reachable", false);
      out.put("valid", false);
      out.put("model", "");
      return out;
    }
    Probe p = cachedProbe;
    if (p == null) {
      // 启动时探测过才有缓存；理论上 Main 会先探测，这里只是兜底
      p = new Probe("no_key", false, false, "", "");
    }
    out.putAll(p.toJson());
    out.put("configured", true);
    return out;
  }

  /** 启动时或缓存过期时调用，真正发一次网络请求。 */
  public static synchronized Probe refreshProbe(String key) {
    if (key == null || key.trim().isEmpty()) {
      cachedProbe = new Probe("no_key", false, false, "", "未配置密钥");
      cachedProbeAt = System.currentTimeMillis();
      cachedProbeKey = null;
      return cachedProbe;
    }
    boolean fresh = cachedProbe != null
        && key.equals(cachedProbeKey)
        && System.currentTimeMillis() - cachedProbeAt < PROBE_TTL_MS;
    if (fresh) return cachedProbe;

    Map<String, Object> root = Data.load("config.json");
    Map<String, Object> llmCfg = root.get("llm") instanceof Map ? Json.obj(root.get("llm")) : null;
    Probe p = new Llm(llmCfg, key).probe();
    cachedProbe = p;
    cachedProbeAt = System.currentTimeMillis();
    cachedProbeKey = key;
    return p;
  }

  // ------------------------------------------------------------------ 调用

  /** 探测网络连通性与 Key 有效性。 */
  public Probe probe() {
    if (apiKey.isEmpty()) return new Probe("no_key", false, false, "", "未配置密钥");
    try {
      HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + probePath))
          .timeout(Duration.ofMillis(probeTimeoutMs))
          .header("Authorization", "Bearer " + apiKey)
          .header("Accept", "application/json")
          .GET()
          .build();
      HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      int code = res.statusCode();
      if (code >= 200 && code < 300) {
        return new Probe("ok", true, true, modelFromModels(res.body()), "");
      }
      if (code == 401 || code == 402 || code == 403) {
        return new Probe("invalid_key", true, false, "", "HTTP " + code + " " + brief(res.body()));
      }
      return new Probe("unreachable", false, false, "", "HTTP " + code + " " + brief(res.body()));
    } catch (IOException e) {
      return new Probe("unreachable", false, false, "", e.getClass().getSimpleName() + ": " + e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new Probe("unreachable", false, false, "", "探测被中断");
    } catch (RuntimeException e) {
      return new Probe("unreachable", false, false, "", e.getMessage());
    }
  }

  /** /models 返回里挑一个模型名，挑不到就退回配置里的默认模型。 */
  private String modelFromModels(String body) {
    try {
      Map<String, Object> m = Json.obj(Json.parse(body));
      List<Object> data = Json.arr(m.get("data"));
      if (data != null) {
        for (Object o : data) {
          String id = Json.str(Json.obj(o), "id", "");
          if (id.contains(model)) return id;
        }
        if (!data.isEmpty()) {
          String id = Json.str(Json.obj(data.get(0)), "id", "");
          if (!id.isEmpty()) return id;
        }
      }
    } catch (RuntimeException ignored) {
      // 解析失败无所谓，模型名只是给界面显示用的
    }
    return model;
  }

  /** 一次对话补全。 */
  public Chat chat(List<Map<String, Object>> messages) {
    if (apiKey.isEmpty()) return new Chat(false, null, "未配置密钥");

    Map<String, Object> bodyMap = new LinkedHashMap<>();
    bodyMap.put("model", model);
    bodyMap.put("messages", messages);
    bodyMap.put("temperature", temperature);
    bodyMap.put("stream", Boolean.FALSE);

    try {
      HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + chatPath))
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

  /** 由「配置 + 历史 + 知识库上下文」组装给模型的 messages。 */
  public static List<Map<String, Object>> buildMessages(String systemPrompt, List<Object> history,
                                                        int historyTurns, String userQuestion,
                                                        String kbContext) {
    List<Map<String, Object>> messages = new ArrayList<>();
    Map<String, Object> sys = new LinkedHashMap<>();
    sys.put("role", "system");
    sys.put("content", systemPrompt);
    messages.add(sys);

    if (history != null) {
      // 有些调用方会把「当前问题」也放进 history（例如界面上的「升级到联网」按钮，
      // 它把提问后的完整对话一起发过来），那样模型会看到同一个问题问两遍。
      // 这里把与本次提问重复的末尾那条去掉，当前问题由下面的 user 消息统一承载。
      int end = history.size();
      if (end > 0 && history.get(end - 1) instanceof Map) {
        Map<String, Object> last = Json.obj(history.get(end - 1));
        if ("user".equals(Json.str(last, "role", ""))
            && userQuestion != null
            && userQuestion.trim().equals(Json.str(last, "content", "").trim())) {
          end--;
        }
      }

      int from = Math.max(0, end - historyTurns);
      for (int i = from; i < end; i++) {
        if (!(history.get(i) instanceof Map)) continue;
        Map<String, Object> raw = Json.obj(history.get(i));
        String role = Json.str(raw, "role", "");
        String content = Json.str(raw, "content", "");
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
    if (kbContext != null && !kbContext.isEmpty()) {
      user.put("content", "【知识库参考（来自学校官方信息，优先采用）】\n" + kbContext
          + "\n\n【我的问题】\n" + userQuestion);
    } else {
      user.put("content", userQuestion);
    }
    messages.add(user);
    return messages;
  }
}
