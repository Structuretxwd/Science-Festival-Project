/**
 * 双引擎等价性检查
 *
 * 同一份知识库、同一份参数，分别喂给 Java 实现和 JavaScript 实现，
 * 逐行比对输出。任何一行不一致就说明两个实现漂移了，必须修复。
 *
 * 用法：node tools/equiv-check.js
 * 退出码：0 = 完全等价；1 = 存在差异；2 = 运行环境/构建问题
 *
 * 为什么要有这个工具：
 *   双引擎最大的风险不是「某一侧算错」，而是「两侧慢慢长歪」——
 *   今天改了 JS 的加权覆盖率，忘了同步 Java，比赛现场就会一边答对一边答错。
 *   把比对固化成脚本，改完引擎跑一次就能立刻发现漂移。
 */

'use strict';

const { spawnSync } = require('child_process');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
const JAVA_DIR = path.join(ROOT, 'java');

const JAVA_CMD = [
  '-Dstdout.encoding=UTF-8',
  '-Dstderr.encoding=UTF-8',
  '-cp', 'out',
  'campus.SelfTest'
];

function run(label, cmd, args, cwd) {
  const r = spawnSync(cmd, args, { cwd, encoding: 'utf8', maxBuffer: 32 * 1024 * 1024 });
  if (r.error) {
    console.error(`[equiv] 无法执行 ${label}：${r.error.message}`);
    process.exit(2);
  }
  if (r.status !== 0 && r.status !== 2) {
    // SelfTest 约定：只要有用例失败就以 2 退出，这里仍然继续比对，
    // 因为「两侧结果一致地失败」也是等价的一种表现。
    console.error(`[equiv] ${label} 退出码 ${r.status}，stderr：\n${r.stderr}`);
  }
  return r.stdout || '';
}

/** 从混合输出里抽出形如 query \t id \t conf \t cov \t PASS|FAIL 的结果行。 */
function parseResultLines(stdout) {
  const lines = [];
  for (const raw of stdout.split(/\r?\n/)) {
    const cols = raw.split('\t');
    if (cols.length < 5) continue;
    const verdict = cols[cols.length - 1].trim();
    if (verdict !== 'PASS' && verdict !== 'FAIL') continue;
    lines.push(cols.map((c) => c.trim()).join('\t'));
  }
  return lines;
}

function buildJavaIfNeeded() {
  const marker = path.join(JAVA_DIR, 'out', 'campus', 'SelfTest.class');
  if (require('fs').existsSync(marker)) return true;
  console.log('[equiv] 未找到编译产物，先执行 java/build.bat …');
  const r = spawnSync('cmd', ['/c', 'build.bat'], { cwd: JAVA_DIR, encoding: 'utf8' });
  if (r.status !== 0) {
    console.error('[equiv] 构建失败，请先手动运行 java/build.bat 查看报错。');
    process.exit(2);
  }
  return true;
}

function main() {
  buildJavaIfNeeded();

  console.log('[equiv] 运行 Java 实现 …');
  const javaOut = run('java-campus.SelfTest', 'java', JAVA_CMD, JAVA_DIR);

  console.log('[equiv] 运行 JavaScript 实现 …');
  const jsOut = run('node-js-selftest', process.execPath, [path.join(ROOT, 'tools', 'js-selftest.js')], ROOT);

  const javaLines = parseResultLines(javaOut);
  const jsLines = parseResultLines(jsOut);

  console.log(`[equiv] Java 产出 ${javaLines.length} 行，JS 产出 ${jsLines.length} 行`);

  if (javaLines.length === 0 || jsLines.length === 0) {
    console.error('[equiv] 有一侧没有产出结果行，无法比对。');
    process.exit(2);
  }

  const n = Math.max(javaLines.length, jsLines.length);
  let diff = 0;
  const width = Math.min(28, Math.max(...javaLines.map((l) => l.split('\t')[0].length), 8));

  for (let i = 0; i < n; i++) {
    const a = javaLines[i];
    const b = jsLines[i];
    if (a === b) continue;
    diff++;
    const qa = a ? a.split('\t')[0] : '(缺行)';
    const qb = b ? b.split('\t')[0] : '(缺行)';
    const name = (qa === qb ? qa : `${qa} | ${qb}`).padEnd(width);
    console.log(`  ${name}  JAVA=${JSON.stringify(a)}  JS=${JSON.stringify(b)}`);
  }

  if (diff === 0) {
    console.log(`[equiv] 等价性通过：${javaLines.length} 条用例两侧输出完全一致。`);
    process.exit(0);
  }

  console.error(`[equiv] 发现 ${diff} 处不一致，两个实现已经漂移，请修复。`);
  process.exit(1);
}

main();
