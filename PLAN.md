# 「小助手」升级方案（可评审版）

> 本文档只描述**要做什么、动哪些文件、怎么验证**，尚未改动任何代码。
> 确认后再进入实现。三阶段按顺序推进，每一阶段独立可交付、可回滚。

---

## 0. 总览

| 阶段 | 目标 | 用户可见的产出 |
| --- | --- | --- |
| A | 在线模式默认走大模型；Key 自动匹配服务商 | 填任意一家的 Key 就能用，徽章显示「在线模式 · DeepSeek」 |
| B | 知识库整理成一份好管理的文档 + 文档子站点 | `docs/kb/` 里能像看网页一样读知识库，标注测试版本 |
| C | 反馈闭环 + 维护页 + GitHub Actions | 回答下方可反馈 → Issue → 自动去重/一致率判定 → 待审核 |

**贯穿三阶段的硬约束（来自项目既有约定）：**

1. 离线路径必须完好。任何时候拔网线、清空 Key，界面照常作答。
2. 双引擎等价性不能被破坏：`node tools/equiv-check.js` 必须保持 28/28 逐行一致。
3. `docs/` 内只能用相对路径（Pages 挂在子路径下），只有 `/api/*` 可以是绝对路径。
4. 任何 Key 都不得进入仓库。

---

## 阶段 A：在线模式默认联网 + 服务商自动匹配

### A1　行为变更

