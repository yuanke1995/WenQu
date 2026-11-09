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
      // 前端调 /proxy/**，转发到 AI 服务 http://localhost:8090/ai/**
      '/proxy': {
        // 后端地址：默认 8090；8090 被本机代理占用、后端改跑 8091 时，用 VITE_PROXY_TARGET 覆盖
        // （启动时：VITE_PROXY_TARGET=http://localhost:8091/ai npm run dev）
        target: process.env.VITE_PROXY_TARGET || 'http://localhost:8090/ai',
        changeOrigin: true,
        rewrite: path => path.replace(/^\/proxy/, '')
      }
    }
  }
})
