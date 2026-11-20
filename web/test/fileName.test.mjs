import test from 'node:test'
import assert from 'node:assert/strict'
import { sanitizeFileName } from '../src/utils/fileName.js'

test('非法字符统一替换为下划线', () => {
  assert.equal(sanitizeFileName('a/b\\c:d*e?f"g<h>i|j'), 'a_b_c_d_e_f_g_h_i_j')
})

test('Windows 保留名加前缀，尾部点空格去除', () => {
  assert.equal(sanitizeFileName('CON.txt'), '_CON.txt')
  assert.equal(sanitizeFileName('report. . '), 'report')
  assert.equal(sanitizeFileName('..'), 'unnamed')
})

test('限长后不会留下尾部点', () => {
  assert.equal(sanitizeFileName('abcdefgh', 6), 'abcdef')
  assert.equal(sanitizeFileName('报告.', 2), '报告')
})
