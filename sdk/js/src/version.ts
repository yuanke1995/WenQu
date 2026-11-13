/**
 * SDK 版本单一真源（JS 侧）。
 *
 * <p>每次请求以 X-Wenqu-Sdk-Version 上报，服务端记进 debug 日志用于比对契约/客户端错配。
 * 发布时必须与 package.json 的 version、Python SDK 的 pyproject.toml / _version.py 同步；
 * CI 的 scripts/check-sdk-version.mjs 会在出包前拦住版本漂移。</p>
 */
export const SDK_VERSION = "0.1.0";
