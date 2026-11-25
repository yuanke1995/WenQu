// 追问改写未执行的原因 → 用户能看懂的一句话（界面不出现 no-history 这类开发术语）。
// 纯字典映射，从 ChatPage.vue 抽出；调试面板的「本轮未改写原因」用它。
export const rewriteSkipText = r => ({
  'no-history': '本会话还没有可参考的前文',
  blank: '检索词为空',
  'too-long': '检索词已足够完整',
  standalone: '检索词已足够独立',
  'no-model': '未取到本轮模型',
  timeout: '改写超时，已按原词检索',
  failed: '改写失败，已按原词检索',
  'empty-output': '改写没有返回可用结果',
  unchanged: '与原检索词一致'
}[r] || '本轮未改写')
