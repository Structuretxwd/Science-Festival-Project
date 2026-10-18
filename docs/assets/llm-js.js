/**
 * 校园智能体「小助手」—— 浏览器直连大模型（无后端时的联网通道）
 *
 * 部署到 GitHub Pages 这类纯静态托管时，没有 Java 后端可调用，
 * 但 DeepSeek 的接口开放了 CORS（实测 OPTIONS 预检会回显 Origin），
 * 所以浏览器可以直接发请求。密钥只存在使用者自己的 localStorage 里，
 * 由使用者在界面上填写 —— 公开站点上绝不放任何公共 Key。
 *
 * 参数一律从 data/config.json 的 llm 段读取，和 Java 侧保持同一份配置。
 *
 * 对外接口：
 *   window.CampusLlm.probe(apiKey)                  -> Promise<{status,reachable,valid,model,detail}>
 *   window.CampusLlm.chat({apiKey,messages,kbContext}) -> Promise<{ok,content,error}>
 */
(function (global) {
  'use strict';

  var VERSION = '0.1.0';
  var FALLBACK_BASE = 'https://api.deepseek.com'; // 仅在 config.json 缺失时兜底
  var FALLBACK_MODEL = 'deepseek-chat';

  var cfgPromise = null;
  var schoolPromise = null;

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
      .then(function (kb) { return (kb && kb.meta && kb.meta.school) || ''; })
      .catch(function () { return ''; });
    return schoolPromise;
  }

  function baseOf(c) {
    var b = String((c && c.baseUrl) || FALLBACK_BASE).trim();
    while (b.length > 1 && b.charAt(b.length - 1) === '/') b = b.slice(0, -1);
    return b;
  }

  /* ---------------------------------------------------------------- 提示词 */

  /**
   * 与 java/src/campus/Router.java 的 systemPrompt 保持同一套要求，
   * 这样「有后端」和「没后端」两条联网路径给出的答案风格一致。
   */
  function systemPrompt(school, kbContext) {
    var who = school ? '你叫「小助手」，是' + school + '的校园问答助手，服务对象是学生和家长。'
                     : '你叫「小助手」，是一个校园问答助手，服务对象是学生和家长。';
    var p = who + '\n\n回答要求：\n'
      + '1. 如果下面给了【知识库参考】，优先依据它作答，用自然、口语化的中文把答案讲清楚，不要照抄原文格式。\n'
      + '2. 知识库没有覆盖的内容，可以用常识回答，但要明确说明「这一点知识库里没有，以下仅供参考」。\n'
      + '3. 涉及学校未公开的具体信息（电话、时间、金额、人数等）又拿不准时，直接说不知道，不要编造。\n'
      + '4. 回答控制在 180 字以内，不要用 Markdown 标题，需要分点时用简短的序号。\n'
      + '5. 语气友好、干脆，像一个熟悉校园的学长学姐。';
    if (kbContext) {
      p += '\n\n【知识库参考（来自学校官方信息，优先采用）】\n' + kbContext;
    }
    return p;
  }

  /* ---------------------------------------------------------------- 工具 */

  function pickModel(body, fallback) {
    try {
      var data = body && body.data;
      if (Array.isArray(data) && data.length) {
        for (var i = 0; i < data.length; i++) {
          var id = data[i] && data[i].id;
          if (id && String(id).indexOf(fallback) >= 0) return String(id);
        }
        if (data[0] && data[0].id) return String(data[0].id);
      }
    } catch (e) { /* 模型名只用于显示，取不到就算了 */ }
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

  /* ---------------------------------------------------------------- 探测 */

  /**
   * 探测 Key 是否可用。GET /models 是最轻量的验签方式：
   * 200 说明网络通 + Key 有效；401/402/403 说明 Key 有问题；其他一律当作网络不可用。
   */
  function probe(apiKey) {
    if (!apiKey || !String(apiKey).trim()) {
      return Promise.resolve({ status: 'no_key', reachable: false, valid: false, model: '', via: 'llm' });
    }
    var key = String(apiKey).trim();
    return loadConfig().then(function (c) {
      var url = baseOf(c) + (c.probePath || '/models');
      return fetchWithTimeout(url, {
        method: 'GET',
        headers: { 'Authorization': 'Bearer ' + key, 'Accept': 'application/json' }
      }, c.probeTimeoutMs || 2500)
        .then(function (res) {
          if (res.ok) {
            return res.json().catch(function () { return null; }).then(function (body) {
              return {
                status: 'ok', reachable: true, valid: true,
                model: pickModel(body, c.model || FALLBACK_MODEL), via: 'llm'
              };
            });
          }
          if (res.status === 401 || res.status === 402 || res.status === 403) {
            return {
              status: 'invalid_key', reachable: true, valid: false, model: '',
              via: 'llm', detail: 'HTTP ' + res.status
            };
          }
          return {
            status: 'unreachable', reachable: false, valid: false, model: '',
            via: 'llm', detail: 'HTTP ' + res.status
          };
        })
        .catch(function (err) {
          return {
            status: 'unreachable', reachable: false, valid: false, model: '',
            via: 'llm', detail: isTimeout(err) ? '请求超时' : String((err && err.message) || err)
          };
        });
    });
  }

  /* ---------------------------------------------------------------- 对话 */

  function chat(opts) {
    var o = opts || {};
    if (!o.apiKey || !String(o.apiKey).trim()) {
      return Promise.resolve({ ok: false, error: '未配置密钥' });
    }
    var key = String(o.apiKey).trim();

    return Promise.all([loadConfig(), loadSchool()]).then(function (both) {
      var c = both[0];
      var school = both[1];

      var messages = [{ role: 'system', content: systemPrompt(school, o.kbContext || '') }];
      var history = Array.isArray(o.messages) ? o.messages : [];
      for (var i = 0; i < history.length; i++) {
        var m = history[i];
        if (!m || !m.content) continue;
        if (m.role !== 'user' && m.role !== 'assistant') continue;
        messages.push({ role: m.role, content: String(m.content) });
      }
      if (messages.length === 1) {
        return { ok: false, error: '没有可发送的内容' };
      }

      var payload = {
        model: c.model || FALLBACK_MODEL,
        messages: messages,
        temperature: typeof c.temperature === 'number' ? c.temperature : 0.3,
        stream: false
      };

      return fetchWithTimeout(baseOf(c) + (c.chatPath || '/chat/completions'), {
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
            return { ok: true, content: String(content).trim(), model: payload.model };
          });
        })
        .catch(function (err) {
          return {
            ok: false,
            error: isTimeout(err) ? '请求超时（可能是网络问题）' : String((err && err.message) || err)
          };
        });
    });
  }

  global.CampusLlm = {
    version: VERSION,
    probe: probe,
    chat: chat
  };
})(window);
