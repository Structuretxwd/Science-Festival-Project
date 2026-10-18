/**
 * 校园智能体「小助手」—— 离线检索引擎（JavaScript 实现）
 *
 * 这是与 java/src/campus/Engine.java 严格对齐的另一份实现：
 * 同样的归一化、同样的分词、同样的 BM25、同样的置信度公式、同样的阈值。
 * 两者读取同一份 data/kb.json、data/synonyms.json、data/config.json。
 *
 * 任何一处逻辑改动都必须同步改两个实现，并跑 tools/equiv-check.js 验证。
 *
 * 对外接口：
 *   window.CampusEngine.create()            -> Promise<Engine>
 *   window.CampusEngine.create({kb,synonyms,config})  // 注入数据，供 Node 测试用
 *   engine.ask(question)                    -> Promise<AskResult>
 */
(function (global) {
  'use strict';

  var VERSION = '0.1.0';

  // ---------------------------------------------------------------- 字符工具

  function isCJK(ch) {
    if (!ch) return false;
    var p = ch.codePointAt(0);
    return p >= 0x4e00 && p <= 0x9fff;
  }

  function isAlnumLower(ch) {
    return (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9');
  }

  /** 全角转半角 + 转小写。两个实现必须完全一致。 */
  function foldWidth(s) {
    var out = '';
    for (var i = 0; i < s.length; i++) {
      var ch = s[i];
      var p = ch.codePointAt(0);
      if (p >= 0xff01 && p <= 0xff5e) {
        out += String.fromCharCode(p - 0xfee0);
      } else if (p === 0x3000) {
        out += ' ';
      } else {
        out += ch;
      }
    }
    return out.toLowerCase();
  }

  /**
   * 归一化：全角转半角 -> 转小写 -> 非「汉字/字母/数字」一律替换为空格 -> 折叠空格。
   * 标点被替换成空格而不是删除，这样「食堂？几点」不会产生跨标点的伪二元组，
   * 同时答案里的「【示例数据】」这类标记会被自动甩掉，不会污染索引。
   */
  function normalize(s) {
    var folded = foldWidth(s == null ? '' : String(s));
    var out = '';
    for (var i = 0; i < folded.length; i++) {
      var ch = folded[i];
      if (isCJK(ch) || isAlnumLower(ch)) out += ch;
      else out += ' ';
    }
    return out.replace(/\s+/g, ' ').trim();
  }

  // ---------------------------------------------------------------- 同义词

  /**
   * 构建整串替换器。
   * 按 variant 长度从长到短排序，逐个做全局字符串替换。
   * 排序保证「长词优先」，避免短词先替换后破坏长词。
   */
  function buildReplacer(synonyms) {
    var pairs = [];
    var groups = (synonyms && synonyms.groups) || [];
    for (var i = 0; i < groups.length; i++) {
      var canonical = normalize(groups[i].canonical);
      var variants = groups[i].variants || [];
      for (var j = 0; j < variants.length; j++) {
        var variant = normalize(variants[j]);
        if (!variant || !canonical || variant === canonical) continue;
        // 防御：canonical 里含有该 variant 时跳过，否则会产生嵌套替换
        if (canonical.indexOf(variant) >= 0) continue;
        pairs.push({ variant: variant, canonical: canonical });
      }
    }
    pairs.sort(function (a, b) {
      if (b.variant.length !== a.variant.length) return b.variant.length - a.variant.length;
      return a.variant < b.variant ? -1 : a.variant > b.variant ? 1 : 0;
    });
    return function (text) {
      for (var k = 0; k < pairs.length; k++) {
        var p = pairs[k];
        if (text.indexOf(p.variant) >= 0) text = text.split(p.variant).join(p.canonical);
      }
      return text;
    };
  }

  // ---------------------------------------------------------------- 分词

  /**
   * 中文按「一元组 + 二元组」混合切分，英文数字按整段切分。
   *
   * 为什么要一元组：纯二元组对「食堂什么时候开门」这种换说法的问法召回太差；
   * 补上一元组后单字也能命中，召回明显变好。二元组权重高于一元组，
   * 保证「食堂」这种真正的词比单个「食」字贡献更大，精度不至于崩。
   */
  function tokenize(text, weightUnigram, weightBigram) {
    var out = [];
    var n = text.length;
    var i = 0;
    while (i < n) {
      var ch = text[i];
      if (ch === ' ') { i++; continue; }
      if (isCJK(ch)) {
        var j = i;
        while (j < n && isCJK(text[j])) j++;
        var run = text.slice(i, j);
        for (var k = 0; k < run.length; k++) out.push({ t: run[k], w: weightUnigram });
        for (var m = 0; m + 1 < run.length; m++) out.push({ t: run.slice(m, m + 2), w: weightBigram });
        i = j;
      } else {
        var q = i;
        while (q < n && isAlnumLower(text[q])) q++;
        out.push({ t: text.slice(i, q), w: weightBigram });
        i = q;
      }
    }
    return out;
  }

  // ---------------------------------------------------------------- 引擎

  function Engine(kb, synonyms, config, stopwords) {
    this.kb = kb;
    this.config = config;
    this.replaceSynonyms = buildReplacer(synonyms);
    this.stop = Object.create(null);
    var stopList = (stopwords && stopwords.stopwords) || [];
    for (var si = 0; si < stopList.length; si++) this.stop[stopList[si]] = true;
    this.wUni = config.tokenWeights.unigram;
    this.wBi = config.tokenWeights.bigram;
    this.entries = [];
    this.df = Object.create(null);
    this.avgLen = {};
    this.fieldNames = ['question', 'altQuestions', 'keywords', 'answer'];
    this._build();
  }

  Engine.prototype._build = function () {
    var self = this;
    var fields = this.fieldNames;
    var totals = {};
    fields.forEach(function (f) { totals[f] = 0; });

    (this.kb.entries || []).forEach(function (raw) {
      var texts = {
        question: self._norm(raw.question),
        altQuestions: self._norm((raw.altQuestions || []).join(' ')),
        keywords: self._norm((raw.keywords || []).join(' ')),
        answer: self._norm(raw.answer)
      };
      var entry = {
        raw: raw,
        id: raw.id,
        category: raw.category,
        question: raw.question,
        source: raw.source,
        verified: raw.verified === true,
        fieldTf: {},
        fieldLen: {},
        tokenSet: Object.create(null)
      };
      fields.forEach(function (f) {
        var tf = Object.create(null);
        var len = 0;
        var toks = self._tok(texts[f]);
        for (var i = 0; i < toks.length; i++) {
          var tk = toks[i];
          tf[tk.t] = (tf[tk.t] || 0) + tk.w;
          len += tk.w;
          entry.tokenSet[tk.t] = true;
        }
        entry.fieldTf[f] = tf;
        entry.fieldLen[f] = len;
        totals[f] += len;
      });
      // 统计文档频率：以「条目级 token 集合」为准，同一词在一条里出现多次只算一次
      for (var t in entry.tokenSet) {
        self.df[t] = (self.df[t] || 0) + 1;
      }
      self.entries.push(entry);
    });

    var N = this.entries.length || 1;
    fields.forEach(function (f) { self.avgLen[f] = totals[f] / N; });
  };

  Engine.prototype._norm = function (s) {
    return this.replaceSynonyms(normalize(s));
  };

  /** 分词 + 过滤停用词。索引端与查询端必须走同一个方法，否则打分口径不一致。 */
  Engine.prototype._tok = function (text) {
    var toks = tokenize(text, this.wUni, this.wBi);
    var out = [];
    for (var i = 0; i < toks.length; i++) {
      if (!this.stop[toks[i].t]) out.push(toks[i]);
    }
    return out;
  };

  Engine.prototype._idf = function (t) {
    var df = this.df[t] || 0;
    var N = this.entries.length;
    return Math.log(1 + (N - df + 0.5) / (df + 0.5));
  };

  /*
   * 关于「错别字自动纠错」：早期版本在这里做过 Levenshtein 距离 <= 1 的二元组纠正，
   * 实测后被移除。原因是中文二元组的距离 1 几乎等价于「两个字里共用一个字」，
   * 会把无关输入硬拽进知识库，例如「今天天气怎么样」中的
   *   今天 -> 一天、天天 -> 天上、天气 -> 天上、气怎 -> 了怎
   * 全部命中作息条目，导致本该回答「不知道」的问题被硬答。
   *
   * 现在改为：不做自动纠错，靠「一元组 + 二元组」混合召回兜底（写错一个字仍能命中，
   * 只是覆盖率下降、置信度降级为 mid，助手会提示「我理解得可能不准确」），
   * 确需支持的常见错别字写进 synonyms.json 作为人工整理的混淆表（如 三个面像 -> 三个面向）。
   */

  Engine.prototype._queryTokens = function (question) {
    var norm = this._norm(question);
    var toks = this._tok(norm);
    var seen = Object.create(null);
    var list = [];
    for (var i = 0; i < toks.length; i++) {
      var t = toks[i].t;
      if (seen[t]) continue;
      seen[t] = true;
      list.push({ t: t, w: toks[i].w });
    }
    return list;
  };

  Engine.prototype._score = function (entry, queryTokens) {
    var cfg = this.config;
    var k1 = cfg.bm25.k1, b = cfg.bm25.b;
    var fw = cfg.fieldWeights;
    var total = 0;
    for (var i = 0; i < queryTokens.length; i++) {
      var t = queryTokens[i].t;
      var idf = this._idf(t);
      for (var f = 0; f < this.fieldNames.length; f++) {
        var name = this.fieldNames[f];
        var tfw = entry.fieldTf[name][t];
        if (!tfw) continue;
        var avg = this.avgLen[name] || 0;
        var ratio = avg > 0 ? entry.fieldLen[name] / avg : 1;
        var denom = tfw + k1 * (1 - b + b * ratio);
        total += idf * fw[name] * (tfw * (k1 + 1)) / denom;
      }
    }
    return total;
  };

  /**
   * 覆盖率 = 命中的查询词权重和 / 查询词权重和。
   * 按权重加权而不是数个数：二元组（真正的词）权重 1.0、一元组（单字）0.45。
   * 不这样做的话，「今天天气怎么样」这种库外问题会靠「天」「样」这类常见单字
   * 刷出虚高的覆盖率，导致该说「不知道」的时候却硬答。
   */
  Engine.prototype._coverage = function (entry, queryTokens) {
    var total = 0, hit = 0;
    for (var i = 0; i < queryTokens.length; i++) {
      total += queryTokens[i].w;
      if (entry.tokenSet[queryTokens[i].t]) hit += queryTokens[i].w;
    }
    return total > 0 ? hit / total : 0;
  };

  Engine.prototype.ask = function (question) {
    var self = this;
    var started = Date.now();
    var cfg = this.config;
    var queryTokens = this._queryTokens(question || '');

    var scored = [];
    for (var i = 0; i < this.entries.length; i++) {
      var e = this.entries[i];
      var s = this._score(e, queryTokens);
      scored.push({ entry: e, score: s, coverage: this._coverage(e, queryTokens) });
    }
    scored.sort(function (a, b) {
      if (b.score !== a.score) return b.score - a.score;
      return a.entry.id < b.entry.id ? -1 : a.entry.id > b.entry.id ? 1 : 0;
    });

    var top = scored[0] || null;
    var second = scored[1] || null;
    var topScore = top ? top.score : 0;
    var gapRatio = topScore > 0 ? (topScore - (second ? second.score : 0)) / topScore : 0;
    var coverage = top ? top.coverage : 0;

    var conf = cfg.confidence;
    var level;
    if (!top || topScore <= 0 || coverage < conf.midCoverage) level = 'low';
    else if (coverage >= conf.highCoverage && gapRatio >= conf.highGapRatio) level = 'high';
    else level = 'mid';

    var candidates = scored
      .filter(function (x) { return x.score > 0; })
      .slice(0, cfg.maxCandidates)
      .map(function (x) {
        return {
          id: x.entry.id,
          question: x.entry.question,
          category: x.entry.category,
          coverage: Math.round(x.coverage * 1000) / 1000
        };
      });

    var matched = queryTokens
      .filter(function (t) { return top && top.entry.tokenSet[t.t]; })
      .sort(function (a, b) {
        if (b.w !== a.w) return b.w - a.w;
        return a.t < b.t ? -1 : a.t > b.t ? 1 : 0;
      })
      .slice(0, 8)
      .map(function (t) { return t.t; });

    var msg = cfg.messages;
    var answer, notice = null;
    if (level === 'low') {
      answer = msg.lowFallback;
      notice = msg.lowNotice;
    } else {
      answer = top.entry.raw.answer;
      if (level === 'mid') {
        answer = msg.midPrefix + '\n' + answer;
        notice = msg.midNotice;
      }
    }

    return Promise.resolve({
      mode: 'offline',
      confidence: level,
      answer: answer,
      entryId: level === 'low' ? null : top.entry.id,
      entryQuestion: level === 'low' ? null : top.entry.question,
      category: level === 'low' ? null : top.entry.category,
      source: level === 'low' ? null : top.entry.source,
      verified: level === 'low' ? null : top.entry.verified,
      candidates: candidates,
      matchedTerms: matched,
      notice: notice,
      engine: { impl: 'js', version: VERSION, queryTokens: queryTokens.length, topScore: Math.round(topScore * 1000) / 1000, coverage: Math.round(coverage * 1000) / 1000, gapRatio: Math.round(gapRatio * 1000) / 1000 },
      timingMs: Date.now() - started
    });
  };

  // ---------------------------------------------------------------- 对外接口

  function fetchJson(url) {
    return fetch(url).then(function (r) {
      if (!r.ok) throw new Error('加载失败: ' + url + ' HTTP ' + r.status);
      return r.json();
    });
  }

  function create(opts) {
    opts = opts || {};
    var base = opts.base || 'data/';
    if (opts.kb && opts.synonyms && opts.config) {
      return Promise.resolve(new Engine(opts.kb, opts.synonyms, opts.config, opts.stopwords));
    }
    return Promise.all([
      opts.kb ? Promise.resolve(opts.kb) : fetchJson(base + 'kb.json'),
      opts.synonyms ? Promise.resolve(opts.synonyms) : fetchJson(base + 'synonyms.json'),
      opts.config ? Promise.resolve(opts.config) : fetchJson(base + 'config.json'),
      opts.stopwords ? Promise.resolve(opts.stopwords) : fetchJson(base + 'stopwords.json')
    ]).then(function (r) { return new Engine(r[0], r[1], r[2], r[3]); });
  }

  var api = { version: VERSION, create: create, normalize: normalize, tokenize: tokenize };

  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  global.CampusEngine = api;
})(typeof window !== 'undefined' ? window : globalThis);
