<script setup>
import { computed, ref, watch } from 'vue';
import Icon from './Icon.vue';
import FieldItem from './FieldItem.vue';
const props = defineProps({ state: Object, columns: Array, layout: Object, canFilter: Boolean });
const emit = defineEmits(['column', 'expand', 'filter', 'all', 'reset']);
const metadataOpen = ref(false);
const matches = computed(() =>
  props.state.schema
    .map((field, index) => ({ field, index }))
    .filter(({ field }) =>
      field.name.toLowerCase().includes(props.state.fieldSearch.toLowerCase()),
    ),
);
const metadata = computed(() =>
  props.layout
    ? matches.value.filter((item) => props.layout.metadataIndexes.includes(item.index))
    : [],
);
const ordinary = computed(() =>
  matches.value.filter((item) => !props.layout?.metadataIndexes.includes(item.index)),
);
watch(
  () => [props.state.fieldSearch, props.state.activeField],
  () => {
    if (
      props.state.fieldSearch ||
      metadata.value.some((item) => item.index === props.state.activeField)
    )
      metadataOpen.value = true;
  },
);
function bindings(item) {
  return {
    field: item.field,
    index: item.index,
    selected: props.columns.includes(item.index),
    lastColumn: props.columns.length === 1,
    expanded: props.state.activeField === item.index,
    rows: props.state.rows,
    canFilter:
      props.canFilter &&
      props.state.schema.filter(
        (field) => field.name.toLowerCase() === item.field.name.toLowerCase(),
      ).length === 1,
    dirty: props.state.dirty,
    busy: props.state.busy,
  };
}
</script>
<template>
  <aside id="fields-panel" class="fields-panel" aria-label="字段探索">
    <div class="field-panel-title">
      <div class="field-panel-heading">
        <strong>字段</strong
        ><span
          id="field-count"
          :title="`${state.schema.length} 个字段 · ${layout && state.visibleColumns === null ? '默认日志视图' : `显示 ${columns.length} 列`}`"
          >{{ state.schema.length }}</span
        >
      </div>
      <div class="field-column-controls">
        <button
          type="button"
          aria-label="显示全部列"
          title="显示全部列"
          :disabled="!state.schema.length || columns.length === state.schema.length"
          @click="emit('all')"
        >
          全部
        </button>
        <button
          type="button"
          aria-label="恢复默认列"
          title="恢复默认列"
          :disabled="!state.schema.length || state.visibleColumns === null"
          @click="emit('reset')"
        >
          默认
        </button>
      </div>
    </div>
    <div class="field-search">
      <Icon name="search" /><input
        id="field-search"
        v-model="state.fieldSearch"
        placeholder="查找字段"
        aria-label="查找字段"
      />
    </div>
    <div id="field-list">
      <p v-if="!state.schema.length" class="field-empty">暂无字段</p>
      <p v-else-if="!matches.length" class="field-empty">没有匹配的字段</p>
      <FieldItem
        v-for="item in ordinary"
        :key="item.index"
        v-bind="bindings(item)"
        @column="emit('column', $event)"
        @expand="emit('expand', $event)"
        @filter="(field, value, exclude) => emit('filter', field, value, exclude)"
      />
      <details
        v-if="metadata.length"
        class="metadata-field-list"
        :open="metadataOpen"
        @toggle="metadataOpen = $event.target.open"
      >
        <summary>
          采集元数据 <span>{{ metadata.length }}</span>
        </summary>
        <FieldItem
          v-for="item in metadata"
          :key="item.index"
          v-bind="bindings(item)"
          @column="emit('column', $event)"
          @expand="emit('expand', $event)"
          @filter="(field, value, exclude) => emit('filter', field, value, exclude)"
        />
      </details>
    </div>
  </aside>
</template>
