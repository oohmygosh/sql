<script setup>
import { computed } from 'vue';
const props = defineProps({ manager: Object });
const task = computed(() => props.manager.state.task);
const labels = {
  PENDING: '等待执行',
  RUNNING: '正在导出',
  SUCCEEDED: '导出完成',
  FAILED: '导出失败',
  CANCELLED: '已取消',
  EXPIRED: '文件已过期',
  UNAVAILABLE: '无法访问',
};
</script>
<template>
  <div id="export-task">
    <div v-if="task" class="export-progress">
      <strong>{{ labels[task.state] || task.state }}</strong
      ><span
        >{{ task.exportedRows ?? 0 }} 行 ·
        {{ (Number(task.fileSizeBytes || 0) / 1048576).toFixed(2) }} MiB</span
      >
      <p class="modal-note">{{ task.source || '' }} · {{ task.query || '' }}</p>
      <p v-if="task.state === 'EXPIRED'" class="modal-note" role="status">
        导出文件已过期或被清理，请重新创建导出。
      </p>
      <p v-if="task.error" class="query-error">{{ task.error }}</p>
      <p v-if="task.pollError" class="query-error">{{ task.pollError }}</p>
      <a
        v-if="task.state === 'SUCCEEDED'"
        class="button primary"
        :href="manager.downloadHref.value"
        download="result.csv"
        >下载 CSV</a
      ><template v-if="manager.active.value"
        ><button id="resume-export" class="button secondary" @click="manager.poll">刷新进度</button
        ><button id="pause-export" class="button secondary" @click="manager.pause()">
          暂停刷新
        </button></template
      >
    </div>
  </div>
</template>