**现在**：只有置信度 `mid` / `low` 才会升级到大模型；`high` 直接返回知识库原文（见 [Router.java](file:///d:/project/TraeProject/Science%20Festival%20Project/java/src/campus/Router.java#L60-L80)）。

**改成**：处于「在线模式」（Key 有效且服务商匹配成功）时，**所有问题都送大模型**，知识库检索结果无条件作为唯一事实来源。

**事实约束（你确认的口径：只改写措辞，不动事实）**，系统提示词改为：

```
【知识库参考】是回答的唯一事实来源。
1. 只能对参考内容做改写、归纳、口语化，不得添加参考之外的任何学校相关信息。
2. 参考里没有的，直接回答「这一点知识库没有记录」，不要用常识补充。
3. 不得改动参考中的数字、时间、金额、名称、电话、地点。
4. 用自然口语化的中文作答，不超过 180 字，需要分点时用简短序号。
5. 语气友好、干脆，像一个熟悉校园的学长学姐。
```

**知识库未覆盖时（已确认口径）**：不做限制，**直接交给大模型用通用常识回答**，只要求它在开头说明「这一点知识库里没有记录」。

因此提示词分两套，由「离线检索是否命中」决定用哪一套：

| 情况 | 提示词 | 约束 |
| --- | --- | --- |
| 知识库命中（有 top1 条目） | `systemWithKb` | 只能改写参考内容，不得加事实、不得改数字 |
| 知识库未覆盖（无 top1 条目） | `systemNoKb` | 自由作答，但需声明「知识库没有记录」，且不得编造学校未公开信息 |

**离线开关**：面板新增「强制离线」开关。打开后即使 Key 有效也不联网，用于现场演示「断网也能答」。默认关闭。

### A2　配置结构：从单一 llm 段改为 providers 列表

[config.json](file:///d:/project/TraeProject/Science%20Festival%20Project/docs/data/config.json#L31-L40) 的 `llm` 段改为下方结构，Java 与 JS 读同一份文件：

```json
"llm": {
  "defaultProvider": "deepseek",
  "onlineAsk": "always",
  "providers": [
    { "id": "deepseek", "label": "DeepSeek", "baseUrl": "https://api.deepseek.com",
      "model": "deepseek-chat", "modelHints": ["deepseek"] },
    { "id": "moonshot", "label": "Kimi", "baseUrl": "https://api.moonshot.cn/v1",
      "model": "moonshot-v1-8k", "modelHints": ["moonshot", "kimi"] },
    { "id": "dashscope", "label": "通义千问", "baseUrl": "https://dashscope.aliyuncs.com/compatible-mode/v1",
      "model": "qwen-plus", "modelHints": ["qwen"] },
    { "id": "openai", "label": "OpenAI 兼容", "baseUrl": "https://api.openai.com/v1",
      "model": "gpt-4o-mini", "modelHints": ["gpt"] },
    { "id": "custom", "label": "自定义", "baseUrl": "", "model": "" }
  ],
  "probePath": "/models",
  "chatPath": "/chat/completions",
  "probeTimeoutMs": 2500,
  "chatTimeoutMs": 30000,
  "historyTurns": 6,
  "temperature": 0.3,
  "mode": { "onlineAsk": "always", "strictFacts": true, "allowCommonSense": false, "forceOffline": false }
}
```

`onlineAsk=always` / `auto` 两个取值控制 A1 的行为，便于回归对比。

### A3　自动匹配算法（两侧实现必须等价）

**关键认知**：Key 前缀（`sk-`）在 DeepSeek、Kimi、通义、OpenAI 之间高度重叠，**无法只靠前缀判断**。因此「自动匹配」的可靠实现是**探测式匹配**，前缀只用来给候选排序。

候选队列（按序去重）：

1. 面板里用户显式指定的服务商（若选了「自动」则跳过）
2. 上次匹配成功并缓存的 `{id, keyHash}`
3. 本地 `localStorage` 里保存的自定义 baseUrl（若填过）
4. 按 Key 前缀/长度启发式排序的全部内置服务商
5. `defaultProvider` 兜底

逐个 `GET {baseUrl}/models`（2.5s 超时）：

- 第一个 `200` → 锁定该服务商，并从返回的 `data[].id` 里按 `modelHints` 挑模型名。
- `401/403` → 记为「Key 可能无效」但**继续尝试下一个**（baseUrl 不匹配也会返回 401）。
- 超时/网络错 → 继续尝试下一个。

**缓存**：`localStorage['campus.provider'] = {id, baseUrl, model, keyHash}`。Key 变了重新匹配；否则启动时直接复用，不再多发探测请求。落盘位置与现有 `campus.apiKey` 并列，同样只存本机。

匹配失败的两种结论，分别对应现有徽章状态：

| 结论 | 判定 | 徽章 |
| --- | --- | --- |
| `invalid_key` | 所有候选都返回 401/403 | 密钥无效（橙） |
| `unreachable` | 所有候选都超时/网络错 | 离线模式 · 网络不可用 |

### A4　改动清单

| 文件 | 改动点 |
| --- | --- |
| [config.json](file:///d:/project/TraeProject/Science%20Festival%20Project/docs/data/config.json) | `llm` 段改 providers 结构 + `mode` 段（见 A2） |
| [java/.../Llm.java](file:///d:/project/TraeProject/Science%20Festival%20Project/java/src/campus/Llm.java) | 读 providers 列表；新增 `matchProvider(key)` 自动匹配 + 缓存；`probe()/chat()` 改为「已匹配的 provider」驱动；`healthCached()` 增加 provider 字段 |
| [java/.../Router.java](file:///d:/project/TraeProject/Science%20Festival%20Project/java/src/campus/Router.java) | `onlineAsk=always` 时不再看置信度，一律走 LLM；`kbContext()` 对 `high` 也生效；systemPrompt 换成 A1 版本 |
| [java/.../Server.java](file:///d:/project/TraeProject/Science%20Festival%20Project/java/src/campus/Server.java) | `/api/health` 的 llm 段带上 `provider`/`label`；`/api/ask` 支持 `forceOffline` 透传 |
| [java/.../Main.java](file:///d:/project/TraeProject/Science%20Festival%20Project/java/src/campus/Main.java) | 启动横幅与探测日志文案改为「自动匹配到 X 服务商」 |
| [docs/assets/llm-js.js](file:///d:/project/TraeProject/Science%20Festival%20Project/docs/assets/llm-js.js) | 与 Llm.java 对齐：providers 读取、`matchProvider`、`probe/chat` 用匹配结果；systemPrompt 换成 A1 版本 |
| [docs/assets/app.js](file:///d:/project/TraeProject/Science%20Festival%20Project/docs/assets/app.js) | `route()` 改为「在线模式一律先检索再联网」；`modelLabel()` 用 provider.label；无后端时不再需要用户点「升级」按钮；新增 provider 缓存读写与强制离线开关 |
| [docs/index.html](file:///d:/project/TraeProject/Science%20Festival%20Project/docs/index.html#L141-L193) | 面板新增：服务商下拉（自动/DeepSeek/Kimi/通义/OpenAI 兼容/自定义 baseUrl+model）、强制离线开关、匹配结果提示 |
| [docs/assets/app.css](file:///d:/project/TraeProject/Science%20Festival%20Project/docs/assets/app.css) | 新增下拉与开关样式（深浅两套） |
| [README.md](file:///d:/project/TraeProject/Science%20Festival%20Project/README.md) / [demo.md](file:///d:/project/TraeProject/Science%20Festival%20Project/demo.md) | 同步「在线模式」与「自动匹配」的说明与演示脚本 |

### A5　两条联网路径必须行为一致

| 场景 | 有 Java 后端 | 纯静态（Pages） |
| --- | --- | --- |
| 提问 | 前端 → `/api/ask`，后端决定是否联网 | 前端先本地检索 → `CampusLlm.chat(带 kbContext)` |
| 返回结构 | `mode/confidence/entryId/category/source/verified/candidates/matchedTerms` | **同一套字段**，前端渲染代码不分叉 |

现有 [app.js](file:///d:/project/TraeProject/Science%20Festival%20Project/docs/assets/app.js#L600-L657) 里「升级到联网大模型」按钮的语义随之改变：在线模式下不再需要点它；它的位置留给「强制离线时手动升级一次」的兜底。

### A6　降级与风险

- 联网失败（断网 / Key 失效 / 超时 / 服务商限流）→ 安静回退离线答案，`notice` 说明原因。绝不白屏。
- 在线模式下答案不再逐字节可复现（大模型有随机性）。**演示时用「强制离线」开关展示可复现性**，这是 A 阶段唯一的卖点损失，必须靠开关补回来。
- `temperature` 保持 0.3；事实约束靠提示词，不靠降温度。

---

## 阶段 B：知识库文档化 + 文档子站点

### B1　单一源文档

新增 `docs/data/kb.md` 作为**唯一的知识库源文件**，人读人改都方便；`docs/data/kb.json` 变为**编译产物**（引擎输入保持不变，双引擎等价性不受影响）。

格式（字段与现有 `Engine.Entry` 一一对应：id / category / question / altQuestions / keywords / answer / source / verified）：

```markdown
---
version: 0.2.0
updated: 2026-10-19
school: 北京景山学校京西实验学校
categories: [学校概况, 教学管理, 校园生活, ...]
---

## 食堂几点开饭
- id: life-canteen-hours
- category: 校园生活
- verified: true
- source: 校方公开资料
- alt: 餐厅几点开饭 / 什么时候可以吃饭
- keywords: 食堂, 开饭, 餐厅, 就餐

早餐 7:00–8:00，午餐 11:30–13:00，晚餐 17:30–19:00。
```

### B2　编译与校验

新增 `tools/build-kb.js`：`kb.md` → `kb.json`，并做**校验**（id 唯一、category 属于 `meta.categories`、必填字段齐全、`verified` 布尔、同义词表能被解析）。校验失败直接非零退出，避免脏数据进仓库。
同一脚本附带生成 `docs/data/kb.json` 的确定性输出（键序固定），避免每次编译都产生无意义 diff。

### B3　文档子站点

新增 `docs/kb/`：由 `kb.md` 渲染出的静态页面，Pages 直接托管，零依赖、无构建。

- 顶部醒目标注「**测试版本**」+ 知识库版本号与更新日期。
- 逐条展示，`verified: false` 的条目标「模拟示例数据」并整体置灰。
- 提供分类筛选与关键词语法高亮（纯手写，不引第三方库）。
- 与 `docs/index.html` 同处一个 Pages 站点，互相可达（`docs/kb/` 内一律相对路径）。

---

## 阶段 C：反馈闭环 + 维护页 + GitHub Actions

### C1　反馈入口（第一级：GitHub Issue）

- 每条回答下方新增「这条回答有用吗 / 反馈」按钮。
- 点击后打开**预填好的 GitHub Issue 新建页**（`?title=&body=&labels=feedback`），body 里自动带上：问题、回答、命中条目 id、置信度、检索详情、时间。
- 二级入口挂腾讯文档类表单链接（可写建议），仅在页面放一个外链按钮。

### C2　Actions 自动判定（阈值 95%）

新增 `.github/workflows/feedback.yml`：

- 触发：`issues: [opened, labeled]`，仅处理带 `feedback` 标签的 issue（`workflow_dispatch` 也留着便于手动重跑）。
- 处理逻辑**复用 `tools/` 的 JS 引擎**，不需要 API Key：
  1. 抓取 issue 标题/正文里的问题文本。
  2. 用引擎对该问题做检索，得到 `coverage` 与 `gapRatio`。
  3. **一致率口径**：`coverage ≥ 0.95` 且 `top1` 唯一（`gapRatio ≥ highGapRatio`）→ 判为「知识库已有答案」，一致。
  4. 一致 → 落到 `feedback/pending/<issue>.json`，并给 issue 打 `待审核` 标签、回一条评论说明已收录。
  5. 不一致 → 落到 `feedback/inbox/<issue>.json`，打 `待补充` 标签。
- 产物提交回仓库（`GITHUB_TOKEN`）→ Pages 自动重建。
- **人工关卡**：一致率达标也不直接改 `kb.json`，只进「待审核」；维护页确认后再开 PR 由人 Merge。这条是刻意的，避免机器人改坏知识库。

> 一致率阈值写在 `config.json` 的 `feedback` 段，方便现场调参，不硬编码。

### C3　维护页 `docs/admin.html`

纯静态、无后端的维护界面：

- 读取 `docs/data/kb.json` 与 `feedback/pending/*.json`、`feedback/inbox/*.json`（Pages 下直接 fetch 相对路径）。
- 列表视图：待审核反馈、未覆盖反馈、知识库总览（已核实 / 模拟数量）。
- 支持「合并进知识库」：在浏览器内生成新的 `kb.md` 片段与 `kb.json` diff，**下载 patch 文件 + 复制命令**，由使用者提交（浏览器不直接写仓库）。
- 顶部同样标注「测试版本 / 维护页」。

### C4　等价性注意

反馈相关字段只出现在 `feedback/*.json` 与 `kb.json` 的 `meta` 中，**不进入引擎的检索输入输出**，因此不影响 `equiv-check`。若后续要给 `Entry` 加字段，必须两侧同步。

---

## 5. 双引擎等价性影响清单

| 改动 | 是否影响 `equiv-check` | 说明 |
| --- | --- | --- |
| `config.json` 的 `llm` 段重构 | 否 | 只被 Llm/Router 读取，不进检索 |
| `Router` 改成 `onlineAsk=always` | **否**（但需专项测试） | 检索输出不变；联网行为需要新增用例覆盖 |
| `kb.json` 由 `kb.md` 编译而来 | 否 | 只要编译产物与现有一致，28 条用例结果不变 |
| 给 `Engine.Entry` 加字段 | **是** | Java `Engine.java` 与 `engine-js.js` 必须同步改，跑 `equiv-check` 验证 |
| `systemPrompt` 文案 | 否 | 两侧只需保持同一份文案（建议抽到 `config.json`，避免复制粘贴漂移） |

> **建议**：把 systemPrompt 从 [Router.java](file:///d:/project/TraeProject/Science%20Festival%20Project/java/src/campus/Router.java#L108-L116) 和 [llm-js.js](file:///d:/project/TraeProject/Science%20Festival%20Project/docs/assets/llm-js.js#L64-L77) 里各写一份，改为统一从 `config.json` 的 `prompts.system` 读取——现在这两处已经是手工同步的重复文案，是漂移风险点。

---

## 6. 验证方案

**每阶段结束都跑：**

1. `node tools/equiv-check.js` → 期望 `28/28` 逐行一致。
2. `node tools/js-selftest.js` → 全部用例通过。
3. `java/build.bat` → `java -cp out campus.Main --no-browser`，确认服务起来、横幅正常。
4. CDP 直驱浏览器（不依赖 `browser_*` 子代理）：

| 场景 | 期望 |
| --- | --- |
| 无 Key | 徽章灰、答案来自知识库、**零外网请求** |
| 填有效 Key | 自动匹配出服务商、徽章「在线模式 · X」、同一问题答案变为润色版 |
| 填错误 Key | `invalid_key`，回退离线，可读的错误提示 |
| 断网 + 有效 Key | 回退离线，`notice` 说明网络原因，不白屏 |
| 强制离线开关打开 | 即便 Key 有效也回到知识库原文 |
| Pages 子路径模拟（静态，无 `/api`） | 功能完整、零控制台错误、`/api/health` 404 为预期 |

---

## 7. 已确认的决策

1. **在线模式下知识库未覆盖的话题** → 直接交给大模型用通用常识作答（见 A1 的两套提示词）。
2. **知识库源文档格式** → Markdown 分块（见 B1）。
3. **「强制离线」开关** → 要，放在设置面板里，默认关闭。

> 阶段 A 已完成实现与验证：`node tools/equiv-check.js` 28/28 两侧逐字节一致、Java 编译通过、`README.md` / `demo.md` 已同步。两条部署路径（Java 后端 + 纯静态 Pages）均已浏览器实测：徽章状态、服务商下拉、强制离线开关、离线作答、低置信升级入口全部符合预期。
>
> **唯一未现场验证项**：真实 Key 的在线作答（当前环境无可用 Key 且外网受限，探测返回 `unreachable`）——需在一台能联网且有 Key 的机器上补测「自动匹配服务商 → 在线改写」一次，确认返回结构无误。
