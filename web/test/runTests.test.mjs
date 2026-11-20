import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { collectTestFiles } from '../../scripts/run-tests.mjs'

test('测试收集器：递归收集 .test.mjs/.test.js，不依赖 shell glob', t => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'wenqu-tests-'))
  t.after(() => fs.rmSync(root, { recursive: true, force: true }))
  fs.writeFileSync(path.join(root, 'a.test.mjs'), '')
  fs.writeFileSync(path.join(root, 'ignore.txt'), '')
  fs.mkdirSync(path.join(root, 'sub'))
  fs.writeFileSync(path.join(root, 'sub', 'b.test.js'), '')

  const files = collectTestFiles([root])
  assert.equal(files.length, 2)
  assert.ok(files[0].endsWith('a.test.mjs'))
  assert.ok(files[1].endsWith(path.join('sub', 'b.test.js')))
})

test('测试收集器：目录不存在时抛 E_TEST_DIR', () => {
  assert.throws(() => collectTestFiles(['/definitely/not/here']), e => e.code === 'E_TEST_DIR')
})
