/* ==========================================================================
   校园智能体「小助手」· 演示界面逻辑
   纯静态、零依赖、完全离线可用。

   运行路径：
   A) 有后端   —— 通过 /api/ask 提问（Java 服务托管时）
   B) 无后端   —— 用 window.CampusEngine（本地检索引擎）作答
      · 需联网大模型时用 window.CampusLlm（本地直连）

   两种路径返回「统一响应结构」，本文件只负责渲染。
   ========================================================================== */
(function () {
  'use strict';

  /* ------------------------------------------------------------------
     常量
     ------------------------------------------------------------------ */
  var LS_THEME = 'campus.theme';
  var LS_KEY = 'campus.apiKey';
  var LS_PROVIDER = 'campus.provider';      // 上次自动匹配成功的服务商
  var LS_PROVIDER_SEL = 'campus.providerSel'; // 面板里手动选定的服务商（'' = 自动匹配）
  var LS_FORCE_OFFLINE = 'campus.forceOffline';
  var LS_CUSTOM_BASE = 'campus.customBase';   // 自定义服务商地址
  var LS_CUSTOM_MODEL = 'campus.customModel'; // 自定义模型名
  var HEALTH_TIMEOUT = 1500;
  var HISTORY_LIMIT = 12; // 送给模型的历史消息条数（约 6 轮）

  // 兜底快捷提问（当知识库数据读不到时使用）
  var FALLBACK_QUICK = [
    '北京景山学校京西实验学校是一所什么学校？',
    '学校的学制是怎么设置的？',
    '学校有哪些设施？',
    '食堂几点开饭？',
    '怎么请假？',
    '学校有哪些社团？怎么参加？',
    '怎么联系学校？',
    '你知道些什么？'
  ];

  /* ------------------------------------------------------------------
     内联 SVG 图标（不使用 emoji / 图标字体）
     ------------------------------------------------------------------ */
  var ICON = {
    book: '<svg viewBox="0 0 24 24" width="14" height="14" aria-hidden="true"><path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20"/><path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2Z"/></svg>',
    warn: '<svg viewBox="0 0 24 24" width="12" height="12" aria-hidden="true"><path d="M10.3 3.9 1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0Z"/><path d="M12 9v4"/><path d="M12 17h.01"/></svg>',
    check: '<svg viewBox="0 0 24 24" width="12" height="12" aria-hidden="true"><path d="M20 6 9 17l-5-5"/></svg>',
    info: '<svg viewBox="0 0 24 24" width="14" height="14" aria-hidden="true"><circle cx="12" cy="12" r="9"/><path d="M12 8h.01"/><path d="M12 12v4.5"/></svg>',
    chevron: '<svg viewBox="0 0 24 24" width="14" height="14" aria-hidden="true"><path d="m6 9 6 6 6-6"/></svg>',
    sparkles: '<svg viewBox="0 0 24 24" width="17" height="17" aria-hidden="true"><path d="M12 3l1.9 5.1L19 10l-5.1 1.9L12 17l-1.9-5.1L5 10l5.1-1.9L12 3Z"/><path d="M19 15l.8 2.2L22 18l-2.2.8L19 21l-.8-2.2L16 18l2.2-.8L19 15Z"/></svg>',
    arrow: '<svg viewBox="0 0 24 24" width="14" height="14" aria-hidden="true"><path d="M5 12h14"/><path d="m13 6 6 6-6 6"/></svg>',
    help: '<svg viewBox="0 0 24 24" width="14" height="14" aria-hidden="true"><circle cx="12" cy="12" r="9"/><path d="M9.6 9.2a2.5 2.5 0 1 1 3.4 2.3c-.7.3-1 .9-1 1.6v.4"/><path d="M12 17h.01"/></svg>',
    cap: '<svg viewBox="0 0 32 32" width="19" height="19" aria-hidden="true"><path d="M16 7.5 6.8 12l9.2 4.5 9.2-4.5L16 7.5Z" fill="currentColor"/><path d="M10.6 14.2v4c0 1.9 2.4 3.3 5.4 3.3s5.4-1.4 5.4-3.3v-4" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"/></svg>'
  };

  /* ------------------------------------------------------------------
     DOM 引用
     ------------------------------------------------------------------ */
  var $ = function (sel) { return document.querySelector(sel); };

  var el = {
    stream: $('#stream'),
    empty: $('#empty'),
    messages: $('#messages'),
    quickList: $('#quickList'),
    welcomeTags: $('#welcomeTags'),
    welcomeSub: $('#welcomeSub'),
    brandSub: $('#brandSub'),
    composer: $('#composer'),
    input: $('#input'),
    sendBtn: $('#sendBtn'),
    hintMode: $('#composerHint'),
    badge: $('#modeBadge'),
    badgeText: $('#modeBadgeText'),
    themeBtn: $('#themeBtn'),
    clearBtn: $('#clearBtn'),
    banner: $('#envBanner'),
    bannerText: $('#envBannerText'),
    bannerClose: $('#envBannerClose'),
    panel: $('#panel'),
    panelClose: $('#panelClose'),
    panelStatus: $('#panelStatus'),
    scrim: $('#scrim'),
    keyInput: $('#keyInput'),
    keyReveal: $('#keyReveal'),
    keyTest: $('#keyTest'),
    keyClear: $('#keyClear'),
    probe: $('#probeResult'),
    providerSel: $('#providerSel'),
    customRow: $('#customRow'),
    customBase: $('#customBase'),
    customModel: $('#customModel'),
    forceOffline: $('#forceOffline')
  };

  /* ------------------------------------------------------------------
     状态
     ------------------------------------------------------------------ */
  var state = {
    kbMeta: null,
    entries: [],
    entriesById: {},
    kbLoaded: false,
    backend: null,          // /api/health 返回对象（无后端时为 null）
    engine: null,           // CampusEngine 实例
    engineError: null,      // 'missing' | 'error' | null
    llmStatus: 'no_key',    // no_key | unreachable | invalid_key | ok
    llmModel: '',
    provider: null,         // 匹配到的服务商 {id,label,baseUrl,model}
    providerSel: '',        // 面板里手动选定的服务商 id（'' = 自动匹配）
    forceOffline: false,    // 强制离线开关
    onlineMode: 'always',   // always = 在线模式下所有问题都过大模型
    kbFallback: true,       // 知识库未覆盖时是否允许大模型用通用常识作答
    apiKey: '',
    busy: false,
    history: [],            // [{role, content}]
    lastResult: null,       // 最近一次检索结果（供「升级到联网大模型」复用）
    typingToken: null
  };

  /* ------------------------------------------------------------------
     小工具
     ------------------------------------------------------------------ */
  function timeoutSignal(ms) {
    if (typeof AbortSignal !== 'undefined' && typeof AbortSignal.timeout === 'function') {
      return AbortSignal.timeout(ms);
    }
    var ctrl = new AbortController();
    setTimeout(function () { ctrl.abort(); }, ms);
    return ctrl.signal;
  }

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  /* markdown-lite 渲染：【】强调 + 换行；不使用任何 markdown 解析器 */
  function renderRich(text) {
    return esc(text).replace(/\n/g, '<br>').replace(/【([^】]*)】/g, '<span class="em">$1</span>');
  }

  /* 把文本拆成 [{t, em}] 片段，供打字机逐字输出 */
  function parseSegments(text) {
    var out = [];
    var re = /【([^】]*)】/g;
    var last = 0, m;
    text = String(text == null ? '' : text);
    while ((m = re.exec(text)) !== null) {
      if (m.index > last) out.push({ t: text.slice(last, m.index), em: false });
      out.push({ t: m[1], em: true });
      last = re.lastIndex;
    }
    if (last < text.length) out.push({ t: text.slice(last), em: false });
    if (!out.length) out.push({ t: '', em: false });
    return out;
  }

  function prefersReducedMotion() {
    return window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  }

  function elFromHTML(html) {
    var d = document.createElement('div');
    d.innerHTML = html.trim();
    return d.firstElementChild;
  }

  function pct(n) {
    var v = Number(n);
    if (!isFinite(v)) return '';
    return Math.round(Math.min(1, Math.max(0, v)) * 100) + '%';
  }

  /* ------------------------------------------------------------------
     主题
     ------------------------------------------------------------------ */
  function effectiveDark() {
    var t = document.documentElement.getAttribute('data-theme');
    if (t === 'dark') return true;
    if (t === 'light') return false;
    return window.matchMedia('(prefers-color-scheme: dark)').matches;
  }

  function syncThemeBtn() {
    var dark = effectiveDark();
    el.themeBtn.setAttribute('aria-label', dark ? '切换到浅色主题' : '切换到深色主题');
    el.themeBtn.setAttribute('title', dark ? '切换到浅色主题' : '切换到深色主题');
  }

  function initTheme() {
    try {
      var saved = localStorage.getItem(LS_THEME);
      if (saved === 'light' || saved === 'dark') {
        document.documentElement.setAttribute('data-theme', saved);
      }
    } catch (e) { /* 隐私模式下 localStorage 可能不可用 */ }
    syncThemeBtn();

    var mq = window.matchMedia('(prefers-color-scheme: dark)');
    var onChange = function () {
      if (!document.documentElement.getAttribute('data-theme')) syncThemeBtn();
    };
    if (mq.addEventListener) mq.addEventListener('change', onChange);
    else if (mq.addListener) mq.addListener(onChange);

    el.themeBtn.addEventListener('click', function () {
      var next = effectiveDark() ? 'light' : 'dark';
      document.documentElement.setAttribute('data-theme', next);
      try { localStorage.setItem(LS_THEME, next); } catch (e) { /* ignore */ }
      syncThemeBtn();
    });
  }

  /* ------------------------------------------------------------------
     模式徽章
     ------------------------------------------------------------------ */
  /* 服务商显示名：自动匹配到谁就显示谁 */
  function modelLabel() {
    if (state.provider && state.provider.label) return state.provider.label;
    if (state.llmModel) return state.llmModel;
    return '联网大模型';
  }

  /* 「在线模式」是否真的可用：匹配成功 + 没开强制离线 */
  function onlineReady() {
    return state.llmStatus === 'ok' && !!state.provider && !state.forceOffline;
  }

  /* 面板里选的服务商 + 自定义地址，一起塞给匹配逻辑 */
  function providerOpts() {
    var o = {};
    if (state.providerSel) o.provider = state.providerSel;
    if (state.providerSel === 'custom') {
      o.baseUrl = el.customBase.value.trim();
      o.model = el.customModel.value.trim();
    }
    return o;
  }

  var BADGE_TEXT = {
    no_key: '离线模式 · 未配置问答密钥',
    unreachable: '离线模式 · 网络不可用',
    invalid_key: '密钥无效',
    ok: '在线模式 · ',
    probing: '正在检测密钥…'
  };

  function setLlmStatus(status, model, provider) {
    if (typeof model === 'string' && model) state.llmModel = model;
    if (provider) state.provider = provider;
    state.llmStatus = status;

    // 强制离线时徽章按「离线」配色显示，避免误导
    el.badge.setAttribute('data-status', state.forceOffline ? 'no_key' : status);
    var text = BADGE_TEXT[status] || BADGE_TEXT.no_key;
    if (status === 'ok') text = '在线模式 · ' + modelLabel();
    if (state.forceOffline && status === 'ok') text = '强制离线模式';
    el.badgeText.textContent = text;

    syncPanelStatus();
    syncComposerHint();
    el.sendBtn.title = onlineReady() ? '发送（在线模式）' : '发送（离线模式）';
  }

  function syncComposerHint() {
    var map = {
      ok: '当前：在线模式，答案由大模型基于知识库改写',
      invalid_key: '当前：离线模式（密钥无效）',
      unreachable: '当前：离线模式（网络不可用）',
      no_key: '当前：离线模式，检索本地知识库'
    };
    el.hintMode.textContent = state.forceOffline
      ? '当前：强制离线模式，答案完全来自本地知识库'
      : (map[state.llmStatus] || map.no_key);
  }

  function syncPanelStatus() {
    var map = {
      ok: ['ok', '在线模式已就绪 · ' + modelLabel()
        + (state.llmModel ? ' / ' + state.llmModel : '')],
      invalid_key: ['invalid_key', '密钥无效：请检查 Key 是否正确或已过期'],
      unreachable: ['unreachable', '网络不可用：无法连接大模型服务'],
      no_key: ['no_key', '离线模式：未配置问答密钥'],
      probing: ['probing', '正在检测密钥…']
    };
    var v;
    if (state.forceOffline) {
      v = ['no_key', state.llmStatus === 'ok'
        ? '强制离线模式已开启 · 已匹配 ' + modelLabel() + '，关闭开关后立即生效'
        : '强制离线模式已开启，答案完全来自本地知识库'];
    } else {
      v = map[state.llmStatus] || map.no_key;
    }
    el.panelStatus.setAttribute('data-status', v[0]);
    el.panelStatus.innerHTML = '<span class="dot"></span><span>' + esc(v[1]) + '</span>';
  }

  /* ------------------------------------------------------------------
     环境提示条
     ------------------------------------------------------------------ */
  function showBanner(html) {
    el.bannerText.innerHTML = html;
    el.banner.hidden = false;
  }

  /* ------------------------------------------------------------------
     初始化：探测后端 / 读取知识库
     ------------------------------------------------------------------ */
  function probeBackend() {
    return fetch('/api/health', {
      signal: timeoutSignal(HEALTH_TIMEOUT),
      headers: { 'Accept': 'application/json' }
    }).then(function (res) {
      if (!res.ok) return null;
      return res.text().then(function (t) {
        var data;
        try { data = JSON.parse(t); } catch (e) { return null; } // 非 JSON 一律视为无后端
        return (data && typeof data === 'object') ? data : null;
      });
    }).catch(function () { return null; });
  }

  function loadKb() {
    return fetch('data/kb.json', { cache: 'no-store' })
      .then(function (res) {
        if (!res.ok) throw new Error('HTTP ' + res.status);
        return res.json();
      })
      .catch(function () { return null; });
  }

  function indexKb(kb) {
    state.kbMeta = (kb && kb.meta) || null;
    state.entries = (kb && Array.isArray(kb.entries)) ? kb.entries : [];
    state.entriesById = {};
    state.entries.forEach(function (e) { if (e && e.id) state.entriesById[e.id] = e; });
    state.kbLoaded = state.entries.length > 0;
  }

  function renderMeta() {
    var school = (state.kbMeta && state.kbMeta.school) || '';
    var cats = (state.kbMeta && state.kbMeta.categories) || [];

    el.brandSub.textContent = school
      ? school + ' · 离线知识库'
      : '本地校园知识库 · 离线可用';

    el.welcomeSub.innerHTML = school
      ? '我可以回答关于 <b>' + esc(school) + '</b> 的校园问题，答案来自本地知识库。'
      : '我可以回答关于学校的校园问题，答案来自本地知识库。';

    if (cats.length) {
      el.welcomeTags.innerHTML = cats.slice(0, 7).map(function (c) {
        return '<span class="welcome-tag">' + esc(c) + '</span>';
      }).join('');
    }
  }

  /* 快捷提问：从知识库每个分类里各取一条代表性问题 */
  function renderQuickPrompts() {
    var list = [];
    if (state.kbLoaded && state.kbMeta && Array.isArray(state.kbMeta.categories)) {
      state.kbMeta.categories.forEach(function (cat) {
        var hit = state.entries.filter(function (e) { return e.category === cat; })[0];
        if (hit && hit.question) list.push(hit.question);
      });
    }
    if (list.length < 6) {
      FALLBACK_QUICK.forEach(function (q) { if (list.indexOf(q) === -1) list.push(q); });
    }
    list = list.slice(0, 8);

    el.quickList.innerHTML = list.map(function (q) {
      return '<button class="quick" type="button" data-q="' + esc(q) + '">' + esc(q) + '</button>';
    }).join('');
  }

  /* ------------------------------------------------------------------
     本地引擎
     ------------------------------------------------------------------ */
  function ensureEngine() {
    if (state.engine) return Promise.resolve(state.engine);
    if (state.engineError) return Promise.resolve(null);
    if (!window.CampusEngine || typeof window.CampusEngine.create !== 'function') {
      state.engineError = 'missing';
      return Promise.resolve(null);
    }
    return Promise.resolve()
      .then(function () { return window.CampusEngine.create(); })
      .then(function (engine) { state.engine = engine; return engine; })
      .catch(function (err) {
        state.engineError = 'error';
        console.error('[小助手] 检索引擎启动失败：', err);
        return null;
      });
  }

  /* ------------------------------------------------------------------
     提问路由
     ------------------------------------------------------------------ */
  /**
   * 走 Java 后端。服务商、自定义地址、强制离线开关都随请求一起带过去，
   * 这样前端和后端对「在线模式」的理解永远一致。
   */
  function askBackend(question, history) {
    var body = { question: question, history: history };
    if (state.apiKey) body.apiKey = state.apiKey;
    var p = providerOpts();
    if (p.provider) body.provider = p.provider;
    if (p.baseUrl) body.baseUrl = p.baseUrl;
    if (p.model) body.model = p.model;
    if (state.forceOffline) body.forceOffline = true;

    return fetch('/api/ask', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Accept': 'application/json' },
      body: JSON.stringify(body)
    }).then(function (res) {
      if (!res.ok) {
        var e = new Error('后端返回 HTTP ' + res.status);
        e.code = 'backend_http';
        e.status = res.status;
        throw e;
      }
      return res.text().then(function (t) {
        try { return JSON.parse(t); }
        catch (err) {
          var e2 = new Error('后端返回了非 JSON 内容');
          e2.code = 'backend_badjson';
          throw e2;
        }
      });
    });
  }

  function route(question, history) {
    // A) 有后端：联不联网由后端按同一份配置决定
    if (state.backend) {
      return askBackend(question, history);
    }
    // B) 无后端 → 本地引擎检索，在线模式下再把结果交给大模型改写
    return ensureEngine().then(function (engine) {
      if (!engine) {
        var e = new Error('ENGINE_NOT_READY');
        e.code = 'engine';
        throw e;
      }
      return engine.ask(question);
    }).then(function (offline) {
      return onlinePass(question, history, offline);
    });
  }

  /**
   * 无后端时的「在线模式」：先本地检索，再把知识库结果交给大模型改写。
   *
   * 与 java/src/campus/Router.java 的 ask() 保持同一套判断：
   *   没开在线模式 → 原样返回离线答案
   *   auto 口径且高置信 → 原样返回离线答案
   *   知识库没覆盖且不允许通用常识 → 原样返回离线答案
   *   否则交给大模型；联网失败就回退离线答案并在 notice 里说明原因
   */
  function onlinePass(question, history, offline) {
    if (!onlineReady()) return offline;
    if (state.onlineMode !== 'always' && offline.confidence === 'high') return offline;

    var hasKb = !!offline.entryId;
    if (!hasKb && !state.kbFallback) return offline;
    if (!window.CampusLlm || typeof window.CampusLlm.chat !== 'function') return offline;

    return window.CampusLlm.chat({
      apiKey: state.apiKey,
      history: history,
      question: question,
      kbContext: hasKb ? buildKbContext(offline) : '',
      provider: state.provider.id,
      baseUrl: state.provider.baseUrl,
      model: state.provider.model
    }).then(function (r) {
      if (!r || !r.ok) {
        offline.notice = mergeNotice(offline.notice,
          '联网大模型暂时不可用（' + ((r && r.error) || '未知错误') + '），已回退为离线答案。');
        return offline;
      }
      return {
        mode: 'online',
        confidence: 'high',
        answer: r.content,
        entryId: offline.entryId || null,
        entryQuestion: offline.entryQuestion || null,
        category: offline.category || null,
        source: '联网大模型 · ' + (r.providerLabel || state.provider.label)
          + (r.model || state.provider.model ? ' · ' + (r.model || state.provider.model) : ''),
        verified: offline.verified == null ? null : offline.verified,
        candidates: offline.candidates || [],
        matchedTerms: offline.matchedTerms || [],
        notice: hasKb
          ? '本回答由联网大模型改写自知识库原文，事实以知识库为准。'
          : '知识库没有收录这个问题，以下内容由联网大模型用通用知识作答，仅供参考。',
        engine: {
          impl: 'js',
          model: r.model || state.provider.model,
          provider: r.provider || state.provider.id,
          providerLabel: r.providerLabel || state.provider.label,
          retrievalConfidence: offline.confidence,
          kbHit: hasKb
        }
      };
    });
  }

  function mergeNotice(existing, extra) {
    var base = typeof existing === 'string' ? existing.trim() : '';
    return base ? base + ' ' + extra : extra;
  }

  /* ------------------------------------------------------------------
     消息渲染
     ------------------------------------------------------------------ */
  function hideEmpty() {
    el.empty.classList.add('hidden');
  }

  function showEmpty() {
    el.empty.classList.remove('hidden');
  }

  function scrollBottom(force) {
    var s = el.stream;
    if (!s) return;
    var nearBottom = s.scrollHeight - s.scrollTop - s.clientHeight < 140;
    if (force || nearBottom) s.scrollTop = s.scrollHeight;
  }

  function addUserMessage(text) {
    var node = elFromHTML(
      '<div class="msg msg-user">' +
        '<div class="bubble">' + esc(text).replace(/\n/g, '<br>') + '</div>' +
      '</div>'
    );
    el.messages.appendChild(node);
    scrollBottom(true);
  }

  function addAssistantShell() {
    var node = elFromHTML(
      '<div class="msg msg-assistant">' +
        '<span class="avatar" aria-hidden="true">' + ICON.cap + '</span>' +
        '<div class="bubble"><div class="body"></div></div>' +
      '</div>'
    );
    el.messages.appendChild(node);
    scrollBottom(true);
    return { root: node, body: node.querySelector('.body'), bubble: node.querySelector('.bubble') };
  }

  function showLoading(holder) {
    holder.body.innerHTML =
      '<div class="loading"><span>正在检索…</span>' +
      '<span class="dots"><i></i><i></i><i></i></span></div>';
  }

  /* --- 打字机 --- */
  function typewriter(target, text, token) {
    return new Promise(function (resolve) {
      target.innerHTML = '';
      text = String(text == null ? '' : text);

      var segs = parseSegments(text);
      var nodes = segs.map(function (s) {
        var span = document.createElement('span');
        if (s.em) span.className = 'em';
        target.appendChild(span);
        return { span: span, chars: Array.from(s.t), i: 0 };
      });

      var cursor = document.createElement('span');
      cursor.className = 'cursor';
      target.appendChild(cursor);

      function finish() {
        target.innerHTML = renderRich(text);
        resolve();
      }

      if (prefersReducedMotion() || !text) { finish(); return; }

      var delay = text.length > 260 ? 3
                : text.length > 130 ? 6
                : text.length > 55 ? 10 : 14;

      var ni = 0, tick = 0;
      function step() {
        if (token.cancelled) { finish(); return; }
        while (ni < nodes.length && nodes[ni].i >= nodes[ni].chars.length) ni++;
        if (ni >= nodes.length) { finish(); return; }
        var n = nodes[ni];
        n.span.textContent += n.chars[n.i++];

        // 每 4 帧滚动一次，避免频繁回流
        if (++tick % 4 === 0) scrollBottom(false);
        setTimeout(step, delay);
      }
      step();
    });
  }

  function chipHTML(text, kind, iconHTML) {
    return '<span class="chip chip-' + kind + '">' + (iconHTML || '') + esc(text) + '</span>';
  }

  function confLabel(c) {
    return { high: '高置信', mid: '中等置信', low: '低置信' }[c] || '置信度未知';
  }

  /* --- 来源卡片 --- */
  function buildSourceCard(result) {
    var verified = result.verified;
    var unverified = verified === false;
    var isOnline = result.mode === 'online';

    var chips = '';
    if (result.category) chips += chipHTML(result.category, 'cat');
    if (result.confidence) chips += chipHTML(confLabel(result.confidence), 'conf-' + result.confidence);
    if (isOnline) chips += chipHTML('联网生成', 'online');
    if (unverified) chips += chipHTML('模拟示例数据', 'warn', ICON.warn);
    else if (verified === true) chips += chipHTML('已核实', 'verified', ICON.check);

    var source = result.source;
    if (!source && isOnline) source = '联网大模型 · ' + modelLabel();
    if (!source) source = '—';

    var extra = unverified
      ? '（该条目为模拟示例数据，仅用于演示检索效果，上线前须替换为校方真实信息）'
      : '';

    return elFromHTML(
      '<div class="src-card' + (unverified ? ' unverified' : '') + '">' +
        '<div class="src-chips">' + chips + '</div>' +
        '<div class="src-body">' + ICON.book +
          '<span class="src-label">出处</span>' +
          '<span class="src-text">' + esc(source) + esc(extra) + '</span>' +
        '</div>' +
      '</div>'
    );
  }

  /* --- 检索详情（默认收起） --- */
  function buildTrace(result) {
    var terms = Array.isArray(result.matchedTerms) ? result.matchedTerms : [];
    var cands = Array.isArray(result.candidates) ? result.candidates : [];
    if (!terms.length && !cands.length) return null;

    var termsHTML = terms.length
      ? terms.map(function (t) { return '<span class="term">' + esc(t) + '</span>'; }).join('')
      : '<span class="term-empty">未命中任何查询词</span>';

    var candHTML = '';
    if (cands.length) {
      candHTML =
        '<div class="trace-row">' +
          '<span class="trace-key">候选</span>' +
          '<span class="trace-val"><span class="trace-cands">' +
            cands.slice(0, 3).map(function (c) {
              var cov = Math.round(Math.min(1, Math.max(0, Number(c.coverage) || 0)) * 100);
              return '<span class="trace-cand">' +
                '<span class="q">' + esc(c.question || c.id || '') + '</span>' +
                (c.category ? '<span class="chip chip-cat">' + esc(c.category) + '</span>' : '') +
                '<span class="bar"><i style="width:' + cov + '%"></i></span>' +
                '<span class="cov">' + cov + '%</span>' +
              '</span>';
            }).join('') +
          '</span></span>' +
        '</div>';
    }

    return elFromHTML(
      '<details class="trace">' +
        '<summary>' + ICON.chevron + '<span>检索详情</span></summary>' +
        '<div class="trace-body">' +
          '<div class="trace-row">' +
            '<span class="trace-key">命中词</span>' +
            '<span class="trace-val"><span class="terms">' + termsHTML + '</span></span>' +
          '</div>' +
          candHTML +
        '</div>' +
      '</details>'
    );
  }

  /* --- mid：你是不是想问 --- */
  function buildCandidates(result) {
    var cands = Array.isArray(result.candidates) ? result.candidates : [];
    if (!cands.length) return null;

    var node = elFromHTML(
      '<div class="candidates">' +
        '<div class="cand-title">' + ICON.help + '<span>你是不是想问：</span></div>' +
        '<div class="cand-list"></div>' +
      '</div>'
    );
    var list = node.querySelector('.cand-list');
    cands.slice(0, 3).forEach(function (c) {
      var q = c.question || '';
      if (!q) return;
      var btn = elFromHTML(
        '<button class="cand" type="button">' +
          '<span class="cand-q">' + esc(q) + '</span>' +
          (c.coverage != null ? '<span class="cand-cov">' + pct(c.coverage) + '</span>' : '') +
          '<span class="cand-arrow">' + ICON.arrow + '</span>' +
        '</button>'
      );
      btn.addEventListener('click', function () { submit(q); });
      list.appendChild(btn);
    });
    return node;
  }

  /* --- low：升级到联网大模型 --- */
  function buildUpgrade(question) {
    var node = elFromHTML(
      '<div class="upgrade">' +
        '<button class="btn-upgrade" type="button">' + ICON.sparkles + '<span>升级到联网大模型</span></button>' +
        '<p class="upgrade-hint">知识库没有覆盖到这个问题，可以把检索结果交给大模型继续作答。</p>' +
      '</div>'
    );
    node.querySelector('.btn-upgrade').addEventListener('click', function () {
      upgradeToOnline(question, node);
    });
    return node;
  }

  /* --- 组装一条完整回答 --- */
  function paintAnswer(holder, question, result) {
    var text = result && result.answer ? String(result.answer) : '（引擎未返回内容）';
    var token = { cancelled: false };
    state.typingToken = token;

    var answerEl = document.createElement('div');
    answerEl.className = 'answer';
    holder.body.innerHTML = '';
    holder.body.appendChild(answerEl);

    return typewriter(answerEl, text, token).then(function () {
      state.typingToken = null;

      // 来源卡片
      holder.body.appendChild(buildSourceCard(result));

      // notice 提示
      if (result.notice) {
        holder.body.appendChild(elFromHTML(
          '<div class="notice">' + ICON.info + '<span>' + esc(result.notice) + '</span></div>'
        ));
      }

      // 检索详情（折叠）
      var trace = buildTrace(result);
      if (trace) holder.body.appendChild(trace);

      // 中等置信 → 候选追问
      if (result.confidence === 'mid') {
        var cands = buildCandidates(result);
        if (cands) holder.body.appendChild(cands);
      }

      // 低置信 → 升级引导
      if (result.confidence === 'low') {
        holder.body.appendChild(buildUpgrade(question));
      }

      state.lastResult = result;
      state.history.push({ role: 'assistant', content: text });
      scrollBottom(true);
    });
  }

  /* --- 错误气泡 --- */
  function describeError(err) {
    var code = err && err.code;
    if (code === 'engine') {
      if (state.engineError === 'missing') {
        return {
          title: '检索引擎未就绪',
          body: '页面没有加载到 <code>assets/engine-js.js</code>，也没有检测到本地后端服务。' +
                '请通过 HTTP 服务访问本页（例如在 docs 目录运行 <code>python -m http.server</code>），' +
                '并确认检索引擎文件已生成。'
        };
      }
      return {
        title: '检索引擎启动失败',
        body: '引擎初始化时出错了，通常是知识库数据读取失败。请确认 <code>docs/data/</code> 下的 JSON 文件可通过当前方式访问。'
      };
    }
    if (code === 'backend_http') {
      return {
        title: '后端服务返回错误',
        body: '请求 <code>/api/ask</code> 失败（HTTP ' + esc(err.status) + '）。请确认 Java 服务仍在运行，稍后重试。'
      };
    }
    if (code === 'backend_badjson') {
      return { title: '后端返回格式异常', body: '服务返回的内容不是合法 JSON，可能是接口异常。' };
    }
    return {
      title: '出了点问题',
      body: '请求没能完成：' + esc((err && err.message) || '未知错误') + '。请稍后重试。'
    };
  }

  function paintError(holder, err) {
    var d = describeError(err);
    holder.bubble.classList.add('bubble-error');
    holder.body.innerHTML =
      '<div class="err-title">' + ICON.warn + '<span>' + esc(d.title) + '</span></div>' +
      '<div class="err-body">' + d.body + '</div>' +
      '<div class="err-actions">' +
        '<button class="btn-sm" data-act="retry">重新提问</button>' +
        '<button class="btn-sm" data-act="panel">联网设置</button>' +
      '</div>';
    holder.body.querySelector('[data-act="retry"]').addEventListener('click', function () { submit(lastQuestion); });
    holder.body.querySelector('[data-act="panel"]').addEventListener('click', openPanel);
    scrollBottom(true);
  }

  /* ------------------------------------------------------------------
     提交
     ------------------------------------------------------------------ */
  var lastQuestion = '';

  function submit(rawQuestion) {
    var question = String(rawQuestion || '').trim();
    if (!question || state.busy) return;

    lastQuestion = question;
    hideEmpty();

    addUserMessage(question);
    var history = state.history.slice(-HISTORY_LIMIT);
    state.history.push({ role: 'user', content: question });

    state.busy = true;
    el.composer.classList.add('busy');
    el.sendBtn.disabled = true;

    var holder = addAssistantShell();
    showLoading(holder);
    scrollBottom(true);

    route(question, history)
      .then(function (result) {
        if (!result || typeof result !== 'object') {
          var e = new Error('响应为空'); e.code = 'empty'; throw e;
        }
        return paintAnswer(holder, question, result);
      })
      .catch(function (err) {
        console.error('[小助手] 提问失败：', err);
        state.history.pop(); // 回滚这条用户提问
        paintError(holder, err);
      })
      .then(function () {
        state.busy = false;
        el.composer.classList.remove('busy');
        el.sendBtn.disabled = false;
      });
  }

  /* ------------------------------------------------------------------
     联网大模型（无后端时使用 window.CampusLlm；有后端则走 /api/ask）
     ------------------------------------------------------------------ */
  function buildKbContext(result) {
    if (!result) return '';
    var ids = [];
    if (result.entryId) ids.push(result.entryId);
    (result.candidates || []).forEach(function (c) {
      if (c && c.id && ids.indexOf(c.id) === -1) ids.push(c.id);
    });
    var picked = ids.slice(0, 3).map(function (id) { return state.entriesById[id]; })
                    .filter(Boolean);
    if (!picked.length) return '';
    return picked.map(function (e) {
      return '【' + (e.question || '') + '】\n' + (e.answer || '');
    }).join('\n\n');
  }

  /* 组装送给模型的「之前几轮」，剔除末尾那条本次提问 */
  function lastHistory(question) {
    var h = state.history.slice();
    if (h.length && h[h.length - 1].role === 'user' && h[h.length - 1].content === question) h.pop();
    return h.slice(-HISTORY_LIMIT);
  }

  function llmOpts() {
    return {
      provider: state.provider ? state.provider.id : '',
      baseUrl: state.provider ? state.provider.baseUrl : '',
      model: state.provider ? state.provider.model : ''
    };
  }

  function upgradeToOnline(question, upgradeNode) {
    if (!state.apiKey) {
      openPanel('填写 API Key 后即可启用联网大模型。');
      return;
    }
    if (state.forceOffline) {
      openPanel('「强制离线」开关正开着，先关掉它再试。');
      return;
    }
    if (state.busy) return;

    var btn = upgradeNode.querySelector('.btn-upgrade');
    if (btn) { btn.disabled = true; }

    state.busy = true;
    el.composer.classList.add('busy');
    el.sendBtn.disabled = true;

    var holder = addAssistantShell();
    showLoading(holder);
    scrollBottom(true);

    var history = lastHistory(question);

    var run;
    if (state.backend) {
      // 有后端：把 Key 与服务商一起发过去，由后端调用大模型
      run = askBackend(question, history);
    } else if (window.CampusLlm && typeof window.CampusLlm.chat === 'function') {
      // 无后端：浏览器直连
      var prior = state.lastResult;
      var ensure = (prior && (prior.entryId || (prior.candidates || []).length))
        ? Promise.resolve(prior)
        : ensureEngine().then(function (engine) {
            if (!engine) { var e = new Error('ENGINE_NOT_READY'); e.code = 'engine'; throw e; }
            return engine.ask(question);
          });

      run = ensure.then(function (ctxResult) {
        var hasKb = !!ctxResult.entryId;
        return window.CampusLlm.chat({
          apiKey: state.apiKey,
          history: history,
          question: question,
          kbContext: hasKb ? buildKbContext(ctxResult) : '',
          provider: llmOpts().provider,
          baseUrl: llmOpts().baseUrl,
          model: llmOpts().model
        }).then(function (r) {
          if (!r || !r.ok) {
            var e = new Error((r && r.error) || '大模型调用失败');
            e.code = 'llm';
            throw e;
          }
          return {
            mode: 'online',
            confidence: 'high',
            answer: r.content,
            entryId: ctxResult.entryId || null,
            entryQuestion: ctxResult.entryQuestion || null,
            category: ctxResult.category || null,
            source: '联网大模型 · ' + (r.providerLabel || modelLabel())
              + (r.model ? ' · ' + r.model : ''),
            verified: ctxResult.verified == null ? null : ctxResult.verified,
            candidates: ctxResult.candidates || [],
            matchedTerms: ctxResult.matchedTerms || [],
            notice: hasKb
              ? '本回答由联网大模型改写自知识库原文，事实以知识库为准。'
              : '知识库没有收录这个问题，以下内容由联网大模型用通用知识作答，仅供参考。'
          };
        });
      });
    } else {
      run = Promise.reject(Object.assign(new Error('LLM 模块未加载'), { code: 'engine' }));
      state.engineError = state.engineError || 'missing';
    }

    run
      .then(function (result) {
        if (!result || typeof result !== 'object') { var e = new Error('响应为空'); e.code = 'empty'; throw e; }
        return paintAnswer(holder, question, result);
      })
      .catch(function (err) {
        console.error('[小助手] 联网升级失败：', err);
        paintError(holder, err);
      })
      .then(function () {
        state.busy = false;
        el.composer.classList.remove('busy');
        el.sendBtn.disabled = false;
        if (btn) btn.disabled = false;
      });
  }

  /* 测试密钥：优先在浏览器直连做服务商自动匹配，取不到再退回后端实测 */
  function testKey(key) {
    if (window.CampusLlm && typeof window.CampusLlm.match === 'function') {
      return window.CampusLlm.match(key, providerOpts());
    }
    if (state.backend) {
      return askBackend('连接测试', []).then(function (r) {
        var online = r && r.mode === 'online';
        var meta = (r && r.engine) || {};
        return {
          status: online ? 'ok' : 'invalid_key',
          reachable: true,
          valid: online,
          model: meta.model || state.llmModel || '',
          provider: meta.provider || '',
          providerLabel: meta.providerLabel || '',
          via: 'backend',
          sample: r
        };
      });
    }
    return Promise.resolve({ status: 'unreachable', reachable: false, valid: false, model: '', via: 'none' });
  }

  function probeMessage(r) {
    if (r.status === 'ok') {
      return { cls: 'ok', html: '<b>匹配成功</b> · 服务商 <code>' + esc(r.providerLabel || modelLabel())
        + '</code>，模型 <code>' + esc(r.model || '默认') + '</code>，已切换为在线模式。' };
    }
    if (r.status === 'invalid_key') {
      return { cls: 'bad', html: '<b>密钥无效</b> · 没有服务商接受这把 Key。请检查是否填错、已过期或余额不足；'
        + '也可以在上面的「服务商」里手动指定后再试。' + detailSuffix(r) };
    }
    if (r.status === 'unreachable') {
      return { cls: 'bad', html: '<b>网络不可用</b> · 无法连接到大模型服务，请检查网络后重试；'
        + '若是某家境外服务被墙，可在「服务商」里手动指定国内服务商。' + detailSuffix(r) };
    }
    return { cls: 'bad', html: '<b>未配置密钥</b> · 请先填写 API Key。' };
  }

  /* 把失败的具体原因（HTTP 码 / 超时 / 异常名）附在提示后面，便于排查 */
  function detailSuffix(r) {
    var d = r && r.detail ? String(r.detail).trim() : '';
    return d ? '<br><span class="probe-detail">具体原因：' + esc(d) + '</span>' : '';
  }

  function runProbe() {
    var key = el.keyInput.value.trim();
    state.apiKey = key;
    persistKey(key);

    if (!key) {
      setLlmStatus('no_key');
      el.probe.hidden = false;
      el.probe.className = 'probe bad';
      el.probe.innerHTML = '<b>未填写密钥</b> · 已保持离线模式。';
      return;
    }

    setLlmStatus('probing');
    el.probe.hidden = false;
    el.probe.className = 'probe';
    el.probe.textContent = '正在匹配服务商…';
    el.keyTest.disabled = true;

    testKey(key).then(function (r) {
      var provider = r && r.provider
        ? { id: r.provider, label: r.providerLabel || r.provider, baseUrl: r.baseUrl || '', model: r.model || '' }
        : null;
      if (provider) persistProvider(provider);
      setLlmStatus(r.status, r.model, provider);
      var m = probeMessage(r);
      el.probe.className = 'probe ' + m.cls;
      el.probe.innerHTML = m.html;
    }).catch(function (err) {
      console.error('[小助手] 测试连接失败：', err);
      setLlmStatus('unreachable');
      el.probe.className = 'probe bad';
      el.probe.innerHTML = '<b>测试失败</b> · ' + esc((err && err.message) || '未知错误');
    }).then(function () {
      el.keyTest.disabled = false;
    });
  }

  /* ------------------------------------------------------------------
     面板 / 键盘 / 输入
     ------------------------------------------------------------------ */
  function openPanel(note) {
    el.keyInput.value = state.apiKey;
    el.panel.classList.add('open');
    el.scrim.classList.add('open');
    el.panel.setAttribute('aria-hidden', 'false');
    syncProviderUI();
    syncPanelStatus();
    if (note) {
      el.probe.hidden = false;
      el.probe.className = 'probe bad';
      el.probe.textContent = note;
    }
    setTimeout(function () { el.keyInput.focus(); }, 260);
  }

  function closePanel() {
    el.panel.classList.remove('open');
    el.scrim.classList.remove('open');
    el.panel.setAttribute('aria-hidden', 'true');
  }

  function persistKey(key) {
    try {
      if (key) localStorage.setItem(LS_KEY, key);
      else localStorage.removeItem(LS_KEY);
    } catch (e) { /* ignore */ }
  }

  function persistForceOffline(on) {
    try { localStorage.setItem(LS_FORCE_OFFLINE, on ? '1' : '0'); } catch (e) { /* ignore */ }
  }

  function persistProviderSel(id) {
    try {
      if (id) localStorage.setItem(LS_PROVIDER_SEL, id);
      else localStorage.removeItem(LS_PROVIDER_SEL);
    } catch (e) { /* ignore */ }
  }

  /* 把匹配结果（服务商 id/显示名/地址/模型）统一成界面用的结构 */
  function providerOf(r) {
    if (!r || !r.provider) return null;
    return {
      id: r.provider,
      label: r.providerLabel || r.provider,
      baseUrl: r.baseUrl || '',
      model: r.model || ''
    };
  }

  /* 服务商下拉：选项来自 data/config.json，不在界面里硬编码服务商清单 */
  function fillProviderOptions() {
    if (!window.CampusLlm || typeof window.CampusLlm.providers !== 'function') return;
    window.CampusLlm.providers().then(function (list) {
      if (!list || !list.length) return;
      var html = '<option value="">自动匹配</option>';
      list.forEach(function (p) {
        html += '<option value="' + esc(p.id) + '">' + esc(p.label) + '</option>';
      });
      el.providerSel.innerHTML = html;
      el.providerSel.value = state.providerSel;
    }).catch(function () { /* 读不到就只留「自动匹配」 */ });
  }

  function syncProviderUI() {
    el.providerSel.value = state.providerSel;
    el.customRow.hidden = state.providerSel !== 'custom';
    el.forceOffline.checked = state.forceOffline;
  }

  /* 在线模式的行为开关（always / auto、是否允许通用常识兜底）来自 config.json */
  function loadModeConfig() {
    return fetch('data/config.json', { cache: 'no-store' })
      .then(function (res) { return res.ok ? res.json() : null; })
      .then(function (c) {
        var mode = (c && c.llm && c.llm.mode) || {};
        if (mode.onlineAsk) state.onlineMode = String(mode.onlineAsk) === 'auto' ? 'auto' : 'always';
        if (mode.kbFallbackToModel === false) state.kbFallback = false;
        return c;
      })
      .catch(function () { return null; });
  }

  function autoGrow() {
    el.input.style.height = 'auto';
    el.input.style.height = Math.min(el.input.scrollHeight, 168) + 'px';
  }

  function resetComposer() {
    el.input.value = '';
    autoGrow();
    el.input.focus();
  }

  function clearConversation() {
    if (state.typingToken) { state.typingToken.cancelled = true; state.typingToken = null; }
    state.history = [];
    state.lastResult = null;
    state.busy = false;
    el.composer.classList.remove('busy');
    el.sendBtn.disabled = false;
    el.messages.innerHTML = '';
    showEmpty();
    el.stream.scrollTop = 0;
    resetComposer();
  }

  /* ------------------------------------------------------------------
     事件绑定
     ------------------------------------------------------------------ */
  function bindUI() {
    // 快捷提问（事件委托，重绘后依然有效）
    el.quickList.addEventListener('click', function (ev) {
      var btn = ev.target.closest('.quick');
      if (!btn) return;
      submit(btn.getAttribute('data-q'));
    });

    // 提交
    el.composer.addEventListener('submit', function (ev) {
      ev.preventDefault();
      var q = el.input.value.trim();
      if (!q || state.busy) return;
      resetComposer();
      submit(q);
    });

    el.input.addEventListener('input', autoGrow);
    el.input.addEventListener('keydown', function (ev) {
      if (ev.key === 'Enter' && !ev.shiftKey && !ev.isComposing) {
        ev.preventDefault();
        el.composer.requestSubmit();
      }
    });

    // 顶部按钮
    el.clearBtn.addEventListener('click', clearConversation);
    el.badge.addEventListener('click', function () { openPanel(); });
    el.bannerClose.addEventListener('click', function () { el.banner.hidden = true; });

    // 面板
    el.panelClose.addEventListener('click', closePanel);
    el.scrim.addEventListener('click', closePanel);
    el.keyReveal.addEventListener('click', function () {
      var revealed = el.keyInput.type === 'text';
      el.keyInput.type = revealed ? 'password' : 'text';
      el.keyReveal.classList.toggle('revealed', !revealed);
    });
    el.keyInput.addEventListener('change', function () {
      state.apiKey = el.keyInput.value.trim();
      persistKey(state.apiKey);
    });
    el.keyTest.addEventListener('click', runProbe);
    el.keyClear.addEventListener('click', function () {
      el.keyInput.value = '';
      state.apiKey = '';
      persistKey('');
      el.keyInput.type = 'password';
      el.keyReveal.classList.remove('revealed');
      setLlmStatus('no_key', '', null);
      el.probe.hidden = false;
      el.probe.className = 'probe';
      el.probe.textContent = '已清除本机保存的 API Key，恢复离线模式。';
    });

    // 服务商：选「自动匹配」交给探测，选具体一家就只发给那一家
    el.providerSel.addEventListener('change', function () {
      state.providerSel = el.providerSel.value;
      persistProviderSel(state.providerSel);
      syncProviderUI();
      if (!state.apiKey) { syncPanelStatus(); return; }
      runProbe();
    });
    el.customBase.addEventListener('change', function () {
      try { localStorage.setItem(LS_CUSTOM_BASE, el.customBase.value.trim()); } catch (e) { /* ignore */ }
      runProbe();
    });
    el.customModel.addEventListener('change', function () {
      try { localStorage.setItem(LS_CUSTOM_MODEL, el.customModel.value.trim()); } catch (e) { /* ignore */ }
      runProbe();
    });

    // 强制离线：不改变 Key 与服务商的匹配结果，只切换「本次是否联网」
    el.forceOffline.addEventListener('change', function () {
      state.forceOffline = el.forceOffline.checked;
      persistForceOffline(state.forceOffline);
      syncProviderUI();
      setLlmStatus(state.llmStatus);
    });

    // Esc 关闭面板
    document.addEventListener('keydown', function (ev) {
      if (ev.key === 'Escape') closePanel();
    });
  }

  /* ------------------------------------------------------------------
     启动
     ------------------------------------------------------------------ */
  function boot() {
    initTheme();
    bindUI();
    renderQuickPrompts();   // 先用兜底列表占位，知识库读到后再刷新
    syncComposerHint();
    setLlmStatus('no_key');

    try {
      state.apiKey = localStorage.getItem(LS_KEY) || '';
      state.providerSel = localStorage.getItem(LS_PROVIDER_SEL) || '';
      state.forceOffline = localStorage.getItem(LS_FORCE_OFFLINE) === '1';
      el.customBase.value = localStorage.getItem(LS_CUSTOM_BASE) || '';
      el.customModel.value = localStorage.getItem(LS_CUSTOM_MODEL) || '';
    } catch (e) { state.apiKey = ''; }
    el.keyInput.value = state.apiKey;

    fillProviderOptions();
    syncProviderUI();

    Promise.all([probeBackend(), loadKb(), loadModeConfig()]).then(function (res) {
      var health = res[0];
      var kb = res[1];

      if (kb) { indexKb(kb); renderMeta(); renderQuickPrompts(); }

      if (health) state.backend = health;

      if (state.apiKey && window.CampusLlm && typeof window.CampusLlm.match === 'function') {
        // 本机存了 Key：静默自动匹配一次，让徽章如实反映是否真的处于在线模式
        setLlmStatus('probing');
        testKey(state.apiKey).then(function (r) {
          setLlmStatus(r.status, r.model, providerOf(r));
        }).catch(function () { setLlmStatus('unreachable'); });
      } else if (health) {
        // 没有本机 Key 时，徽章反映服务端配置的 Key
        var llm = health.llm || {};
        setLlmStatus(llm.status || 'no_key', llm.model || '', providerOf(llm));
      } else {
        setLlmStatus('no_key');
      }

      reportEnvironment(kb);
    });
  }

  /* 依据环境给出友好提示（绝不弹 alert） */
  function reportEnvironment(kb) {
    var isFile = location.protocol === 'file:';

    if (isFile) {
      showBanner(
        '<b>当前以 file:// 直接打开。</b>浏览器禁止页面读取本地 JSON 数据文件，' +
        '因此检索与联网暂不可用。请改用 HTTP 服务访问 ' +
        '<code>docs</code> 目录（例如 <code>python -m http.server 8000</code>）后再试。界面本身可正常预览。'
      );
      return;
    }
    if (!kb) {
      showBanner('<b>知识库数据读取失败。</b>未能加载 <code>data/kb.json</code>，请确认该文件存在且可访问。');
      return;
    }
    if (!state.backend && !(window.CampusEngine && typeof window.CampusEngine.create === 'function')) {
      showBanner('<b>检索引擎未就绪。</b>未检测到 <code>assets/engine-js.js</code>，且没有本地后端服务，暂时无法作答。');
    }
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot);
  } else {
    boot();
  }
})();
