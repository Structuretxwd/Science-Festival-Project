package campus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 文本处理：归一化、同义词整串替换、一元组 + 二元组混合分词。
 *
 * 本类与 docs/assets/engine-js.js 中的同名逻辑逐行对齐，任何改动必须两边同步，
 * 并跑 tools/equiv-check.js 验证两个实现给出相同结果。
 */
public final class Text {

  /** 一个查询词 / 索引词：文本 + 权重（二元组 1.0，一元组 0.45）。 */
  public static final class Token {
    public final String t;
    public final double w;

    public Token(String t, double w) {
      this.t = t;
      this.w = w;
    }
  }

  private Text() {
  }

  public static boolean isCJK(char c) {
    return c >= 0x4e00 && c <= 0x9fff;
  }

  private static boolean isAlnumLower(char c) {
    return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
  }

  /** 全角转半角 + 转小写。用 Locale.ROOT 避免土耳其语等区域设置影响大小写。 */
  public static String foldWidth(String s) {
    StringBuilder sb = new StringBuilder(s.length());
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c >= 0xff01 && c <= 0xff5e) sb.append((char) (c - 0xfee0));
      else if (c == 0x3000) sb.append(' ');
      else sb.append(c);
    }
    return sb.toString().toLowerCase(Locale.ROOT);
  }

  /**
   * 归一化：全角转半角 -> 转小写 -> 非「汉字/字母/数字」一律替换为空格 -> 折叠空格。
   *
   * 标点替换为空格而不是删除，这样「食堂？几点」不会产生跨标点的伪二元组，
   * 同时答案里的「【示例数据】」这类标记会被自动甩掉，不污染索引。
   */
  public static String normalize(String s) {
    if (s == null) s = "";
    String folded = foldWidth(s);
    StringBuilder sb = new StringBuilder(folded.length());
    for (int i = 0; i < folded.length(); i++) {
      char c = folded.charAt(i);
      if (isCJK(c) || isAlnumLower(c)) sb.append(c);
      else sb.append(' ');
    }
    // 折叠连续空格并去除首尾空格，等价于 JS 的 replace(/\s+/g,' ').trim()
    StringBuilder out = new StringBuilder(sb.length());
    boolean lastSpace = false;
    for (int i = 0; i < sb.length(); i++) {
      char c = sb.charAt(i);
      if (c == ' ') {
        if (!lastSpace && out.length() > 0) out.append(' ');
        lastSpace = true;
      } else {
        out.append(c);
        lastSpace = false;
      }
    }
    while (out.length() > 0 && out.charAt(out.length() - 1) == ' ') {
      out.setLength(out.length() - 1);
    }
    return out.toString();
  }

  /**
   * 中文按「一元组 + 二元组」混合切分，英文数字按整段切分。
   *
   * 为什么要一元组：纯二元组对「食堂什么时候开门」这类换说法的问法召回太差；
   * 补上一元组后单字也能命中，召回明显变好。二元组权重高于一元组，
   * 保证「食堂」这种真正的词比单个「食」字贡献更大，精度不至于崩。
   */
  public static List<Token> tokenize(String text, double wUni, double wBi) {
    List<Token> out = new ArrayList<>();
    int n = text.length();
    int i = 0;
    while (i < n) {
      char c = text.charAt(i);
      if (c == ' ') {
        i++;
        continue;
      }
      if (isCJK(c)) {
        int j = i;
        while (j < n && isCJK(text.charAt(j))) j++;
        for (int k = i; k < j; k++) out.add(new Token(text.substring(k, k + 1), wUni));
        for (int k = i; k + 1 < j; k++) out.add(new Token(text.substring(k, k + 2), wBi));
        i = j;
      } else {
        int q = i;
        while (q < n && isAlnumLower(text.charAt(q))) q++;
        out.add(new Token(text.substring(i, q), wBi));
        i = q;
      }
    }
    return out;
  }

  /**
   * 同义词整串替换器。
   *
   * 按 variant 长度从长到短排序后逐个做全局字符串替换，排序保证「长词优先」，
   * 避免短词先替换后把长词破坏掉。canonical 中含有该 variant 的条目会被跳过，
   * 否则会产生嵌套替换（tools/js-selftest.js 与 campus.SelfTest 都会做这项校验）。
   */
  public static final class Replacer {

    private final List<String[]> pairs = new ArrayList<>();

    public Replacer(Map<String, Object> synonyms) {
      List<Object> groups = Json.arr(synonyms.get("groups"));
      if (groups == null) return;
      for (Object g : groups) {
        Map<String, Object> gm = Json.obj(g);
        String canonical = normalize(Json.str(gm, "canonical", ""));
        List<Object> variants = Json.arr(gm.get("variants"));
        if (variants == null) continue;
        for (Object v : variants) {
          String variant = normalize(String.valueOf(v));
          if (variant.isEmpty() || canonical.isEmpty() || variant.equals(canonical)) continue;
          if (canonical.contains(variant)) continue;
          pairs.add(new String[] { variant, canonical });
        }
      }
      Collections.sort(pairs, new Comparator<String[]>() {
        @Override
        public int compare(String[] a, String[] b) {
          if (a[0].length() != b[0].length()) return b[0].length() - a[0].length();
          return a[0].compareTo(b[0]);
        }
      });
    }

    public String apply(String text) {
      for (String[] p : pairs) {
        if (text.contains(p[0])) text = text.replace(p[0], p[1]);
      }
      return text;
    }

    public int size() {
      return pairs.size();
    }
  }
}
