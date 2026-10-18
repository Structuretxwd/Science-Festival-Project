/**
 * JS 引擎自测 + 同义词表安全检查
 *
 * 用法：node tools/js-selftest.js
 * 输出一行一个用例，格式与 java 的 campus.SelfTest 完全一致，便于 tools/equiv-check.js 直接比对：
 *   <query>\t<topId|->\t<confidence>\t<coverage>\t<PASS|FAIL>
 */
'use strict';

const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
const DATA = path.join(ROOT, 'docs', 'data');

function readJson(name) {
  return JSON.parse(fs.readFileSync(path.join(DATA, name), 'utf8'));
}

// ---------- 同义词表安全检查 ----------
function checkSynonyms(synonyms) {
  const pairs = [];
  for (const g of synonyms.groups || []) {
    const canonical = g.canonical;
    for (const v of g.variants || []) {
      if (v === canonical) continue;
      if (canonical.includes(v)) {
        throw new Error(`同义词嵌套风险：variant「${v}」是 canonical「${canonical}」的子串`);
      }
      pairs.push({ variant: v, canonical });
    }
  }
  pairs.sort((a, b) => b.variant.length - a.variant.length || (a.variant < b.variant ? -1 : 1));
  for (let i = 0; i < pairs.length; i++) {
    for (let j = i + 1; j < pairs.length; j++) {
      if (pairs[i].canonical.includes(pairs[j].variant)) {
        throw new Error(
          `同义词嵌套风险：先替换「${pairs[i].variant}」→「${pairs[i].canonical}」，` +
          `但结果里含后续待替换的「${pairs[j].variant}」`
        );
      }
      if (pairs[j].variant.includes(pairs[i].variant) && pairs[i].variant !== pairs[j].variant) {
        throw new Error(`同义词包含风险：「${pairs[i].variant}」被「${pairs[j].variant}」包含`);
      }
    }
  }
  return pairs.length;
}

// ---------- 主流程 ----------
const kb = readJson('kb.json');
const synonyms = readJson('synonyms.json');
const config = readJson('config.json');
const stopwords = readJson('stopwords.json');
const testcases = readJson('testcases.json');

const pairCount = checkSynonyms(synonyms);
console.error(`[同义词检查] 通过，共 ${pairCount} 组替换规则`);

const CampusEngine = require(path.join(ROOT, 'docs', 'assets', 'engine-js.js'));

(async () => {
  const engine = await CampusEngine.create({ kb, synonyms, config, stopwords });
  let pass = 0, fail = 0;
  const lines = [];
  for (const c of testcases.cases) {
    const r = await engine.ask(c.query);
    const topId = r.entryId === null ? '-' : r.entryId;
    const expectTop = c.expectId === null ? '-' : c.expectId;
    let ok = topId === expectTop;
    if (c.expectConfidence) ok = ok && r.confidence === c.expectConfidence;
    if (ok) pass++; else fail++;
    lines.push([c.query, topId, r.confidence, r.engine.coverage, ok ? 'PASS' : 'FAIL'].join('\t'));
  }
  lines.forEach((l) => console.log(l));
  console.error(`\n[结果] 通过 ${pass}，失败 ${fail}，共 ${testcases.cases.length}`);

  // 失败明细，方便调阈值
  if (fail) {
    console.error('\n[失败明细]');
    for (const c of testcases.cases) {
      const r = await engine.ask(c.query);
      const topId = r.entryId === null ? '-' : r.entryId;
      const expectTop = c.expectId === null ? '-' : c.expectId;
      if (topId === expectTop && (!c.expectConfidence || r.confidence === c.expectConfidence)) continue;
      console.error(
        `  查询「${c.query}」期望 ${expectTop}/${c.expectConfidence || '*'} ` +
        `实际 ${topId}/${r.confidence} coverage=${r.engine.coverage} gap=${r.engine.gapRatio} ` +
        `候选=${r.candidates.map((x) => x.id + ':' + x.coverage).join(',')}`
      );
    }
  }
})();
