<template>
  <span class="tri-wrap">
    <button v-if="modelValue === 'deny'" type="button" class="exempt-chip"
            :class="{ on: exempt === 1 }" :disabled="disabled"
            :title="exempt === 1 ? '只压本角色自己的授权：其它角色（或管理员级）仍可用这个动作' : '只在本角色内生效'"
            @click="$emit('update:exempt', exempt === 1 ? 0 : 1)">
      {{ exempt === 1 ? '仅本角色内生效' : '排除范围：全员' }}
    </button>
    <span class="tri" role="group" :aria-label="'授权点状态'">
      <button type="button" :class="{ 'on-allow': modelValue === 'allow' }" :disabled="disabled"
              :aria-pressed="modelValue === 'allow'" @click="$emit('update:modelValue', 'allow')">允许</button>
      <button type="button" :class="{ 'on-deny': modelValue === 'deny' }" :disabled="disabled"
              :aria-pressed="modelValue === 'deny'" @click="$emit('update:modelValue', 'deny')">禁止</button>
      <button type="button" :class="{ 'on-none': modelValue === 'none' }" :disabled="disabled"
              :aria-pressed="modelValue === 'none'" @click="$emit('update:modelValue', 'none')">未置</button>
    </span>
  </span>
</template>

<script setup>
defineProps({
  modelValue: { type: String, default: 'none' },
  exempt: { type: Number, default: 0 },
  disabled: { type: Boolean, default: false }
})
defineEmits(['update:modelValue', 'update:exempt'])
</script>

<style scoped>
.tri-wrap { display: inline-flex; align-items: center; gap: 6px; flex: none; }
.tri { display: inline-flex; border: 1px solid var(--app-border); border-radius: 6px; overflow: hidden; height: 24px; background: var(--app-panel); }
.tri button {
  border: 0; background: transparent; cursor: pointer; font-family: inherit;
  font-size: 11px; padding: 0 8px; color: var(--app-text3); white-space: nowrap;
  transition: background .12s, color .12s;
}
.tri button + button { border-left: 1px solid var(--app-border); }
.tri button:hover:not(:disabled) { background: var(--app-panel-2); color: var(--app-text2); }
.tri button.on-allow { background: var(--app-ok-weak); color: var(--app-ok); font-weight: 600; }
.tri button.on-deny { background: var(--app-danger-weak); color: var(--app-danger); font-weight: 600; }
.tri button.on-none { background: var(--app-panel-2); color: var(--app-text2); font-weight: 600; }
.tri button:disabled { cursor: not-allowed; opacity: .55; }
.tri button:focus-visible { outline: 2px solid var(--app-accent); outline-offset: -2px; }
.exempt-chip {
  font-family: inherit; font-size: 10px; line-height: 20px; height: 22px; padding: 0 7px;
  border: 1px solid var(--app-border); border-radius: 999px; cursor: pointer;
  background: var(--app-panel-2); color: var(--app-text3);
}
.exempt-chip.on { background: var(--app-warn-weak); border-color: var(--app-warn-border); color: var(--app-warn-text); }
.exempt-chip:disabled { cursor: not-allowed; opacity: .55; }
</style>
