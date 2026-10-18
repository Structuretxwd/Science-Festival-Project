package campus;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 本地 HTTP 服务：既托管前端页面，又提供两个接口。
 *
 *   GET  /api/health   —— 前端启动时探测「有没有后端」，必须在 1.5s 内返回（只读缓存，不发网络请求）
 *   POST /api/ask      —— 提问，body {question, history, apiKey}
 *   其余路径            —— 当作静态文件，根目录是 docs/
 *
 * 全部基于 JDK 自带的 com.sun.net.httpserver，不引入 Web 框架。
 * 只绑定 127.0.0.1，不监听局域网，避免把演示服务暴露到外网。
 */
public final class Server {

  private static final int PORT_TRIES = 20;

  private final HttpServer http;
  private final Path webRoot;
  private final Router router;

  private Server(HttpServer http, Path webRoot, Router router) {
    this.http = http;
    this.webRoot = webRoot;
    this.router = router;
  }

  /**
   * 启动服务，返回实际监听的端口。
   * 配置里的 preferredPort 被占用时自动往后试，避免演示现场因为端口冲突起不来。
   */
  public static Server start(Router router, Map<String, Object> config) throws IOException {
    Map<String, Object> serverCfg =
        config.get("server") instanceof Map ? Json.obj(config.get("server")) : new LinkedHashMap<>();
    int preferred = (int) Json.num(serverCfg, "preferredPort", 8080);

    HttpServer http = null;
    int port = -1;
    for (int i = 0; i < PORT_TRIES; i++) {
      int candidate = preferred + i;
      try {
        http = HttpServer.create(new InetSocketAddress("127.0.0.1", candidate), 0);
        port = candidate;
        break;
      } catch (IOException e) {
        System.err.println("[server] 端口 " + candidate + " 不可用，尝试下一个…");
      }
    }
    if (http == null) {
      throw new IOException("端口 " + preferred + "~" + (preferred + PORT_TRIES - 1) + " 全部被占用，无法启动服务。");
    }

    Server s = new Server(http, webRoot(), router);
    s.http.createContext("/", s.new RootHandler());
    // 用线程池而不是默认的单线程调度：一次大模型调用可能耗时几十秒，
    // 单线程会把这期间的所有静态资源请求全部堵死。
    ExecutorService pool = Executors.newFixedThreadPool(8, r -> {
      Thread t = new Thread(r, "campus-http");
      t.setDaemon(false);
      return t;
    });
    s.http.setExecutor(pool);
    s.http.start();
    System.out.println("[server] 已监听 http://127.0.0.1:" + port + "/");
    return s;
  }

  public int port() {
    return http.getAddress().getPort();
  }

  public String url() {
    return "http://127.0.0.1:" + port() + "/";
  }

  public void stop() {
    http.stop(0);
  }

  /** 前端静态根目录：优先 -Dcampus.web，否则取数据目录的上一级（docs/data → docs）。 */
  private static Path webRoot() {
    String override = System.getProperty("campus.web");
    if (override != null && !override.trim().isEmpty()) {
      Path p = Paths.get(override).toAbsolutePath().normalize();
      if (Files.isDirectory(p)) return p;
      throw new IllegalStateException("campus.web 指定的目录不存在：" + p);
    }
    Path dataDir = Data.dir();
    Path docs = dataDir.getParent();
    if (docs != null && Files.isDirectory(docs)) return docs.toAbsolutePath().normalize();
    throw new IllegalStateException("无法定位前端目录，请用 -Dcampus.web=路径 指定包含 index.html 的目录。");
  }

  // ------------------------------------------------------------------ 请求处理

