// @ts-check
/**
 * 编辑区「在当前选区插入/替换文本」的落点计算（纯逻辑，便于单测）。
 *
 * Composer 的编辑区是 contenteditable：光标是一个折叠选区（from === to），
 * 选中一段文字则 from < to。所有插入都走 replaceRange(from, to, str)：
 *  - 折叠光标：from === to，纯插入；
 *  - 有选区  ：把 [from, to) 整段换掉——粘贴覆盖是这个路径，
 *              只按光标起点算会退化成「插在选区前面」，旧文字留在后面。
 *
 * caretIndex 只给起点；粘贴若直接用它当 from 和 to，就会踩上面那条。
 */
export const insertSpan = (from, to, length) => {
  const len = Math.max(0, length || 0)
  if (from == null || from < 0) {
    const at = len
    return [at, at]
  }
  const a = Math.min(from, len)
  const b = to == null ? a : Math.min(Math.max(to, a), len)
  return [a, b]
}
