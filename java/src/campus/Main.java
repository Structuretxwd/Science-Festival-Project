package campus;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 启动器：起本地服务 → 预探测大模型 → 用应用窗口模式打开界面。
 *
 * 目标使用体验是「双击 run.bat，等一两秒，窗口自己弹出来」，
 * 不要求评委懂命令行，也不要求他们手动敲 URL。
 *
 * 参数：
 *   --no-browser   只起服务不开浏览器（自动化测试用）
 */
public final class Main {

  public static void main(String[] args) {
    boolean openBrowser = true;
    for (String a : args) {
      if ("--no-browser".equals(a)) openBrowser = false;
    }

    Map<String, Object> config;
    Map<String, Object> kb;
    Engine engine;
    try {
      config = Data.load("config.json");
      kb = Data.load("kb.json");
      engine = Data.newEngine();
    } catch (RuntimeException e) {
      System.err.println("[fatal] 初始化失败：" + e.getMessage());
      System.err.println("        请确认 docs/data 下的 kb.json / synonyms.json / config.json / stopwords.json 都在。");
      System.exit(1);
      return;
    }

    Router router = new Router(engine, config, kb);

    // 启动时就把大模型探测做完。前端 /api/health 有 1.5 秒超时，
    // 每次刷新都让它现场探测的话，网络稍慢就会被误判成「没有后端」。
    if (Llm.hasServerKey()) {
      long t0 = System.currentTimeMillis();
      Llm.Probe p = Llm.refreshProbe(Llm.resolveKey(null));
      System.out.println("[llm] 服务端已配置密钥 —— 探测：" + p.status
          + (p.model.isEmpty() ? "" : " · 模型 " + p.model)
          + (p.detail.isEmpty() ? "" : " · " + p.detail)
          + " (" + (System.currentTimeMillis() - t0) + "ms)");
    } else {
      System.out.println("[llm] 未配置服务端密钥 —— 默认离线模式；"
          + "可在界面右上角「联网设置」里临时填入 Key 启用在线模式。");
    }

    Server server;
    try {
      server = Server.start(router, config);
    } catch (IOException e) {
      System.err.println("[fatal] 服务启动失败：" + e.getMessage());
      System.exit(1);
      return;
    }

    banner(server, router, kb);

    Runtime.getRuntime().addShutdownHook(new Thread(() -> System.out.println("\n[server] 已停止，再见。")));

    if (openBrowser) {
      openBrowser(server.url());
    } else {
      System.out.println("[ui] 已跳过自动打开浏览器（--no-browser）");
    }
  }

  private static void banner(Server server, Router router, Map<String, Object> kb) {
    Map<String, Object> meta = kb.get("meta") instanceof Map ? Json.obj(kb.get("meta")) : null;

    int entries = router.engine().entries().size();
    int verified = 0;
    for (Engine.Entry e : router.engine().entries()) if (e.verified) verified++;

    String kbVersion = meta == null ? "" : Json.str(meta, "version", "");
    String kbUpdated = meta == null ? "" : Json.str(meta, "updated", "");
    String kbInfo = entries + " 条（已核实 " + verified + " / 模拟示例 " + (entries - verified) + "）";
    if (!kbVersion.isEmpty()) kbInfo += "  ·  知识库 v" + kbVersion;
    if (!kbUpdated.isEmpty()) kbInfo += "，更新于 " + kbUpdated;

    System.out.println();
    System.out.println("  ============================================================");
    System.out.println("   校园智能体「小助手」  ·  Java 版  v" + Engine.VERSION);
    System.out.println("  ------------------------------------------------------------");
    System.out.println("   学校      " + router.school());
    System.out.println("   知识库    " + kbInfo);
    System.out.println("   同义词    " + router.engine().synonymPairCount() + " 组替换规则");
    System.out.println("   访问地址  " + server.url());
    System.out.println("  ------------------------------------------------------------");
    System.out.println("   离线检索永远可用；填了 API Key 且能联网时，");
    System.out.println("   置信度不足的问题会自动升级给大模型补充作答。");
    System.out.println("   关闭本窗口即停止服务（或按 Ctrl+C）。");
    System.out.println("  ============================================================");
    System.out.println();
  }

  /**
   * 优先用 Edge/Chrome 的 --app 模式打开：窗口没有地址栏和标签页，
   * 看起来就是个桌面应用，比裸浏览器体面得多。找不到就退回系统默认浏览器。
   */
  private static void openBrowser(String url) {
    List<String> candidates = new ArrayList<>();
    addEnv(candidates, "ProgramFiles(x86)", "\\Microsoft\\Edge\\Application\\msedge.exe");
    addEnv(candidates, "ProgramFiles", "\\Microsoft\\Edge\\Application\\msedge.exe");
    addEnv(candidates, "ProgramFiles", "\\Google\\Chrome\\Application\\chrome.exe");
    addEnv(candidates, "ProgramFiles(x86)", "\\Google\\Chrome\\Application\\chrome.exe");
    addEnv(candidates, "LOCALAPPDATA", "\\Google\\Chrome\\Application\\chrome.exe");

    for (String c : candidates) {
      Path p;
      try {
        p = Paths.get(c);
      } catch (RuntimeException e) {
        continue;
      }
      if (!Files.isRegularFile(p)) continue;
      try {
        new ProcessBuilder(p.toString(), "--app=" + url, "--window-size=1100,800").start();
        System.out.println("[ui] 已用 " + p.getFileName() + " 以应用窗口模式打开界面");
        return;
      } catch (IOException e) {
        // 这个浏览器起不来，试下一个
      }
    }

    try {
      new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start();
      System.out.println("[ui] 未找到 Edge/Chrome，已用系统默认浏览器打开界面");
    } catch (IOException e) {
      System.out.println("[ui] 无法自动打开浏览器，请手动访问 " + url);
    }
  }

  private static void addEnv(List<String> out, String envName, String suffix) {
    String base = System.getenv(envName);
    if (base == null || base.trim().isEmpty()) return;
    String p = base + suffix;
    if (!out.contains(p)) out.add(p);
  }
}
