import test from 'node:test'
import assert from 'node:assert/strict'
import { baseNameOf, dirOf, toPosix } from '../src/utils/pathUtil.js'
import { parseJsonFile } from '../src/utils/json.js'

test('Windows 路径按 POSIX 折目录', () => {
  assert.equal(toPosix('C:\\Users\\me\\work\\a.txt'), 'C:/Users/me/work/a.txt')
  assert.equal(baseNameOf('C:\\Users\\me\\work\\a.txt'), 'a.txt')
  assert.equal(dirOf('C:\\Users\\me\\work\\a.txt'), 'C:/Users/me/work')
  assert.equal(baseNameOf('/a/b/'), 'b')
})

test('带 BOM 的 JSON 文件可以导入', () => {
  assert.deepEqual(parseJsonFile('\uFEFF{"name":"问渠"}'), { name: '问渠' })
  assert.deepEqual(parseJsonFile('{"n":1}'), { n: 1 })
})
