package campus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 分级路由：离线优先，在线模式下交给大模型润色。
 *
 * 规则（可在 config.json 的 llm.mode 里切换）：
 *   onlineAsk = "always"（默认，作品当前口径）
 *       → 只要处于「在线模式」（Key 有效 + 服务商匹配成功），**所有问题**都交给大模型，
 *         知识库检索结果作为唯一事实来源一起送过去；知识库没覆盖的问题则由大模型用
 *         通用常识作答（kbFallbackToModel = true 时）。
 *   onlineAsk = "auto"
 *       → 只有离线检索「不够确信」（mid / low）时才动用大模型，高置信直接返回原文。
 *
 * 为什么保留 "auto"：评委可以拔掉网线跑一遍，答案照样出来；也可以把 Key 填上再问同一个
 * 问题，观察答案从「知识库原文」变成「模型润色版」。两种口径都能现场演示，
 * 靠 config.json 一行切换，不需要改代码。
 *
 * 事实约束：知识库命中时，大模型只允许改写措辞，不允许添加参考之外的学校信息，
 * 也不允许改动数字 / 时间 / 金额 / 名称。这是为了避免大模型编造校园信息。
 *
 * 兜底策略：联网失败（断网、Key 失效、超时）不报错给用户，而是安静地回退到离线答案，
 * 并在 notice 里说明原因。比赛现场网络不可控，宁可降级也不能白屏。
 */
public final class Router {

  /** 一次提问的可选项（界面上填的东西都从这里进来）。 */
  public static final class AskOpts {
    public String apiKey;
    public String providerId;
    public String baseUrl;
    public String model;
    public boolean forceOffline;
  }

  private final Engine engine;
  private final Map<String, Object> llmCfg;
  private final String school;
  private final int historyTurns;
  private final boolean alwaysOnline;
  private final boolean kbFallback;
  private final Map<String, Engine.Entry> byId = new LinkedHashMap<>();

  public Router(Engine engine, Map<String, Object> config, Map<String, Object> kb) {
    this.engine = engine;
    Object rawLlm = config.get("llm");
    this.llmCfg = rawLlm instanceof Map ? Json.obj(rawLlm) : new LinkedHashMap<>();
    this.historyTurns = (int) Json.num(llmCfg, "historyTurns", 6);
    this.alwaysOnline = Llm.alwaysOnline();
    this.kbFallback = Llm.kbFallbackToModel();

    Map<String, Object> meta = kb.get("meta") instanceof Map ? Json.obj(kb.get("meta")) : new LinkedHashMap<>();
    this.school = Json.str(meta, "school", "本校");

    for (Engine.Entry e : engine.entries()) byId.put(e.id, e);
  }

  public Engine engine() {
    return engine;
  }

  public String school() {
    return school;
  }

  /** 在线模式的答题口径，供 /api/health 与启动横幅显示。 */
  public String onlineMode() {
    return alwaysOnline ? "always" : "auto";
  }

  /**
   * 回答一个问题。
   *
   * @param question 用户提问
   * @param history  之前的对话（形如 [{role,content}]），可为 null
   * @param opts     界面上填的 Key / 服务商 / 强制离线开关，可为 null
   */
  public Map<String, Object> ask(String question, List<Object> history, AskOpts opts) {
    long started = System.currentTimeMillis();

    Map<String, Object> offline = engine.ask(question);
    String level = String.valueOf(offline.get("confidence"));

    String key = Llm.resolveKey(opts == null ? null : opts.apiKey);

    // 没配 Key：纯离线模式
    if (key == null) return offline;

    // 界面上的「强制离线」开关：现场演示「断网也能答」时用
    if (opts != null && opts.forceOffline) {
      offline.put("notice", mergeNotice(offline.get("notice"),
          "已启用强制离线模式，本次回答完全来自本地知识库。"));
      return offline;
    }

    // auto 口径下，高置信的离线答案已经是原文，再让大模型复述只会引入偏差
    if (!alwaysOnline && "high".equals(level)) return offline;

    Llm.Match match = Llm.match(key, opts == null ? null : opts.providerId,
        opts == null ? null : opts.baseUrl, opts == null ? null : opts.model);
    if (!match.ok()) {
      offline.put("notice", mergeNotice(offline.get("notice"),
          "联网大模型暂时不可用（" + matchDetail(match) + "），已回退为离线答案。"));
      return offline;
    }

    boolean hasKb = offline.get("entryId") instanceof String;

    // 知识库没覆盖、且明确不允许通用常识兜底 → 保持离线话术，不硬编
    if (!hasKb && !kbFallback) {
      offline.put("notice", mergeNotice(offline.get("notice"),
          "知识库里没有收录这个问题，且当前不允许联网补充通用知识。"));
      return offline;
    }

    Llm llm = Llm.of(match.provider, key);
    Llm.Chat chat = llm.chat(Llm.buildMessages(
        hasKb ? systemWithKb() : systemNoKb(), history, historyTurns, question,
        hasKb ? kbContext(offline) : ""));

    if (!chat.ok) {
      offline.put("notice", mergeNotice(offline.get("notice"),
          "联网大模型暂时不可用（" + chat.error + "），已回退为离线答案。"));
      return offline;
    }

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("mode", "online");
    out.put("confidence", "high");
    out.put("answer", chat.content);
    out.put("entryId", offline.get("entryId"));
    out.put("entryQuestion", offline.get("entryQuestion"));
    out.put("category", offline.get("category"));
    out.put("source", llm.sourceLabel());
    // 保留离线条目的核实标记：模拟示例数据的警告不能因为走了大模型就消失
    out.put("verified", offline.get("verified"));
    out.put("candidates", offline.get("candidates"));
    out.put("matchedTerms", offline.get("matchedTerms"));
    out.put("notice", hasKb
        ? "本回答由联网大模型改写自知识库原文，事实以知识库为准。"
        : "知识库没有收录这个问题，以下内容由联网大模型用通用知识作答，仅供参考。");

    Map<String, Object> meta = new LinkedHashMap<>();
    meta.put("impl", "java");
    meta.put("version", Engine.VERSION);
    meta.put("model", llm.modelName());
    meta.put("provider", llm.providerId());
    meta.put("providerLabel", llm.providerLabel());
    meta.put("retrievalConfidence", level);
    meta.put("kbHit", hasKb);
    meta.put("queryTokens", offline.get("engine") instanceof Map
        ? Json.obj(offline.get("engine")).get("queryTokens") : null);
    out.put("engine", meta);
    out.put("timingMs", System.currentTimeMillis() - started);
    return out;
  }