  private final class RootHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange ex) {
      String path = ex.getRequestURI().getPath();
      try {
        if (path.equals("/api/health")) {
          health(ex);
        } else if (path.equals("/api/ask")) {
          ask(ex);
        } else if (path.startsWith("/api/")) {
          json(ex, 404, error("未知接口：" + path));
        } else {
          serveStatic(ex, path);
        }
      } catch (Exception e) {
        System.err.println("[server] 处理 " + path + " 出错：" + e);
        try {
          json(ex, 500, error("服务内部错误：" + e.getMessage()));
        } catch (IOException ignored) {
          // 响应已经发出去了，只能放弃
        }
      } finally {
        ex.close();
      }
    }
  }

  private void health(HttpExchange ex) throws IOException {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("backend", "java");
    out.put("version", Engine.VERSION);
    out.put("school", router.school());
    out.put("dataDir", Data.dir().toString());

    Map<String, Object> eng = new LinkedHashMap<>();
    eng.put("impl", "java");
    eng.put("version", Engine.VERSION);
    eng.put("entries", router.engine().entries().size());
    eng.put("synonymPairs", router.engine().synonymPairCount());
    out.put("engine", eng);

    out.put("llm", Llm.healthCached());
    out.put("onlineMode", router.onlineMode());
    json(ex, 200, out);
  }

  private void ask(HttpExchange ex) throws IOException {
    if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
      json(ex, 405, error("/api/ask 只接受 POST"));
      return;
    }
    String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    Map<String, Object> req;
    try {
      req = Json.obj(Json.parse(body));
    } catch (RuntimeException e) {
      json(ex, 400, error("请求体不是合法 JSON"));
      return;
    }

    String question = Json.str(req, "question", "");
    if (question.trim().isEmpty()) {
      json(ex, 400, error("question 不能为空"));
      return;
    }
    String apiKey = Json.str(req, "apiKey", null);
    List<Object> history = req.get("history") instanceof List ? Json.arr(req.get("history")) : null;

    Router.AskOpts opts = new Router.AskOpts();
    opts.apiKey = apiKey;
    opts.providerId = Json.str(req, "provider", null);
    opts.baseUrl = Json.str(req, "baseUrl", null);
    opts.model = Json.str(req, "model", null);
    opts.forceOffline = Json.bool(req, "forceOffline", false);

    long t0 = System.currentTimeMillis();
    Map<String, Object> result;
    try {
      result = router.ask(question, history, opts);
    } catch (RuntimeException e) {
      // 引擎或大模型出异常也不能让页面白屏，返回一条可读的离线兜底
      System.err.println("[ask] 处理失败：" + e);
      Map<String, Object> fallback = new LinkedHashMap<>();
      fallback.put("mode", "offline");
      fallback.put("confidence", "low");
      fallback.put("answer", "抱歉，处理这个问题时出错了。请把问题换个说法再试一次。");
      fallback.put("entryId", null);
      fallback.put("entryQuestion", null);
      fallback.put("category", null);
      fallback.put("source", null);
      fallback.put("verified", null);
      fallback.put("candidates", List.of());
      fallback.put("matchedTerms", List.of());
      fallback.put("notice", "服务内部错误：" + e.getMessage());
      fallback.put("timingMs", System.currentTimeMillis() - t0);
      json(ex, 200, fallback);
      return;
    }

    logAsk(question, result, System.currentTimeMillis() - t0);
    json(ex, 200, result);
  }

  /** 给演示看的一行日志：走了哪条路、置信度多少、耗时多少。 */
  private static void logAsk(String question, Map<String, Object> result, long ms) {
    String mode = String.valueOf(result.get("mode"));
    String conf = String.valueOf(result.get("confidence"));
    String extra = "";
    if ("online".equals(mode) && result.get("engine") instanceof Map) {
      Map<String, Object> meta = Json.obj(result.get("engine"));
      Object label = meta.get("providerLabel");
      Object model = meta.get("model");
      extra = " · " + label + " / " + model;
      if (Boolean.FALSE.equals(meta.get("kbHit"))) extra += " · 知识库未覆盖";
    }
    String q = question.length() > 26 ? question.substring(0, 26) + "…" : question;
    System.out.println("[ask] \"" + q + "\" → " + conf + " (" + mode + extra + ", " + ms + "ms)");
  }

  // ------------------------------------------------------------------ 静态文件

  private void serveStatic(HttpExchange ex, String path) throws IOException {
    String rel = path.startsWith("/") ? path.substring(1) : path;
    if (rel.isEmpty()) rel = "index.html";
    try {
      rel = URLDecoder.decode(rel, StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      json(ex, 400, error("路径编码异常"));
      return;
    }

    Path target = webRoot.resolve(rel).normalize();
    // 防目录穿越：规范化之后必须仍在 webRoot 之内
    if (!target.startsWith(webRoot)) {
      text(ex, 403, "禁止访问该路径");
      return;
    }
    if (Files.isDirectory(target)) target = target.resolve("index.html");
    if (!Files.isRegularFile(target)) {
      text(ex, 404, "找不到资源：" + rel
          + "\n如果你是通过 Java 服务访问，请确认该文件位于 " + webRoot);
      return;
    }

    byte[] bytes = Files.readAllBytes(target);
    ex.getResponseHeaders().set("Content-Type", mime(target));
    // 演示作品不追求缓存收益，一律 no-store，避免改了文件还要清浏览器缓存
    ex.getResponseHeaders().set("Cache-Control", "no-store");
    ex.sendResponseHeaders(200, bytes.length);
    try (OutputStream os = ex.getResponseBody()) {
      os.write(bytes);
    }
  }

  private static String mime(Path p) {
    String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
    int dot = name.lastIndexOf('.');
    String ext = dot >= 0 ? name.substring(dot + 1) : "";
    switch (ext) {
      case "html": case "htm": return "text/html; charset=utf-8";
      case "css": return "text/css; charset=utf-8";
      case "js": case "mjs": return "text/javascript; charset=utf-8";
      case "json": return "application/json; charset=utf-8";
      case "svg": return "image/svg+xml";
      case "png": return "image/png";
      case "jpg": case "jpeg": return "image/jpeg";
      case "gif": return "image/gif";
      case "webp": return "image/webp";
      case "ico": return "image/x-icon";
      case "woff2": return "font/woff2";
      case "woff": return "font/woff";
      case "txt": case "md": return "text/plain; charset=utf-8";
      default: return "application/octet-stream";
    }
  }

  // ------------------------------------------------------------------ 响应工具

  private static void json(HttpExchange ex, int code, Object payload) throws IOException {
    byte[] bytes = Json.write(payload).getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    ex.getResponseHeaders().set("Cache-Control", "no-store");
    ex.sendResponseHeaders(code, bytes.length);
    try (OutputStream os = ex.getResponseBody()) {
      os.write(bytes);
    }
  }

  private static void text(HttpExchange ex, int code, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
    ex.sendResponseHeaders(code, bytes.length);
    try (OutputStream os = ex.getResponseBody()) {
      os.write(bytes);
    }
  }

  private static Map<String, Object> error(String message) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("ok", Boolean.FALSE);
    m.put("error", message);
    return m;
  }
}
