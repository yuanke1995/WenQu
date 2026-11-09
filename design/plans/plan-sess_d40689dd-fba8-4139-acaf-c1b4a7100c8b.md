# 表单助手 B 档改造:dtbd 后端代理,问渠 token 不落浏览器

## Context

现状:dtbd 设计器前端 `formCopilot.js` 直连问渠 `/api/ai/share/{token}/...`,分享 token 存在浏览器 localStorage(`wenqu-copilot-token`),泄露面在 dtbd 侧。切 API Key 已评估过被否(Key 同样落浏览器、语义倒退)。真根治 = **B 档后端代理**:token 改存 dtbd 后端(按 dtbd 用户),浏览器只跟 dtbd 后端说话;dtbd 后端持 token 转发问渠。

**wenqu 零代码改动**:share 端点族/SSE/游客鉴权全不动;`ClientIp` 新口径已天然兼容——dtbd 后端从回环/私网接入即自动可信代理,转发时把真实客户端 IP 放 `X-Forwarded-For` 末段即可恢复按 IP 限频与审计 IP。

范围说明:历史图片与 artifact 仍由浏览器经 `/wenqu`(dev 代理/nginx)直连问渠——那些是签名 URL、不含 share token,不在泄密面内,不值得为此做二进制流转发。

## 一、dtbd 后端(dynamic-form,Java 8/Boot 2.7)

### 新增 5 个文件

1. **实体** `com.wisesoft.form.biz.base.CopilotWenquConfig`(domain 模块,actable 扫描包)→ 表 `c_copilot_wenqu_config`,照抄 [SysConfig.java](/Users/yuki/IdeaProjects/dynamic-form/com.wisesoft.form.domain/src/main/java/com/wisesoft/form/biz/base/SysConfig.java) 注解形态(@Table/@Column/@TableName/@TableId ASSIGN_ID,防 actable 驼峰坑):
   `id`、`userId`(varchar 50,唯一索引)、`shareToken`(varchar 100)、`updateTime`。
2. **Mapper** `com.wisesoft.form.biz.mapper.CopilotWenquConfigMapper extends BaseMapper<>`(两处 `@MapperScan` 的 `com.wisesoft.**.mapper` 都覆盖)。
3. **Service** `biz/service/impl/CopilotWenquConfigService`:`getByUserId / upsert / deleteByUserId` + `scope(token)` 静态方法——**FNV-1a 32 位 + base36(len),与前端 tokenScope 逐字同构**(int 乘法自然溢出 = Math.imul;token 校验限定 ASCII `^[A-Za-z0-9]{16,128}$`,则 UTF-8 字节 == charCode,两侧哈希必然一致,存量会话分格无缝延续)。
4. **Controller** `web/controllers/CopilotWenquProxyController`,`@RequestMapping(JwtConstants.JWT_PATH + "copilot")`:
   - `GET /config` → `{configured, tokenMasked(前4…后4), scope}`——永不回 token;
   - `PUT /config` body `{token}` → 校验、按 `SecurityContextHolder.getUserId()` upsert;`DELETE /config` → 断开;
   - `@RequestMapping("/share/**")` 泛转发(全 HTTP 方法):取用户 config,未配置 → HTTP 404 JSON(前端现有 loadInfo 404→连接卡逻辑直接吃);URL = `@Value("${wenqu.proxy.base-url:http://127.0.0.1:8090/ai}") + /api/ai/share/{token}/` + 归一化子路径(拒 `..`/`%2e%2e`)+ query 透传。
5. **请求头白名单工具**(并入 controller 私有方法即可):只透传 Content-Type/Accept,**不透传 cookie/X-Token**(dtbd 会话绝不外带);构造 `X-Forwarded-For: <bestClientIp>`(镜像 wenqu ClientIp 口径:对端私网且入站 XFF 非空取其末段,否则 getRemoteAddr)。

### 转发实现要点(已勘察定论)

- **HTTP 客户端**:Apache HttpClient 4.x(base pom 已有);`connectTimeout=10s`、`socketTimeout=120s`(问渠 SSE 每 15s 有 keepalive 注释帧,120s 读超时安全)。
- **SSE 流式**:upstream `InputStream` → `response.getOutputStream()` 逐块拷贝,**每块 flush + `response.flushBuffer()`**(容器 8KB 缓冲不逐块推就会攒帧);状态码/Content-Type 透传;不用 SseEmitter/async,`mvc.async.request-timeout=10000` 不生效,无需改超时配置;同步阻塞转发占 1 个 servlet 线程/活跃轮,内部工具规模可接受。
- **multipart 特例**(仅 attachment 一个端点):Boot 会在 DispatcherServlet 把 multipart parts 解析掉、原始流已耗尽——走 `request.getPart("file")`+`getPart("visitorId")` 用 httpmime 重建上游 multipart;其余 JSON body `request.getInputStream()` 原样字节转发(已确认 XssHttpServletRequestWrapper 只包 getParameter,不碰 getInputStream)。
- **下游 abort 传导**:前端停/断开 → 拷贝循环抛 IOException → finally 关上游连接,等效今天浏览器直连 abort。
- token 不进日志、不回显;明文存 DB(与 dtbd 库同信任域,注明)。

