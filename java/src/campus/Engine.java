package campus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 离线检索引擎（Java 实现）。
 *
 * 与 docs/assets/engine-js.js 严格对齐：同样的归一化、分词、停用词过滤、
 * BM25 多字段打分、加权覆盖率、置信度分档阈值，两边读同一份数据文件。
 *
 * 改动任一实现都必须同步另一边，然后用 tools/equiv-check.js 比对两个实现的结果。
 */
public final class Engine {

  public static final String VERSION = "0.1.0";

  /** 一条知识库记录及其索引信息。 */
  public static final class Entry {
    public final String id;
    public final String category;
    public final String question;
    public final String answer;
    public final String source;
    public final boolean verified;
    public final Map<String, Map<String, Double>> fieldTf = new LinkedHashMap<>();
    public final Map<String, Double> fieldLen = new LinkedHashMap<>();
    public final Set<String> tokenSet = new HashSet<>();

    Entry(Map<String, Object> raw) {
      this.id = Json.str(raw, "id", "");
      this.category = Json.str(raw, "category", "");
      this.question = Json.str(raw, "question", "");
      this.answer = Json.str(raw, "answer", "");
      this.source = Json.str(raw, "source", "");
      this.verified = Boolean.TRUE.equals(raw.get("verified"));
    }
  }

  private final Map<String, Object> config;
  private final Text.Replacer replacer;
  private final Set<String> stop = new HashSet<>();
  private final String[] fields = { "question", "altQuestions", "keywords", "answer" };

  private final double wUni;
  private final double wBi;
  private final double k1;
  private final double b;
  private final Map<String, Double> fieldWeights = new HashMap<>();
  private final double highCoverage;
  private final double midCoverage;
  private final double highGapRatio;
  private final int maxCandidates;

  private final List<Entry> entries = new ArrayList<>();
  private final Map<String, Integer> df = new HashMap<>();
  private final Map<String, Double> avgLen = new HashMap<>();

  public Engine(Map<String, Object> kb, Map<String, Object> synonyms,
                Map<String, Object> config, Map<String, Object> stopwords) {
    this.config = config;
    this.replacer = new Text.Replacer(synonyms);

    List<Object> stopList = stopwords == null ? null : Json.arr(stopwords.get("stopwords"));
    if (stopList != null) {
      for (Object w : stopList) stop.add(String.valueOf(w));
    }

    Map<String, Object> tw = Json.obj(config.get("tokenWeights"));
    Map<String, Object> bm = Json.obj(config.get("bm25"));
    Map<String, Object> fw = Json.obj(config.get("fieldWeights"));
    Map<String, Object> cf = Json.obj(config.get("confidence"));
    this.wUni = Json.num(tw, "unigram", 0.45);
    this.wBi = Json.num(tw, "bigram", 1.0);
    this.k1 = Json.num(bm, "k1", 1.2);
    this.b = Json.num(bm, "b", 0.75);
    for (String f : fields) fieldWeights.put(f, Json.num(fw, f, 1.0));
    this.highCoverage = Json.num(cf, "highCoverage", 0.7);
    this.midCoverage = Json.num(cf, "midCoverage", 0.35);
    this.highGapRatio = Json.num(cf, "highGapRatio", 0.12);
    this.maxCandidates = (int) Json.num(config, "maxCandidates", 3);

    build(kb);
  }

  private void build(Map<String, Object> kb) {
    Map<String, Double> totals = new HashMap<>();
    for (String f : fields) totals.put(f, 0.0);

    List<Object> rawEntries = Json.arr(kb.get("entries"));
    if (rawEntries == null) rawEntries = Collections.emptyList();

    for (Object o : rawEntries) {
      Map<String, Object> raw = Json.obj(o);
      Entry e = new Entry(raw);

      Map<String, String> texts = new HashMap<>();
      texts.put("question", norm(e.question));
      texts.put("altQuestions", norm(join(raw.get("altQuestions"))));
      texts.put("keywords", norm(join(raw.get("keywords"))));
      texts.put("answer", norm(e.answer));

      for (String f : fields) {
        Map<String, Double> tf = new HashMap<>();
        double len = 0;
        for (Text.Token tk : tok(texts.get(f))) {
          tf.merge(tk.t, tk.w, Double::sum);
          len += tk.w;
          e.tokenSet.add(tk.t);
        }
        e.fieldTf.put(f, tf);
        e.fieldLen.put(f, len);
        totals.merge(f, len, Double::sum);
      }
      for (String t : e.tokenSet) df.merge(t, 1, Integer::sum);
      entries.add(e);
    }

    double n = entries.isEmpty() ? 1 : entries.size();
    for (String f : fields) avgLen.put(f, totals.get(f) / n);
  }

