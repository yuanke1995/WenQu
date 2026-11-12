import test from 'node:test'
import assert from 'node:assert/strict'
import { downloadDeliveryPackage } from '../src/chat/deliveryDownload.js'

test('交付包下载：把文件 id 交给下载器；空包不发请求', async () => {
  let got = null
  const ok = await downloadDeliveryPackage({ files: [{ id: 'a' }, { id: 'b' }] }, ids => { got = ids })
  assert.equal(ok, true)
  assert.deepEqual(got, ['a', 'b'])

  got = null
  const empty = await downloadDeliveryPackage({ files: [] }, ids => { got = ids })
  assert.equal(empty, false)
  assert.equal(got, null)
  assert.equal(await downloadDeliveryPackage(null, () => {}), false)
})

test('交付包下载：缺 id 的文件被跳过，全无 id 不下载', async () => {
  let got = null
  await downloadDeliveryPackage({ files: [{ name: 'x' }, { id: 'b' }] }, ids => { got = ids })
  assert.deepEqual(got, ['b'])
  assert.equal(await downloadDeliveryPackage({ files: [{ name: 'x' }] }, () => {}), false)
})
