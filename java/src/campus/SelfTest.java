package campus;

import java.util.List;
import java.util.Map;

/**
 * 离线引擎自测：读 docs/data/testcases.json 跑全量用例。
 *
 * 输出格式与 tools/js-selftest.js 完全一致，便于 tools/equiv-check.js 直接逐行比对，
 * 用来证明 Java 与 JS 两个实现给出相同的 top1 与置信度分档。
 *
 * 运行：java -cp out campus.SelfTest
 *       java -cp out campus.SelfTest --explain "食堂几点开饭"   只解剖一条查询（与 tools/explain.js 同口径）
 */
public final class SelfTest {

  public static void main(String[] args) {
    try {
      if (args.length >= 2 && "--explain".equals(args[0])) {
        System.out.print(Data.newEngine().explain(args[1]));
        return;
      }

      Map<String, Object> synonyms = Data.load("synonyms.json");
      checkSynonyms(synonyms);
      System.err.println("[同义词检查] 通过");

      Engine engine = Data.newEngine();
      System.err.println("[引擎] Java 实现 " + Engine.VERSION + "，条目 " + engine.entries().size()
          + "，同义词规则 " + engine.synonymPairCount() + " 组");

      List<Object> cases = Json.arr(Data.load("testcases.json").get("cases"));
      int pass = 0, fail = 0;
      StringBuilder failures = new StringBuilder();

      for (Object co : cases) {
        Map<String, Object> c = Json.obj(co);
        String query = Json.str(c, "query", "");
        String expectId = c.get("expectId") == null ? "-" : String.valueOf(c.get("expectId"));
        String expectConf = Json.str(c, "expectConfidence", "");

        Map<String, Object> r = engine.ask(query);
        Object entryId = r.get("entryId");
        String topId = entryId == null ? "-" : String.valueOf(entryId);
        String conf = String.valueOf(r.get("confidence"));
        double coverage = Json.num(Json.obj(r.get("engine")), "coverage", 0);

        boolean ok = topId.equals(expectId) && (expectConf.isEmpty() || expectConf.equals(conf));
        if (ok) pass++;
        else {
          fail++;
          failures.append("  查询「").append(query).append("」期望 ").append(expectId).append('/')
              .append(expectConf.isEmpty() ? "*" : expectConf).append(" 实际 ").append(topId)
              .append('/').append(conf).append(" coverage=").append(numText(coverage)).append('\n');
          failures.append(engine.explain(query)).append('\n');
        }

        System.out.println(query + "\t" + topId + "\t" + conf + "\t" + numText(coverage) + "\t"
            + (ok ? "PASS" : "FAIL"));
      }

      System.err.println("[结果] 通过 " + pass + "，失败 " + fail + "，共 " + cases.size());
      if (fail > 0) {
        System.err.println("[失败明细]");
        System.err.print(failures);
        System.exit(2);
      }
    } catch (Exception e) {
      System.err.println("[自测异常] " + e);
      e.printStackTrace();
      System.exit(1);
    }
  }

  /** 与 tools/js-selftest.js 的 checkSynonyms 同口径：禁止同义词表出现嵌套替换风险。 */
  private static void checkSynonyms(Map<String, Object> synonyms) {
    List<Object> groups = Json.arr(synonyms.get("groups"));
    java.util.List<String[]> pairs = new java.util.ArrayList<>();
    for (Object go : groups) {
      Map<String, Object> g = Json.obj(go);
      String canonical = Json.str(g, "canonical", "");
      List<Object> variants = Json.arr(g.get("variants"));
      if (variants == null) continue;
      for (Object v : variants) {
        String variant = String.valueOf(v);
        if (variant.equals(canonical)) continue;
        if (canonical.contains(variant)) {
          throw new IllegalStateException("同义词嵌套风险：variant「" + variant + "」是 canonical「" + canonical + "」的子串");
        }
        pairs.add(new String[] { variant, canonical });
      }
    }
    pairs.sort((a, b) -> b[0].length() != a[0].length() ? b[0].length() - a[0].length() : a[0].compareTo(b[0]));
    for (int i = 0; i < pairs.size(); i++) {
      for (int j = i + 1; j < pairs.size(); j++) {
        if (pairs.get(i)[1].contains(pairs.get(j)[0])) {
          throw new IllegalStateException("同义词嵌套风险：先替换「" + pairs.get(i)[0] + "」→「"
              + pairs.get(i)[1] + "」，但结果里含后续待替换的「" + pairs.get(j)[0] + "」");
        }
        if (!pairs.get(i)[0].equals(pairs.get(j)[0]) && pairs.get(j)[0].contains(pairs.get(i)[0])) {
          throw new IllegalStateException("同义词包含风险：「" + pairs.get(i)[0] + "」被「" + pairs.get(j)[0] + "」包含");
        }
      }
    }
  }

  /** 整数不输出多余小数点，保证与 JS 端 Number.toString 的文本一致，便于逐行 diff。 */
  static String numText(double v) {
    if (v == Math.rint(v) && !Double.isInfinite(v) && Math.abs(v) < 1e15) {
      return Long.toString((long) v);
    }
    return Double.toString(v);
  }
}
