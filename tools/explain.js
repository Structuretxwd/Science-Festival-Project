/**
 * 检索引擎单条查询「解剖」工具 —— 排查打分问题时用
 *
 * 用法：node tools/explain.js "今天天气怎么样"
 *
 * 会打印：归一化结果、同义词替换结果、查询词与总权重、判定结果、
 * 各候选条目的分数与覆盖率、以及命中词明细。
 * Java 端排错请看 campus.Engine 的 explain()，口径与此一致。
 */
'use strict';

const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
const DATA = path.join(ROOT, 'docs', 'data');
const read = (n) => JSON.parse(fs.readFileSync(path.join(DATA, n), 'utf8'));

const CampusEngine = require(path.join(ROOT, 'docs', 'assets', 'engine-js.js'));

const query = process.argv.slice(2).join(' ') || '今天天气怎么样';

CampusEngine.create({
  kb: read('kb.json'),
  synonyms: read('synonyms.json'),
  config: read('config.json'),
  stopwords: read('stopwords.json')
}).then((engine) => {
  console.log('原始查询 :', query);
  console.log('归一化后 :', CampusEngine.normalize(query));
  console.log('同义词后 :', engine._norm(query));

  const raw = engine._tok(engine._norm(query));
  console.log('过滤前词 :', raw.map((t) => t.t + '(' + t.w + ')').join(' '));
  const tokens = engine._queryTokens(query);
  console.log('查询词   :', tokens.map((t) => t.t + '(' + t.w + ')').join(' '));
  console.log('总权重   :', tokens.reduce((s, t) => s + t.w, 0));

  // engine.ask() 返回 Promise（浏览器与 Node 共用同一份实现），必须 await 后才能取字段
  return engine.ask(query).then((result) => {
    console.log('\n判定     :', result.confidence, '| coverage=' + result.engine.coverage,
      '| gapRatio=' + result.engine.gapRatio, '| topScore=' + result.engine.topScore);

    const scored = engine.entries.map((e) => ({
      id: e.id,
      score: engine._score(e, tokens),
      cov: engine._coverage(e, tokens),
      hits: tokens.filter((t) => e.tokenSet[t.t])
    })).sort((a, b) => b.score - a.score).slice(0, 5);

    console.log('\n前 5 候选：');
    for (const s of scored) {
      const hitWeight = s.hits.reduce((x, t) => x + t.w, 0);
      console.log('  ' + s.id.padEnd(26) +
        ' score=' + s.score.toFixed(3).padStart(8) +
        ' cov=' + s.cov.toFixed(3) +
        ' hitW=' + hitWeight.toFixed(2) +
        ' 命中[' + s.hits.map((t) => t.t).join(',') + ']');
    }
  });
});
