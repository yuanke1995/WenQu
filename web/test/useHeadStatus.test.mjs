import test from 'node:test'
import assert from 'node:assert/strict'
import { ref } from 'vue'
import { useHeadStatus } from '../src/chat/useHeadStatus.js'

const setup = ({ narrow = false, open = true, tab = 'browser' } = {}) => {
  const panelOpenRef = ref(open)
  const mPanelOpenRef = ref(false)
  const narrowRef = ref(narrow)
  const toggles = []
  const togglePanel = () => {
    toggles.push(narrowRef.value ? mPanelOpenRef.value : panelOpenRef.value)
    if (narrowRef.value) mPanelOpenRef.value = !mPanelOpenRef.value
    else panelOpenRef.value = !panelOpenRef.value
  }
  const p = useHeadStatus({
    processMsg: () => null,
    roundSteps: () => [],
    panelOpen: () => panelOpenRef.value,
    mPanelOpen: () => mPanelOpenRef.value,
    isNarrow: () => narrowRef.value,
    togglePanel,
    deliveryPackages: ref([]),
    sandboxCount: ref(0),
    procs: ref([]),
    sandboxErr: ref(''),
    sandboxLoading: ref(false),
    procCardVisible: ref(false),
    messages: ref([])
  })
  p.railTab.value = tab
  return { p, panelOpenRef, mPanelOpenRef, narrowRef, toggles }
}

test('状态胶囊：右栏展开停在浏览页签时点它直接收起，不再只切到过程', () => {
  const { p, panelOpenRef, toggles } = setup({ open: true, tab: 'browser' })
  p.headStatusClick()
  assert.equal(panelOpenRef.value, false)
  assert.equal(p.railTab.value, 'browser')   // 收起不改写当前页签
  assert.equal(toggles.length, 1)
})

test('状态胶囊：展开态在任意页签都收起', () => {
  for (const tab of ['process', 'sources', 'files', 'browser']) {
    const { p, panelOpenRef, toggles } = setup({ open: true, tab })
    p.headStatusClick()
    assert.equal(panelOpenRef.value, false, tab)
    assert.equal(p.railTab.value, tab)
    assert.equal(toggles.length, 1, tab)
  }
})

test('状态胶囊：收起态点它开栏并回到过程页签', () => {
  const { p, panelOpenRef, toggles } = setup({ open: false, tab: 'sources' })
  p.headStatusClick()
  assert.equal(panelOpenRef.value, true)
  assert.equal(p.railTab.value, 'process')
  assert.equal(toggles.length, 1)
})

test('状态胶囊：窄屏按移动栏开合判定，浏览页签展开时同样收起', () => {
  const { p, mPanelOpenRef, panelOpenRef, toggles } = setup({ narrow: true, open: false, tab: 'browser' })
  mPanelOpenRef.value = true
  p.headStatusClick()
  assert.equal(mPanelOpenRef.value, false)
  assert.equal(panelOpenRef.value, false)
  assert.equal(p.railTab.value, 'browser')
  assert.equal(toggles.length, 1)
})

test('状态胶囊：tooltip 与开合态一致', () => {
  const open = setup({ open: true })
  assert.equal(open.p.headStatusTitle.value, '收起状态栏')
  const closed = setup({ open: false })
  assert.equal(closed.p.headStatusTitle.value, '展开状态栏')
})
