package campus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 分级路由：离线优先，只有离线检索「不够确信」时才动用大模型。
 *
 * 规则（也是这个作品的核心卖点）：
 *   confidence = high          → 直接返回离线答案。不联网、不花钱、毫秒级、结果可复现。
 *   confidence = mid / low     → 若配了 Key，把离线检索到的 top-N 条目作为上下文
 *                                喂给大模型做 RAG 补答；没配 Key 就照常返回离线结果。
 *
 * 这样设计的好处是可验证：评委可以拔掉网线跑一遍，答案照样出来；
 * 也可以把 Key 填上再问同一个问题，观察答案从「知识库原文」变成「模型润色版」。
 *
 * 兜底策略：联网失败（断网、Key 失效、超时）不报错给用户，而是安静地回退到离线答案，
 * 并在 notice 里说明原因。比赛现场网络不可控，宁可降级也不能白屏。
 */
public final class Router {

  private final Engine engine;
  private final Map<String, Object> llmCfg;
  private final String school;
  private final int historyTurns;
  private final Map<String, Engine.Entry> byId = new LinkedHashMap<>();

  public Router(Engine engine, Map<String, Object> config, Map<String, Object> kb) {
    this.engine = engine;
    Object rawLlm = config.get("llm");
    this.llmCfg = rawLlm instanceof Map ? Json.obj(rawLlm) : new LinkedHashMap<>();
    this.historyTurns = (int) Json.num(llmCfg, "historyTurns", 6);

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

  /**
   * 回答一个问题。
   *
   * @param question   用户提问
   * @param history    之前的对话（形如 [{role,content}]），可为 null
   * @param requestKey 界面上填的 Key，可为 null
   */
  public Map<String, Object> ask(String question, List<Object> history, String requestKey) {
    long started = System.currentTimeMillis();

    Map<String, Object> offline = engine.ask(question);
    String level = String.valueOf(offline.get("confidence"));

    String key = Llm.resolveKey(requestKey);

    // 高置信：离线答案已经是原文，再让大模型复述一遍只会引入偏差，直接用离线结果。
    // 未配置 Key：退化为纯离线模式。
    if (key == null || "high".equals(level)) {
      return offline;
    }

    Llm llm = new Llm(llmCfg, key);
    Llm.Chat chat = llm.chat(Llm.buildMessages(
        systemPrompt(), history, historyTurns, question, kbContext(offline)));

    if (!chat.ok) {
      offline.put("notice", mergeNotice(
          offline.get("notice"),
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
    out.put("source", "联网大模型 · " + llm.modelName());
    out.put("verified", null);
    out.put("candidates", offline.get("candidates"));
    out.put("matchedTerms", offline.get("matchedTerms"));
    out.put("notice", "本回答由联网大模型生成，并参考了离线检索到的校园资料；"
        + "校内信息请以学校官方发布为准。");

    Map<String, Object> meta = new LinkedHashMap<>();
    meta.put("impl", "java");
    meta.put("version", Engine.VERSION);
    meta.put("model", llm.modelName());
    meta.put("retrievalConfidence", level);
    meta.put("queryTokens", offline.get("engine") instanceof Map
        ? Json.obj(offline.get("engine")).get("queryTokens") : null);
    out.put("engine", meta);
    out.put("timingMs", System.currentTimeMillis() - started);
    return out;
  }

  private String systemPrompt() {
    return "你叫「小助手」，是" + school + "的校园问答助手，服务对象是学生和家长。\n\n"
        + "回答要求：\n"
        + "1. 如果下面给了【知识库参考】，优先依据它作答，用自然、口语化的中文把答案讲清楚，不要照抄原文格式。\n"
        + "2. 知识库没有覆盖的内容，可以用常识回答，但要明确说明「这一点知识库里没有，以下仅供参考」。\n"
        + "3. 涉及学校未公开的具体信息（电话、时间、金额、人数等）又拿不准时，直接说不知道，不要编造。\n"
        + "4. 回答控制在 180 字以内，不要用 Markdown 标题，需要分点时用简短的序号。\n"
        + "5. 语气友好、干脆，像一个熟悉校园的学长学姐。";
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

  private static String mergeNotice(Object existing, String extra) {
    String base = existing instanceof String ? ((String) existing).trim() : "";
    if (base.isEmpty()) return extra;
    return base + " " + extra;
  }
}
