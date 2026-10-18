package campus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 解析 / 序列化。
 *
 * 项目按「不引入构建工具」的约定推进，所以不能靠 Maven 拉 Gson/Jackson，
 * 也不好把第三方 jar 直接塞进仓库，因此自己实现够用的这一小部分：
 * 解析对象/数组/字符串/数字/布尔/null，序列化同样的类型。
 *
 * 解析结果的类型约定：
 *   对象 -> LinkedHashMap&lt;String,Object&gt;（保留字段顺序，便于调试比对）
 *   数组 -> ArrayList&lt;Object&gt;
 *   数字 -> Double          布尔 -> Boolean          字符串 -> String          空 -> null
 */
public final class Json {

  private final String s;
  private int i;

  private Json(String s) {
    this.s = s;
  }

  public static Object parse(String text) {
    Json p = new Json(text);
    p.ws();
    Object v = p.value();
    p.ws();
    if (p.i != p.s.length()) {
      throw new IllegalArgumentException("JSON 解析：尾部存在多余内容，位置 " + p.i);
    }
    return v;
  }

  // ------------------------------------------------------------ 解析

  private void ws() {
    while (i < s.length()) {
      char c = s.charAt(i);
      if (c == ' ' || c == '\n' || c == '\r' || c == '\t') i++;
      else break;
    }
  }

  private Object value() {
    if (i >= s.length()) throw new IllegalArgumentException("JSON 解析：内容意外结束");
    char c = s.charAt(i);
    switch (c) {
      case '{': return object();
      case '[': return array();
      case '"': return string();
      case 't': expect("true"); return Boolean.TRUE;
      case 'f': expect("false"); return Boolean.FALSE;
      case 'n': expect("null"); return null;
      default: return number();
    }
  }

  private void expect(String word) {
    if (!s.startsWith(word, i)) throw new IllegalArgumentException("JSON 解析：期望 " + word + "，位置 " + i);
    i += word.length();
  }

  private Map<String, Object> object() {
    Map<String, Object> m = new LinkedHashMap<>();
    i++; // {
    ws();
    if (i < s.length() && s.charAt(i) == '}') { i++; return m; }
    while (true) {
      ws();
      String k = string();
      ws();
      if (s.charAt(i) != ':') throw new IllegalArgumentException("JSON 解析：对象缺少冒号，位置 " + i);
      i++;
      ws();
      m.put(k, value());
      ws();
      char c = s.charAt(i);
      if (c == ',') { i++; continue; }
      if (c == '}') { i++; return m; }
      throw new IllegalArgumentException("JSON 解析：对象分隔符异常，位置 " + i);
    }
  }

  private List<Object> array() {
    List<Object> list = new ArrayList<>();
    i++; // [
    ws();
    if (i < s.length() && s.charAt(i) == ']') { i++; return list; }
    while (true) {
      ws();
      list.add(value());
      ws();
      char c = s.charAt(i);
      if (c == ',') { i++; continue; }
      if (c == ']') { i++; return list; }
      throw new IllegalArgumentException("JSON 解析：数组分隔符异常，位置 " + i);
    }
  }

  private String string() {
    if (s.charAt(i) != '"') throw new IllegalArgumentException("JSON 解析：期望字符串，位置 " + i);
    i++;
    StringBuilder sb = new StringBuilder();
    while (true) {
      char c = s.charAt(i++);
      if (c == '"') return sb.toString();
      if (c != '\\') { sb.append(c); continue; }
      char e = s.charAt(i++);
      switch (e) {
        case '"': sb.append('"'); break;
        case '\\': sb.append('\\'); break;
        case '/': sb.append('/'); break;
        case 'b': sb.append('\b'); break;
        case 'f': sb.append('\f'); break;
        case 'n': sb.append('\n'); break;
        case 'r': sb.append('\r'); break;
        case 't': sb.append('\t'); break;
        case 'u':
          sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
          i += 4;
          break;
        default: throw new IllegalArgumentException("JSON 解析：非法转义 \\" + e);
      }
    }
  }

  private Double number() {
    int start = i;
    while (i < s.length()) {
      char c = s.charAt(i);
      if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') i++;
      else break;
    }
    if (start == i) throw new IllegalArgumentException("JSON 解析：非法字符 '" + s.charAt(i) + "'，位置 " + i);
    return Double.valueOf(s.substring(start, i));
  }

  // ------------------------------------------------------------ 取值辅助

  @SuppressWarnings("unchecked")
  public static Map<String, Object> obj(Object o) {
    return (Map<String, Object>) o;
  }

  @SuppressWarnings("unchecked")
  public static List<Object> arr(Object o) {
    return (List<Object>) o;
  }

  public static String str(Map<String, Object> o, String key, String def) {
    Object v = o == null ? null : o.get(key);
    return v instanceof String ? (String) v : def;
  }

  public static double num(Map<String, Object> o, String key, double def) {
    Object v = o == null ? null : o.get(key);
    return v instanceof Double ? (Double) v : def;
  }

  public static boolean bool(Map<String, Object> o, String key, boolean def) {
    Object v = o == null ? null : o.get(key);
    return v instanceof Boolean ? (Boolean) v : def;
  }

  // ------------------------------------------------------------ 序列化

  public static String write(Object o) {
    StringBuilder sb = new StringBuilder();
    writeTo(sb, o);
    return sb.toString();
  }

  private static void writeTo(StringBuilder sb, Object o) {
    if (o == null) { sb.append("null"); return; }
    if (o instanceof String) { writeString(sb, (String) o); return; }
    if (o instanceof Boolean) { sb.append(o.toString()); return; }
    if (o instanceof Double || o instanceof Integer || o instanceof Long) {
      sb.append(numberText(((Number) o).doubleValue()));
      return;
    }
    if (o instanceof Map) {
      sb.append('{');
      boolean first = true;
      for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
        if (!first) sb.append(',');
        first = false;
        writeString(sb, String.valueOf(e.getKey()));
        sb.append(':');
        writeTo(sb, e.getValue());
      }
      sb.append('}');
      return;
    }
    if (o instanceof List) {
      sb.append('[');
      boolean first = true;
      for (Object v : (List<?>) o) {
        if (!first) sb.append(',');
        first = false;
        writeTo(sb, v);
      }
      sb.append(']');
      return;
    }
    writeString(sb, String.valueOf(o));
  }

  /** 整数值不输出多余的小数点，避免 1 被写成 1.0 影响可读性。 */
  private static String numberText(double v) {
    if (v == Math.rint(v) && !Double.isInfinite(v) && Math.abs(v) < 1e15) {
      return Long.toString((long) v);
    }
    return Double.toString(v);
  }

  private static void writeString(StringBuilder sb, String v) {
    sb.append('"');
    for (int k = 0; k < v.length(); k++) {
      char c = v.charAt(k);
      switch (c) {
        case '"': sb.append("\\\""); break;
        case '\\': sb.append("\\\\"); break;
        case '\n': sb.append("\\n"); break;
        case '\r': sb.append("\\r"); break;
        case '\t': sb.append("\\t"); break;
        case '\b': sb.append("\\b"); break;
        case '\f': sb.append("\\f"); break;
        default:
          if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
          else sb.append(c);
      }
    }
    sb.append('"');
  }
}
