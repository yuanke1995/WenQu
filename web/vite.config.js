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
        // 稳定的 vendor 分块：业务代码迭代不打断框架/组件库的浏览器缓存。
        // antd 块只含 tree-shake 后真正被引用的部分（约 34 个组件族）。
        manualChunks: {
          'vue-vendor': ['vue', 'vue-router'],
          'antd-vendor': ['ant-design-vue', '@ant-design/icons-vue']
        }
      }
    }
  },
  server: {
    port: 5800,
    strictPort: true,
    proxy: {
      // 前端调 /proxy/**，转发到 AI 服务 http://localhost:8090/ai/**
      '/proxy': {
        target: 'http://localhost:8090/ai',
        changeOrigin: true,
        rewrite: path => path.replace(/^\/proxy/, '')
      }
    }
  }
})
