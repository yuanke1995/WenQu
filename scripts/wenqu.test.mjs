import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { COMPOSE, UNITS, composeArgs, findRel } from './wenqu.mjs'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')

test('findRel：按文件名在 scripts/ 下定位（不依赖 shell find）', () => {
  assert.equal(findRel('wenqu-agent.mjs'), 'local-agent/wenqu-agent.mjs')
  assert.equal(findRel('check-eol.mjs'), 'check-eol.mjs')
  assert.equal(findRel('not-exists.mjs'), null)
})

test('composeArgs：-d / -f 在 action 之后，service 在最后', () => {
  assert.deepEqual(composeArgs('mysql', 'up', { detached: true }),
    ['compose', '-f', 'docker-compose.yml', 'up', '-d', 'mysql'])
  assert.deepEqual(composeArgs('redis', 'down'),
    ['compose', '-f', 'docker-compose.yml', 'down', 'redis-stack'])
  assert.deepEqual(composeArgs('meili', 'logs', { follow: true }),
    ['compose', '-f', 'deploy/meilisearch/docker-compose.yml', 'logs', '-f'])
})

test('每个中间件的 compose 文件都真实存在', () => {
  for (const unit of UNITS) {
    assert.ok(fs.existsSync(path.join(root, COMPOSE[unit].file)), `${unit} 的 compose 文件缺失`)
  }
})
