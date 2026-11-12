// 交付包 ZIP 下载：复用产物页的批量打包接口，不另写一套打包逻辑。
// 下载器可注入，便于单测；返回 true=已发起下载，false=没有可下载文件。
export const downloadDeliveryPackage = async (pkg, download) => {
  if (!pkg || !Array.isArray(pkg.files) || !pkg.files.length) return false
  const ids = pkg.files.map(f => f.id).filter(Boolean)
  if (!ids.length) return false
  await download(ids)
  return true
}
