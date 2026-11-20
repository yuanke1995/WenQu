/** Windows 记事本等工具另存的 UTF-8 BOM 会让 JSON.parse 直接失败，统一在这里剥离。 */
export function parseJsonFile (text) {
  return JSON.parse(String(text == null ? '' : text).replace(/^\uFEFF/, ''))
}
