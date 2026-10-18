/**
 * 校园智能体「小助手」—— 浏览器直连大模型（无后端时的联网通道）
 *
 * 部署到 GitHub Pages 这类纯静态托管时，没有 Java 后端可调用，
 * 但主流大模型服务的接口都开放了 CORS（实测 DeepSeek 的 OPTIONS 预检会回显 Origin），
 * 所以浏览器可以直接发请求。密钥只存在使用者自己的 localStorage 里，
 * 由使用者在界面上填写 —— 公开站点上绝不放任何公共 Key。
 *
 * 参数一律从 data/config.json 的 llm 段读取，和 Java 侧（Llm.java）保持同一份配置，
 * 包括提示词文案：两边必须逐字一致，否则「有后端」和「没后端」的答案风格会跑偏。
 *
 * 对外接口：
 *   window.CampusLlm.providers()                    -> [{id,label,baseUrl,model}]
 *   window.CampusLlm.match(apiKey, opts)            -> Promise<{status,reachable,valid,model,provider,providerLabel,detail}>
 *   window.CampusLlm.chat({apiKey,history,question,kbContext,provider}) -> Promise<{ok,content,error,model,provider}>
 *
 *   opts = { provider: 'deepseek' | 'moonshot' | ..., baseUrl, model }
 */