  /**
   * 知识库命中时的提示词：只允许改写措辞，不允许动事实。
   *
   * 这段文案与 docs/assets/llm-js.js 里的 systemWithKb 必须逐字一致，
   * 否则「有后端」和「没后端」两条联网路径的风格会跑偏。
   */
  private String systemWithKb() {
    return "你叫「小助手」，是" + school + "的校园问答助手，服务对象是学生和家长。\n\n"
        + "用户消息里给了【知识库参考】，它是这次回答的唯一事实来源。\n"
        + "1. 只能用参考里的内容作答，可以改写、归纳、口语化，但不得添加参考之外的学校信息。\n"
        + "2. 不得改动参考中的数字、时间、金额、名称、电话、地点。\n"
        + "3. 参考内容不足以回答时，直接说「知识库里没有这部分记录」。\n"
        + "4. 不超过 180 字，不要用 Markdown 标题，需要分点时用简短序号。\n"
        + "5. 语气友好、干脆，像一个熟悉校园的学长学姐。";
  }

  /**
   * 知识库没有覆盖时的提示词：允许大模型用通用知识作答，但必须声明来源、不许编造校内信息。
   * 与 llm-js.js 里的 systemNoKb 必须逐字一致。
   */
  private String systemNoKb() {
    return "你叫「小助手」，是" + school + "的校园问答助手，服务对象是学生和家长。\n\n"
        + "这个问题校园知识库里没有记录，请你用通用知识回答，注意：\n"
        + "1. 开头先说明「这一点知识库里没有记录，以下是通用建议」。\n"
        + "2. 不要编造学校未公开的具体信息（电话、时间、金额、人数等），拿不准就直接说不知道。\n"
        + "3. 不超过 180 字，不要用 Markdown 标题，需要分点时用简短序号。\n"
        + "4. 语气友好、干脆，像一个熟悉校园的学长学姐。";
  }

  /**
   * 把离线检索到的 top-N 条目拼成给模型看的上下文。
   * 只喂检索结果、不喂整个知识库：既省 token，也让模型聚焦在当前问题上。
   */
  private String kbContext(Map<String, Object> offline) {
    List<String> ids = new ArrayList<>();
    Object top = offline.get("entryId");
    if (top instanceof String) ids.add((String) top);

    Object rawCands = offline.get("candidates");
    if (rawCands instanceof List) {
      for (Object o : Json.arr(rawCands)) {
        String id = Json.str(Json.obj(o), "id", "");
        if (!id.isEmpty() && !ids.contains(id)) ids.add(id);
      }
    }
    if (ids.isEmpty()) return "";

    StringBuilder sb = new StringBuilder();
    int n = 0;
    for (String id : ids) {
      if (n >= 3) break;
      Engine.Entry e = byId.get(id);
      if (e == null) continue;
      if (n > 0) sb.append("\n\n");
      sb.append("【").append(e.question).append("】\n").append(e.answer);
      n++;
    }
    return sb.toString();
  }

  /** 把匹配失败的内部状态翻译成一句给用户看的话。 */
  private static String matchDetail(Llm.Match m) {
    if ("invalid_key".equals(m.status)) {
      return m.detail.isEmpty() ? "密钥无效" : m.detail;
    }
    if ("unreachable".equals(m.status)) {
      return "网络不可用" + (m.detail.isEmpty() ? "" : "，" + m.detail);
    }
    return m.detail.isEmpty() ? m.status : m.detail;
  }

  private static String mergeNotice(Object existing, String extra) {
    String base = existing instanceof String ? ((String) existing).trim() : "";
    if (base.isEmpty()) return extra;
    return base + " " + extra;
  }
}