### 配置

`wenqu.proxy.base-url` 加进两份 application-dev.yml(`wenqu.mcp.service-token` 同段);docker-compose 的 `SPRING_APPLICATION_JSON` 可留默认。

## 二、dtbd 前端(dynamic-form-vue)

### [formCopilot.js](/Users/yuki/IdeaProjects/dynamic-form-vue/src/api/formCopilot.js)

- `BASE` → `'/api/copilot'`(dev 走 vue.config.js 现有 `/api`→`127.0.0.1:8080/dtbd` 代理);头注释同步改。
- 删 `TOKEN_KEY`/localStorage token 逻辑与 `VUE_APP_WENQU_TOKEN` 兜底;`tokenScope()` 改为读模块级 `wenquScope`(新增 `setWenquScope()`,由 config 接口喂)——分格语义不变,且服务端哈希同构,**存量会话映射无缝**。
- 新增 `fetchCopilotConfig()` / `saveCopilotToken(token)`(GET/PUT `/config`)。
- 9 个调用函数全部去掉 URL 里 token 段,统一加 **`X-Token` 头**(import store 取 `getters.token`,与 request.js 同源同模式)——/api/** 全局强制鉴权必须。
- **401 语义细分**:dtbd 401 是 HTTP 200 信封 `{code:401}`,fetch 层识别后按「登录过期」提示(引导重登);仅 404 才回未连接卡。现有 loadInfo 的 `e.status` 判断相应调整。

### [FormCopilot.vue](/Users/yuki/IdeaProjects/dynamic-form-vue/src/views/form/copilot/FormCopilot.vue)

- `refreshConn()` 改调 `fetchCopilotConfig()`:configured→connected,scope 喂 formCopilot,masked 供设置卡展示;
- `saveToken()` 改调 `saveCopilotToken()`,成功后既有作废/清状态/loadInfo/ensureCatalogs/maybeRestoreHistory 链路不动;对比逻辑改为「scope 是否变化」;
- 一次性迁移:mounted 时 localStorage 仍有 `wenqu-copilot-token` 且服务端未配置 → 自动 PUT 上去并 removeItem(老用户无感切 B 档);
- 连接卡/设置卡文案:placeholder 改「粘贴问渠分享 token(提交到本系统后端保管,浏览器不再保存)」。

### 其他

- `scripts/shoot-copilot.mjs`:localStorage 写 token 改为先调 PUT /api/copilot/config(实现时确认脚本 dtbd 登录态)。
- vue.config.js `/wenqu` 代理**保留**(图片/artifact 仍直连)。

## 三、wenqu

零改动。部署注记:dtbd 后端 → 问渠必须走回环/私网(docker 同 `wenqu_default` 网络/本地 127),XFF 末段才被 ClientIp 采信;nginx 层原来给 `/wenqu/api/ai` 加的 CORS 可顺势收窄(ops 侧,不急)。

## Verification(本地栈 8000→8080→8090)

1. 起 dtbd 后端,actable 自动建 `c_copilot_wenqu_config` 表;
2. 真机(iab/浏览器)走查:未配置→连接卡→粘 token→能力位加载;**localStorage 检查无 `wenqu-copilot-token`**;老 token 自动迁移后会话历史仍能恢复(哈希同构验证);
3. 对话链路:流式逐帧(不攒帧)、done、停止/abort 上游即断(看 8090 日志轮终止)、清空会话、换 token 分格重置;
4. 附件上传 + dataURL 发图 + 历史图片灯箱(直连链路);
5. 越权面:未登录裸打 `/api/copilot/share/chat` → 401 信封;配置接口永不回 token 原文;
6. 问渠侧:审计/限频 IP = XFF 末段真实客户端(而非丢头落代理 IP);问渠原生分享页冒烟(零改动应无回归)。

## 提交(中文 conventional commit,各自仓库)

- dynamic-form:`feat(web): 表单助手问渠代理——token 服务端保管+share 端点族流式转发`
- dynamic-form-vue(dev 分支):`feat(copilot): 对接后端代理——浏览器不再持有问渠 token`
- wenqu:无。提交后 `git show --stat` 核对无并行会话 hunk 卷入。