(function (global) {
  'use strict';

  var VERSION = '0.2.0';
  var MATCH_TTL_MS = 120000;

  var cfgPromise = null;
  var schoolPromise = null;
  var matchCache = null;   // {sig, at, result}

  /* ---------------------------------------------------------------- 配置 */

  function loadConfig() {
    if (cfgPromise) return cfgPromise;
    cfgPromise = fetch('data/config.json', { cache: 'no-store' })
      .then(function (res) {
        if (!res.ok) throw new Error('HTTP ' + res.status);
        return res.json();
      })
      .then(function (c) { return (c && c.llm) || {}; })
      .catch(function () { return {}; });
    return cfgPromise;
  }

  /** 学校名用于拼系统提示词，让模型的自我认知和知识库一致。 */
  function loadSchool() {
    if (schoolPromise) return schoolPromise;
    schoolPromise = fetch('data/kb.json', { cache: 'no-store' })
      .then(function (res) {
        if (!res.ok) throw new Error('HTTP ' + res.status);
        return res.json();
      })
      .then(function (kb) { return (kb && kb.meta && kb.meta.school) || '本校'; })
      .catch(function () { return '本校'; });
    return schoolPromise;
  }

  function providersOf(c) {
    var raw = (c && c.providers) || [];
    var out = [];
    for (var i = 0; i < raw.length; i++) {
      var p = raw[i] || {};
      if (!p.id) continue;
      out.push({
        id: String(p.id),
        label: String(p.label || p.id),
        baseUrl: baseOf({ baseUrl: p.baseUrl }),
        model: String(p.model || ''),
        modelHints: Array.isArray(p.modelHints) ? p.modelHints.map(String) : []
      });
    }
    return out;
  }

  function findProvider(list, id) {
    for (var i = 0; i < list.length; i++) if (list[i].id === id) return list[i];
    return null;
  }

  function baseOf(c) {
    var b = String((c && c.baseUrl) || '').trim();
    while (b.length > 1 && b.charAt(b.length - 1) === '/') b = b.slice(0, -1);
    return b;
  }

  /**
   * 候选顺序：手动选定 > 默认服务商 > 配置里的其余内置服务商。
   * 与 java/src/campus/Llm.java 的 candidateQueue 保持一致：custom 不参与自动兜底。
   */
  function candidateQueue(c, preferredId, customBase, customModel) {
    var all = providersOf(c);
    var queue = [];
    var pref = String(preferredId || '').trim();
    if (pref) {
      var p = findProvider(all, pref);
      if (p) queue.push(withOverride(p, customBase, customModel));
    }
    if (!queue.length) {
      var d = findProvider(all, String(c.defaultProvider || 'deepseek')) || firstNonCustom(all);
      if (d) queue.push(d);
    }
    for (var i = 0; i < all.length; i++) {
      if (all[i].id === 'custom') continue;
      var seen = false;
      for (var j = 0; j < queue.length; j++) if (queue[j].id === all[i].id) { seen = true; break; }
      if (!seen) queue.push(all[i]);
    }
    return queue;
  }

  function withOverride(p, newBase, newModel) {
    var b = String(newBase == null ? '' : newBase).trim();
    var m = String(newModel == null ? '' : newModel).trim();
    return {
      id: p.id,
      label: p.label,
      baseUrl: b ? baseOf({ baseUrl: b }) : p.baseUrl,
      model: m || p.model,
      modelHints: p.modelHints
    };
  }

  function firstNonCustom(all) {
    for (var i = 0; i < all.length; i++) if (all[i].id !== 'custom') return all[i];
    return null;
  }

  /* ---------------------------------------------------------------- 提示词 */

  /**
   * 知识库命中时的提示词：只允许改写措辞，不允许动事实。
   * 与 java/src/campus/Router.java 的 systemWithKb 逐字一致。
   */
  function systemWithKb(school) {
    return '你叫「小助手」，是' + school + '的校园问答助手，服务对象是学生和家长。\n\n'
      + '用户消息里给了【知识库参考】，它是这次回答的唯一事实来源。\n'
      + '1. 只能用参考里的内容作答，可以改写、归纳、口语化，但不得添加参考之外的学校信息。\n'
      + '2. 不得改动参考中的数字、时间、金额、名称、电话、地点。\n'
      + '3. 参考内容不足以回答时，直接说「知识库里没有这部分记录」。\n'
      + '4. 不超过 180 字，不要用 Markdown 标题，需要分点时用简短序号。\n'
      + '5. 语气友好、干脆，像一个熟悉校园的学长学姐。';
  }

  /**
   * 知识库没有覆盖时的提示词：允许用通用知识作答，但必须声明来源、不许编造校内信息。
   * 与 java/src/campus/Router.java 的 systemNoKb 逐字一致。
   */
  function systemNoKb(school) {
    return '你叫「小助手」，是' + school + '的校园问答助手，服务对象是学生和家长。\n\n'
      + '这个问题校园知识库里没有记录，请你用通用知识回答，注意：\n'
      + '1. 开头先说明「这一点知识库里没有记录，以下是通用建议」。\n'
      + '2. 不要编造学校未公开的具体信息（电话、时间、金额、人数等），拿不准就直接说不知道。\n'
      + '3. 不超过 180 字，不要用 Markdown 标题，需要分点时用简短序号。\n'
      + '4. 语气友好、干脆，像一个熟悉校园的学长学姐。';
  }

  /* ---------------------------------------------------------------- 工具 */

  function pickModel(body, hints, fallback) {
    try {
      var data = body && body.data;
      if (Array.isArray(data) && data.length) {
        var ids = [];
        for (var i = 0; i < data.length; i++) {
          var id = data[i] && data[i].id;
          if (id) ids.push(String(id));
        }
        for (var h = 0; h < (hints || []).length; h++) {
          for (var k = 0; k < ids.length; k++) {
            if (ids[k].indexOf(hints[h]) >= 0) return ids[k];
          }
        }
        if (ids.length) return ids[0];
      }
    } catch (e) { /* 模型名只用于显示与调用，取不到就用配置里的默认值 */ }
    return fallback || '';
  }

  function errorMessage(body) {
    try {
      if (body && body.error && body.error.message) return String(body.error.message);
      if (typeof body === 'string' && body) return body.slice(0, 160);
    } catch (e) { /* ignore */ }
    return '';
  }

  function fetchWithTimeout(url, init, ms) {
    var ctrl = typeof AbortController === 'function' ? new AbortController() : null;
    var timer = null;
    if (ctrl) {
      init.signal = ctrl.signal;
      timer = setTimeout(function () { ctrl.abort(); }, ms);
    }
    function done() { if (timer) clearTimeout(timer); }
    return fetch(url, init).then(
      function (res) { done(); return res; },
      function (err) { done(); throw err; }
    );
  }

  function isTimeout(err) {
    return err && (err.name === 'AbortError' || /abort/i.test(String(err.message || '')));
  }

  function sigOf(key, o) {
    return key.length + ':' + key.slice(-8) + '|' + String(o.provider || '') + '|'
      + String(o.baseUrl || '') + '|' + String(o.model || '');
  }

  /* ---------------------------------------------------------------- 自动匹配 */

  /**
   * 自动匹配「这把 Key 属于哪家服务商」。
   *
   * Key 前缀（sk-）在 DeepSeek / Kimi / 通义 / OpenAI 之间高度重叠，光看前缀判断不出来，
   * 所以真正的判据是「拿 Key 去候选服务商的 /models 探一次，谁返回 200 就是谁」。
   * 串行尝试、第一个成功即锁定并缓存，避免把 Key 散发给列表里所有服务商。
   */
  function match(apiKey, opts) {
    var o = opts || {};
    if (!apiKey || !String(apiKey).trim()) {
      return Promise.resolve(empty('no_key', '未配置密钥'));
    }
    var key = String(apiKey).trim();
    var sig = sigOf(key, o);
    if (matchCache && matchCache.sig === sig && Date.now() - matchCache.at < MATCH_TTL_MS) {
      return Promise.resolve(matchCache.result);
    }

    return loadConfig().then(function (c) {
      var queue = candidateQueue(c, o.provider, o.baseUrl, o.model);
      if (!queue.length) {
        return remember(sig, empty('no_provider', 'config.json 里没有可用的服务商配置'));
      }
      var sawInvalid = false;
      var lastDetail = '';

      function attempt(i) {
        if (i >= queue.length) {
          return Promise.resolve({
            status: sawInvalid ? 'invalid_key' : 'unreachable',
            reachable: sawInvalid,
            valid: false,
            model: '',
            provider: '',
            providerLabel: '',
            detail: lastDetail || (sawInvalid ? '试过的服务商都拒绝了这个密钥' : '无法连接到大模型服务')
          });
        }
        var p = queue[i];
        if (!p.baseUrl) return attempt(i + 1);

        return probeOnce(c, p, key).then(function (r) {
          if (r.status === 'ok') return r;
          if (r.status === 'invalid_key') {
            sawInvalid = true;
            lastDetail = p.label + ' 拒绝了这把 Key（HTTP ' + (r.detail || '401') + '）';
            return attempt(i + 1);
          }
          // 网络不通就不用再试其它服务商了——它们同样连不上，
          // 继续试只会让界面白白多等好几个超时。
          return {
            status: 'unreachable', reachable: false, valid: false,
            model: '', provider: '', providerLabel: '',
            detail: p.label + '：' + (r.detail || '网络不可用')
          };
        });
      }

      return attempt(0).then(function (r) { return remember(sig, r); });
    });
  }

  function remember(sig, result) {
    matchCache = { sig: sig, at: Date.now(), result: result };
    return result;
  }

  function empty(status, detail) {
    return {
      status: status, reachable: false, valid: false,
      model: '', provider: '', providerLabel: '', detail: detail
    };
  }

  /** 拿 Key 去一个服务商的 /models 探一次。 */
  function probeOnce(c, p, key) {
    var url = p.baseUrl + (c.probePath || '/models');
    return fetchWithTimeout(url, {
      method: 'GET',
      headers: { 'Authorization': 'Bearer ' + key, 'Accept': 'application/json' }
    }, c.probeTimeoutMs || 2500)
      .then(function (res) {
        if (res.ok) {
          return res.json().catch(function () { return null; }).then(function (body) {
            var model = pickModel(body, p.modelHints, p.model);
            return {
              status: 'ok', reachable: true, valid: true,
              model: model,
              provider: p.id, providerLabel: p.label,
              baseUrl: p.baseUrl, modelHints: p.modelHints,
              detail: ''
            };
          });
        }
        if (res.status === 401 || res.status === 402 || res.status === 403) {
          return {
            status: 'invalid_key', reachable: true, valid: false,
            model: '', provider: '', providerLabel: '', detail: 'HTTP ' + res.status
          };
        }
        return {
          status: 'unreachable', reachable: false, valid: false,
          model: '', provider: '', providerLabel: '', detail: 'HTTP ' + res.status
        };
      })
      .catch(function (err) {
        return {
          status: 'unreachable', reachable: false, valid: false,
          model: '', provider: '', providerLabel: '',
          detail: isTimeout(err) ? '请求超时' : String((err && err.message) || err)
        };
      });
  }

  /* ---------------------------------------------------------------- 对话 */

  /**
   * 一次对话补全。
   *
   * @param o {apiKey, history, question, kbContext, provider}
   *   history  —— 之前的对话 [{role,content}]，**不含**本次提问
   *   question —— 本次提问
   *   kbContext—— 命中知识库时拼好的参考文本；为空表示知识库没覆盖
   *   provider —— 已匹配到的服务商 id（省略时会先自动匹配）
   */
  function chat(o) {
    o = o || {};
    if (!o.apiKey || !String(o.apiKey).trim()) {
      return Promise.resolve({ ok: false, error: '未配置密钥' });
    }
    var key = String(o.apiKey).trim();

    return Promise.all([loadConfig(), loadSchool()]).then(function (both) {
      var c = both[0];
      var school = both[1];

      var ready;
      if (o.provider) {
        var list = providersOf(c);
        var p = findProvider(list, o.provider) || withOverride(
          { id: o.provider, label: o.provider, baseUrl: '', model: '', modelHints: [] },
          o.baseUrl, o.model);
        ready = p.baseUrl
          ? Promise.resolve(p)
          : match(key, { provider: o.provider, baseUrl: o.baseUrl, model: o.model })
              .then(function (r) { return r.valid ? toProvider(r) : null; });
      } else {
        ready = match(key, { baseUrl: o.baseUrl, model: o.model })
          .then(function (r) { return r.valid ? toProvider(r) : null; });
      }

      return ready.then(function (provider) {
        if (!provider) return { ok: false, error: '没有可用的联网服务商' };
        if (!provider.baseUrl) return { ok: false, error: '没有可用的服务商地址' };

        var kb = String(o.kbContext || '');
        var messages = [{ role: 'system', content: kb ? systemWithKb(school) : systemNoKb(school) }];

        var turns = Number(c.historyTurns) > 0 ? Number(c.historyTurns) : 6;
        var history = Array.isArray(o.history) ? o.history.slice(-turns * 2) : [];
        for (var i = 0; i < history.length; i++) {
          var m = history[i];
          if (!m || !m.content) continue;
          if (m.role !== 'user' && m.role !== 'assistant') continue;
          messages.push({ role: m.role, content: String(m.content) });
        }
        var q = String(o.question == null ? '' : o.question);
        if (!q) return { ok: false, error: '没有可发送的内容' };
        messages.push({ role: 'user', content: kb ? q + '\n\n【知识库参考】\n' + kb : q });

        var payload = {
          model: provider.model,
          messages: messages,
          temperature: typeof c.temperature === 'number' ? c.temperature : 0.3,
          stream: false
        };

        return fetchWithTimeout(provider.baseUrl + (c.chatPath || '/chat/completions'), {
          method: 'POST',
          headers: {
            'Authorization': 'Bearer ' + key,
            'Content-Type': 'application/json',
            'Accept': 'application/json'
          },
          body: JSON.stringify(payload)
        }, c.chatTimeoutMs || 30000)
          .then(function (res) {
            return res.text().then(function (t) {
              var body = null;
              try { body = JSON.parse(t); } catch (e) { /* 可能不是 JSON */ }
              if (!res.ok) {
                return { ok: false, error: 'HTTP ' + res.status + (errorMessage(body) ? ' ' + errorMessage(body) : '') };
              }
              var content = body && body.choices && body.choices[0] && body.choices[0].message
                ? body.choices[0].message.content : null;
              if (!content || !String(content).trim()) {
                return { ok: false, error: '模型返回了空内容' };
              }
              return {
                ok: true,
                content: String(content).trim(),
                model: provider.model,
                provider: provider.id,
                providerLabel: provider.label
              };
            });
          })
          .catch(function (err) {
            return {
              ok: false,
              error: isTimeout(err) ? '请求超时（可能是网络问题）' : String((err && err.message) || err)
            };
          });
      });
    });
  }

  function toProvider(r) {
    return {
      id: r.provider,
      label: r.providerLabel || r.provider,
      baseUrl: baseOf({ baseUrl: r.baseUrl }),
      model: r.model,
      modelHints: r.modelHints || []
    };
  }

  global.CampusLlm = {
    version: VERSION,
    providers: function () { return loadConfig().then(function (c) { return providersOf(c); }); },
    match: match,
    chat: chat
  };
})(window);
