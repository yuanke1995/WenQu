import test from 'node:test'
import assert from 'node:assert/strict'
import { shouldInterceptDownload } from '../src/utils/download.js'

test('只拦截跨源 http(s) 下载；同源与 blob/data 保持原生', () => {
  const origin = 'https://wenqu.example.com'
  assert.equal(shouldInterceptDownload('https://cdn.example.com/a.zip?sig=1', origin), true)
  assert.equal(shouldInterceptDownload('https://wenqu.example.com/a.zip', origin), false)
  assert.equal(shouldInterceptDownload('/a.zip', origin), false)
  assert.equal(shouldInterceptDownload('blob:https://wenqu.example.com/x', origin), false)
  assert.equal(shouldInterceptDownload('data:text/plain,x', origin), false)
  assert.equal(shouldInterceptDownload('', origin), false)
})
