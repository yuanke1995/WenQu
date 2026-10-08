<template>
  <div class="app-page">
    <div class="app-page-head">
      <h3 class="app-page-title">帮助中心</h3>
      <span class="head-hint-plain" title="官方使用手册（随版本自动同步）；也可以在对话里直接提问，答案会从本手册检索并带引用">官方使用手册（随版本自动同步）；也可以在对话里直接提问，答案会从本手册检索并带引用</span>
    </div>
    <div class="app-page-body">
      <HelpCenter />
    </div>
  </div>
</template>

<script setup>
import HelpCenter from '../components/HelpCenter.vue'
</script>
