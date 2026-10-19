<script setup>
import { computed } from 'vue';
import Icon from './Icon.vue';
const props = defineProps({
  chart: Object,
  visible: Boolean,
  elapsed: String,
  warning: String,
  busy: Boolean,
  disabled: Boolean,
  timeZone: String,
});
const emit = defineEmits(['toggle', 'bucket', 'cancel']);
const supported = computed(() => props.chart?.status === 'ok');
const ceiling = computed(() =>
  Math.max(1, ...(props.chart?.buckets || []).map((bucket) => Number(bucket.count))),
);
const reason = computed(() =>
  props.busy
    ? '正在加载时间分布，明细数据可独立浏览。'
    : props.chart
      ? props.chart.reason ||
        props.chart.message ||
        (props.chart.status === 'unsupported'
          ? '当前查询不支持自动生成时间分布。'
          : '时间分布执行失败。')
      : '执行查询后显示时间分布；自动图表需要有限时间范围。',
);
const labels = computed(() => {
  const buckets = props.chart?.buckets || [];
  return buckets.length
    ? [
        buckets[0].from,
        ...[0.25, 0.5, 0.75].map(
          (fraction) =>
            buckets[Math.min(buckets.length - 1, Math.floor(buckets.length * fraction))].from,
        ),
        buckets.at(-1).to,
      ]
    : [];
});
function formatTime(value) {
  try {
    return new Date(value).toLocaleString('zh-CN', {
      timeZone: props.chart?.timeZone || props.timeZone,
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      hour12: false,
    });
  } catch {
    return String(value);
  }
}
</script>
<template>
  <section class="overview-card" aria-label="搜索时间分布" :aria-busy="busy">
    <div class="overview-top">
      <div class="histogram-status">
        <strong>时间分布</strong>
        <span id="histogram-elapsed" role="status" aria-live="polite">{{ elapsed }}</span>
      </div>
      <button
        v-if="busy"
        class="text-button"
        aria-label="停止加载时间分布"
        @click="emit('cancel')"
      >
        <Icon name="x" />停止加载
      </button>
      <button
        class="icon-button"
        :aria-label="visible ? '收起趋势图' : '展开趋势图'"
        :aria-expanded="visible"
        @click="emit('toggle')"
      >
        <Icon :name="visible ? 'chevron-up' : 'chevron-down'" />
      </button>
    </div>
    <div id="histogram-warning" class="query-warning" role="status" :hidden="!warning">
      <strong>时间分布提示</strong> · {{ warning }}
    </div>
    <p
      id="chart-empty"
      class="chart-empty"
      :class="{ 'chart-error': !busy && chart?.status === 'error' }"
      :hidden="(supported && !busy) || !visible"
    >
      {{ reason }}
    </p>
    <div id="chart-area" :hidden="!supported || !visible || busy">
      <div class="chart-head">
        <span>查询时间分布</span
        ><span id="chart-granularity">{{
          supported ? `${chart.interval} · ${chart.timeZone} · 后台聚合` : ''
        }}</span>
      </div>
      <div class="chart-body">
        <div class="chart-scale">
          <span id="chart-max">{{ ceiling.toLocaleString() }}</span
          ><span id="chart-mid">{{ Math.floor(ceiling / 2).toLocaleString() }}</span
          ><span>0</span>
        </div>
        <div
          id="histogram"
          class="histogram"
          role="group"
          aria-label="查询时间分布，点击缩小时间范围"
        >
          <button
            v-for="(bucket, index) in chart?.buckets || []"
            :key="index"
            class="chart-bar"
            :disabled="busy || disabled"
            :title="`${formatTime(bucket.from)} – ${formatTime(bucket.to)} · ${bucket.count} 条`"
            :aria-label="`查询 ${formatTime(bucket.from)} 至 ${formatTime(bucket.to)}`"
            @click="emit('bucket', bucket)"
          >
            <i class="info" :style="{ height: `${(Number(bucket.count) / ceiling) * 100}%` }"></i>
          </button>
        </div>
      </div>
      <div id="chart-axis" class="chart-axis">
        <span v-for="(value, index) in labels" :key="index">{{ formatTime(value) }}</span>
      </div>
    </div>
  </section>
</template>
