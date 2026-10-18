<script setup>
import { computed } from 'vue';
import Icon from './Icon.vue';
import { fieldValues, quickValueFilter } from '../fields.js';
import { displayValue } from '../results.js';
const props = defineProps({
  field: Object,
  index: Number,
  selected: Boolean,
  lastColumn: Boolean,
  expanded: Boolean,
  rows: Array,
  canFilter: Boolean,
  dirty: Boolean,
  busy: Boolean,
});
const emit = defineEmits(['column', 'expand', 'filter']);
const type = computed(() =>
  /int|long|double|float|decimal|number|numeric/.test(props.field.type)
    ? '#'
    : /object|struct|array|map/.test(props.field.type)
      ? '{}'
      : 'Aa',
);
const sample = computed(() => fieldValues(props.rows, props.index));
const values = computed(() =>
  sample.value.values.map((item) => {
    const label = item.value === '' ? '""（空字符串）' : displayValue(item.value);
    let reason = !props.canFilter
      ? '快捷筛选适用于直接读取源表的查询；其他结果请使用源字段筛选。'
      : '';
    try {
      quickValueFilter(props.field.name, item.value);
    } catch (error) {
      reason = error.message;
    }
    return { ...item, label, reason };
  }),
);
</script>
<template>
  <div class="explore-field" :class="{ 'is-open': expanded }">
    <div class="explore-field-row">
      <input
        type="checkbox"
        :data-column-index="index"
        :aria-label="`显示列 ${field.name}`"
        :title="lastColumn && selected ? '至少保留一列' : '在结果表格中显示此字段'"
        :checked="selected"
        :disabled="selected && lastColumn"
        @change="emit('column', index)"
      /><button
        type="button"
        class="field-item"
        :data-field-values="index"
        :aria-label="`查看 ${field.name} 的字段值`"
        :aria-expanded="expanded"
        :aria-controls="`field-values-${index}`"
        :title="`${field.name} · ${field.type} · 查看当前页字段值`"
        @click="emit('expand', index)"
      >
        <span class="field-type">{{ type }}</span
        ><span class="field-name">{{ field.name }}</span
        ><span><Icon :name="expanded ? 'chevron-down' : 'chevron-right'" /></span>
      </button>
    </div>
    <section
      v-if="expanded"
      :id="`field-values-${index}`"
      class="field-values"
      :aria-label="`${field.name} 的当前页字段值`"
    >
      <div class="field-values-heading" title="值与次数仅来自当前页；筛选会查询整个时间范围。">
        <span>当前页 · {{ rows.length }} 条</span>
      </div>
      <div v-for="(item, valueIndex) in values" :key="valueIndex" class="field-value-row">
        <code
          class="field-sample"
          :class="{ 'null-value': item.value === null }"
          :title="item.label"
          >{{ item.label }}</code
        ><span class="field-value-count" :title="`当前页出现 ${item.count} 次`">{{
          item.count
        }}</span
        ><button
          type="button"
          class="field-value-action"
          :data-quick-filter="index"
          :aria-label="`包含 ${field.name} = ${item.label}`"
          :title="item.reason || '包含此值，查询整个时间范围'"
          :disabled="!!item.reason || busy"
          @click="emit('filter', field.name, item.value, false)"
        >
          <Icon name="plus" /></button
        ><button
          type="button"
          class="field-value-action exclude"
          :aria-label="`排除 ${field.name} = ${item.label}`"
          :title="item.reason || '排除此值，查询整个时间范围'"
          :disabled="!!item.reason || busy"
          @click="emit('filter', field.name, item.value, true)"
        >
          <Icon name="minus" />
        </button>
      </div>
      <p v-if="!values.length" class="field-empty">当前页没有字段值</p>
      <p v-if="sample.total > values.length" class="field-value-note">
        前 {{ values.length }} / {{ sample.total }} 项
      </p>
      <p v-if="dirty" class="field-value-note">条件已修改，请先查询</p>
    </section>
  </div>
</template>
