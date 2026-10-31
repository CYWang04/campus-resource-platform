<script setup lang="ts">
import { ref } from "vue";
const props = defineProps<{ refresh: () => Promise<unknown> }>();
const refreshing = ref(false);
async function run() {
  if (refreshing.value) return;
  refreshing.value = true;
  try {
    // Keep the indicator readable even when the local preview responds instantly.
    await Promise.allSettled([
      Promise.resolve().then(props.refresh),
      new Promise((resolve) => setTimeout(resolve, 550)),
    ]);
  } finally {
    refreshing.value = false;
  }
}
</script>
<template>
  <el-button
    class="refresh-button"
    :disabled="refreshing"
    :aria-busy="refreshing"
    @click="run"
  >
    <span
      class="refresh-icon"
      :class="{ 'is-refreshing': refreshing }"
      aria-hidden="true"
      >↻</span
    >
    <span><slot>刷新</slot></span>
    <span class="sr-only" role="status">{{
      refreshing ? "正在刷新" : ""
    }}</span>
  </el-button>
</template>
