package campus;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * 数据文件加载。
 *
 * 知识库、同义词表、参数、停用词、测试用例都放在 docs/data 下由 Java 与 JS 两个实现共享，
 * 保证「两个引擎读同一份数据」不是口号。目录按顺序探测，兼容从项目根目录、
 * 从 java 目录、以及从打包目录运行的不同情况。
 */
public final class Data {

  private static final String[] CANDIDATES = {
      "docs/data",
      "../docs/data",
      "../../docs/data",
      "data"
  };

  private static Path resolvedDir;
  private static final Map<String, Map<String, Object>> CACHE = new HashMap<>();

  private Data() {
  }

  public static synchronized Path dir() {
    if (resolvedDir != null) return resolvedDir;
    String override = System.getProperty("campus.data");
    if (override != null) {
      Path p = Paths.get(override).toAbsolutePath().normalize();
      if (Files.isDirectory(p)) {
        resolvedDir = p;
        return resolvedDir;
      }
      throw new IllegalStateException("campus.data 指定的目录不存在：" + p);
    }
    for (String c : CANDIDATES) {
      Path p = Paths.get(c).toAbsolutePath().normalize();
      if (Files.isDirectory(p) && Files.exists(p.resolve("kb.json"))) {
        resolvedDir = p;
        return resolvedDir;
      }
    }
    throw new IllegalStateException(
        "找不到数据目录 docs/data。可在 java 目录下运行 run.bat，或用 -Dcampus.data=路径 指定。"
            + "当前工作目录：" + Paths.get("").toAbsolutePath());
  }

  /** 读取并缓存一个 JSON 文件（根为对象）。 */
  public static synchronized Map<String, Object> load(String fileName) {
    Map<String, Object> cached = CACHE.get(fileName);
    if (cached != null) return cached;
    Path p = dir().resolve(fileName);
    try {
      String text = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
      Map<String, Object> parsed = Json.obj(Json.parse(text));
      CACHE.put(fileName, parsed);
      return parsed;
    } catch (IOException e) {
      throw new IllegalStateException("读取数据文件失败：" + p + " —— " + e.getMessage(), e);
    }
  }

  public static Engine newEngine() {
    return new Engine(load("kb.json"), load("synonyms.json"), load("config.json"), load("stopwords.json"));
  }
}