  private static String join(Object arr) {
    List<Object> list = Json.arr(arr);
    if (list == null || list.isEmpty()) return "";
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < list.size(); i++) {
      if (i > 0) sb.append(' ');
      sb.append(list.get(i));
    }
    return sb.toString();
  }

  /** 归一化 + 同义词整串替换。 */
  public String norm(String s) {
    return replacer.apply(Text.normalize(s));
  }

  /** 分词 + 过滤停用词。索引端与查询端必须走同一个方法，否则打分口径不一致。 */
  private List<Text.Token> tok(String text) {
    List<Text.Token> all = Text.tokenize(text, wUni, wBi);
    List<Text.Token> out = new ArrayList<>(all.size());
    for (Text.Token t : all) {
      if (!stop.contains(t.t)) out.add(t);
    }
    return out;
  }

  private double idf(String t) {
    int d = df.getOrDefault(t, 0);
    int n = entries.size();
    return Math.log(1 + (n - d + 0.5) / (d + 0.5));
  }

  /*
   * 关于「错别字自动纠错」：JS 侧早期版本做过 Levenshtein 距离 <= 1 的二元组纠正，
   * 实测后被移除（原因见 engine-js.js 中的详细说明：中文二元组的距离 1 几乎等价于
   * 「两个字里共用一个字」，会把「今天天气怎么样」误判成作息类问题）。
   * Java 侧从一开始就不实现该逻辑，保持两边行为一致。
   * 常见错别字请写进 synonyms.json 作为人工整理的混淆表。
   */

  private List<Text.Token> queryTokens(String question) {
    List<Text.Token> toks = tok(norm(question == null ? "" : question));
    Set<String> seen = new HashSet<>();
    List<Text.Token> list = new ArrayList<>();
    for (Text.Token tk : toks) {
      if (!seen.add(tk.t)) continue;
      list.add(tk);
    }
    return list;
  }

  private double score(Entry e, List<Text.Token> q) {
    double total = 0;
    for (Text.Token tk : q) {
      double idf = idf(tk.t);
      for (String f : fields) {
        Double tfw = e.fieldTf.get(f).get(tk.t);
        if (tfw == null || tfw == 0) continue;
        double avg = avgLen.get(f);
        double ratio = avg > 0 ? e.fieldLen.get(f) / avg : 1;
        double denom = tfw + k1 * (1 - b + b * ratio);
        total += idf * fieldWeights.get(f) * (tfw * (k1 + 1)) / denom;
      }
    }
    return total;
  }

  /**
   * 加权覆盖率 = 命中的查询词权重和 / 查询词权重和。
   * 二元组（真正的词）权重 1.0、一元组（单字）0.45，不按个数统计。
   */
  private double coverage(Entry e, List<Text.Token> q) {
    double total = 0, hit = 0;
    for (Text.Token t : q) {
      total += t.w;
      if (e.tokenSet.contains(t.t)) hit += t.w;
    }
    return total > 0 ? hit / total : 0;
  }

  private static double round3(double v) {
    return Math.round(v * 1000) / 1000.0;
  }

  private static final class Scored {
    final Entry entry;
    final double score;
    final double cov;

    Scored(Entry entry, double score, double cov) {
      this.entry = entry;
      this.score = score;
      this.cov = cov;
    }
  }

  /** 离线检索主入口，返回可直接序列化为 JSON 的结果结构。 */
  public Map<String, Object> ask(String question) {
    long started = System.currentTimeMillis();
    List<Text.Token> q = queryTokens(question);

    List<Scored> scored = new ArrayList<>(entries.size());
    for (Entry e : entries) scored.add(new Scored(e, score(e, q), coverage(e, q)));
    Collections.sort(scored, new Comparator<Scored>() {
      @Override
      public int compare(Scored a, Scored b) {
        if (b.score != a.score) return b.score > a.score ? 1 : -1;
        return a.entry.id.compareTo(b.entry.id); // 并列时按 id 升序，保证两个实现一致
      }
    });

    Scored top = scored.isEmpty() ? null : scored.get(0);
    Scored second = scored.size() > 1 ? scored.get(1) : null;
    double topScore = top == null ? 0 : top.score;
    double gapRatio = topScore > 0 ? (topScore - (second == null ? 0 : second.score)) / topScore : 0;
    double cov = top == null ? 0 : top.cov;

    String level;
    if (top == null || topScore <= 0 || cov < midCoverage) level = "low";
    else if (cov >= highCoverage && gapRatio >= highGapRatio) level = "high";
    else level = "mid";

    Map<String, Object> messages = Json.obj(config.get("messages"));
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("mode", "offline");
    result.put("confidence", level);

    if ("low".equals(level)) {
      result.put("answer", Json.str(messages, "lowFallback", "这个问题我还没学到。"));
      result.put("entryId", null);
      result.put("entryQuestion", null);
      result.put("category", null);
      result.put("source", null);
      result.put("verified", null);
      result.put("notice", Json.str(messages, "lowNotice", "离线知识库未命中"));
    } else {
      String answer = top.entry.answer;
      if ("mid".equals(level)) {
        answer = Json.str(messages, "midPrefix", "") + "\n" + answer;
        result.put("notice", Json.str(messages, "midNotice", "离线知识库匹配度一般"));
      } else {
        result.put("notice", null);
      }
      result.put("answer", answer);
      result.put("entryId", top.entry.id);
      result.put("entryQuestion", top.entry.question);
      result.put("category", top.entry.category);
      result.put("source", top.entry.source);
      result.put("verified", top.entry.verified);
    }

    List<Object> candidates = new ArrayList<>();
    for (Scored s : scored) {
      if (s.score <= 0 || candidates.size() >= maxCandidates) break;
      Map<String, Object> c = new LinkedHashMap<>();
      c.put("id", s.entry.id);
      c.put("question", s.entry.question);
      c.put("category", s.entry.category);
      c.put("coverage", round3(s.cov));
      candidates.add(c);
    }
    result.put("candidates", candidates);

    List<Text.Token> matched = new ArrayList<>();
    if (top != null) {
      for (Text.Token t : q) {
        if (top.entry.tokenSet.contains(t.t)) matched.add(t);
      }
    }
    Collections.sort(matched, new Comparator<Text.Token>() {
      @Override
      public int compare(Text.Token a, Text.Token b) {
        if (b.w != a.w) return b.w > a.w ? 1 : -1;
        return a.t.compareTo(b.t);
      }
    });
    List<Object> matchedTerms = new ArrayList<>();
    for (int i = 0; i < matched.size() && i < 8; i++) matchedTerms.add(matched.get(i).t);
    result.put("matchedTerms", matchedTerms);

    Map<String, Object> meta = new LinkedHashMap<>();
    meta.put("impl", "java");
    meta.put("version", VERSION);
    meta.put("queryTokens", q.size());
    meta.put("topScore", round3(topScore));
    meta.put("coverage", round3(cov));
    meta.put("gapRatio", round3(gapRatio));
    result.put("engine", meta);
    result.put("timingMs", System.currentTimeMillis() - started);
    return result;
  }

  /** 单条查询的「解剖」，排查打分问题用。与 tools/explain.js 的口径一致。 */
  public String explain(String question) {
    StringBuilder sb = new StringBuilder();
    List<Text.Token> q = queryTokens(question);
    sb.append("原始查询 : ").append(question).append('\n');
    sb.append("归一化后 : ").append(Text.normalize(question)).append('\n');
    sb.append("同义词后 : ").append(norm(question)).append('\n');
    sb.append("查询词   : ");
    double total = 0;
    for (Text.Token t : q) {
      sb.append(t.t).append('(').append(wt(t.w)).append(") ");
      total += t.w;
    }
    sb.append("\n总权重   : ").append(wt(total)).append('\n');

    List<Scored> scored = new ArrayList<>(entries.size());
    for (Entry e : entries) scored.add(new Scored(e, score(e, q), coverage(e, q)));
    Collections.sort(scored, (a, b) -> Double.compare(b.score, a.score));

    sb.append("\n前 5 候选：\n");
    for (int i = 0; i < scored.size() && i < 5; i++) {
      Scored s = scored.get(i);
      StringBuilder hits = new StringBuilder();
      for (Text.Token t : q) {
        if (!s.entry.tokenSet.contains(t.t)) continue;
        if (hits.length() > 0) hits.append(',');
        hits.append(t.t);
      }
      sb.append(String.format("  %-26s score=%8.3f cov=%.3f 命中[%s]%n",
          s.entry.id, s.score, s.cov, hits));
    }
    return sb.toString();
  }

  public List<Entry> entries() {
    return entries;
  }

  /** 权重文本：整数值不带小数点，与 JS 端 Number.toString 一致，让两边的 explain 输出可以直接 diff。 */
  private static String wt(double v) {
    return v == Math.rint(v) ? Long.toString((long) v) : Double.toString(v);
  }

  public int synonymPairCount() {
    return replacer.size();
  }
}
