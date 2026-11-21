import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import Components from 'unplugin-vue-components/vite'
import { AntDesignVueResolver } from 'unplugin-vue-components/resolvers'

export default defineConfig({
  plugins: [
    vue(),
    // antd 按需：模板里的 a-xxx 编译期解析成具名导入，配合 antd-vue 的
    // sideEffects 声明让生产构建摇掉未用组件（main.js 不再 app.use(Antd) 全量注册）。
    // importStyle:false —— antd-vue 4 是 css-in-js，样式随组件 JS 走，全局只引 reset.css。
    // dts:false —— 本项目是 JS，不生成 components.d.ts 声明文件。
    Components({ resolvers: [AntDesignVueResolver({ importStyle: false })], dts: false })
  ],
  // echarts / zrender 只允许一份实例：依赖预打包会按入口拆成多份内部模块，
  // 完整包与 echarts/core 混用时重复注册 axisPointer 会抛「CartesianAxisPointer exists」。
  resolve: {
    dedupe: ['echarts', 'zrender']
  },
  build: {
    rollupOptions: {
      output: {
        // 分块只固定框架层（vue/vue-router）：业务迭代不打断它们的浏览器缓存。
        //
        // 刻意不再把 antd / icons 整体并进一个 vendor 块。此前那样做时，所有懒加载页面
        // 用到的 34 个组件族被合并进同一块，而入口（App.vue 的 ConfigProvider、若干共享
        // utils 的 message）只要碰一个 antd API，index.html 就会 modulepreload 整块——
        // H5 首屏因此多下载 344KB gz 的用不上的组件。现在交给 rollup 按真实引用关系拆：
        // 入口只含它真正 import 的 antd 模块，其余随各自路由 chunk 首次用到时才下载。
        manualChunks (id) {
          if (!id.includes('node_modules')) return
          if (/node_modules\/(@vue|vue|vue-router|@vueuse)\//.test(id)) return 'vue-vendor'
        }
      }
    }
  },
  server: {
    port: 5800,
    strictPort: true,
    // 监听 0.0.0.0，允许局域网内其他设备用内网 IP 访问（如 http://192.168.31.x:5800）
    host: true,
    proxy: {
      // API 专用：VITE_API_PROXY_TARGET 设了独立根域时（dev:prod），让 /proxy/api/**
      // 剥掉 /proxy 后走 /api/**（线上 nginx /api/ 关了 proxy_buffering，SSE 实时）；
      // 未设时回落到 VITE_PROXY_TARGET，与下面 /proxy 行为等价，本地 dev 不受影响。
      // 必须排在 /proxy 之前：vite 按对象键顺序匹配，/proxy 会吞掉全部 /proxy/**。
      '/proxy/api': {
        target: process.env.VITE_API_PROXY_TARGET || process.env.VITE_PROXY_TARGET || 'http://localhost:8090/ai',
        changeOrigin: true,
        ws: true,
        rewrite: path => path.replace(/^\/proxy/, '')
      },
      // 其余 /proxy/**（媒体 /images、/artifacts 等）转发到 AI 服务
      '/proxy': {
        // 后端地址：默认 8090；8090 被本机代理占用、后端改跑 8091 时，用 VITE_PROXY_TARGET 覆盖
        // （启动时：VITE_PROXY_TARGET=http://localhost:8091/ai npm run dev）
        target: process.env.VITE_PROXY_TARGET || 'http://localhost:8090/ai',
        changeOrigin: true,
        ws: true,
        rewrite: path => path.replace(/^\/proxy/, '')
      },
      // 对接文档里的 curl 用「origin + /ai」作基址（线上 dist 与后端同域，正是这个形状），
      // 分享/邀请/MCP 链接里的对外地址也是这么来的。dev 不转发的话，本地打开文档复制出来的
      // curl 会命中 SPA 回退、拿到 index.html（200 + text/html，比 404 更难察觉）。
      // 所以这里同样把 /ai 透到后端：本地复制即可直接跑。
      // 约定与上面一致——VITE_PROXY_TARGET 要含上下文路径（默认 .../ai）。
      '/ai': {
        target: process.env.VITE_PROXY_TARGET || 'http://localhost:8090/ai',
        changeOrigin: true,
        rewrite: path => path.replace(/^\/ai/, '')
      }
    }
  }
})
