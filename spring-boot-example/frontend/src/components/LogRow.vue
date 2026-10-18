<script setup>
import { computed } from 'vue';
import Icon from './Icon.vue';
import { extractedEntries } from '../log-results.js';
import { displayValue } from '../results.js';
import { jsonText } from '../api.js';
const props = defineProps({ row: Object, layout: Object, expanded: Boolean, number: Number });
const emit = defineEmits(['toggle', 'detail']);
const extracted = computed(() => props.row.values[props.layout.extractedIndex]);
const entries = computed(() => extractedEntries(extracted.value));
const log = computed(() => {
  const parsed = Object.fromEntries(entries.value),
    values = props.row.values;
  const present = (value) => value !== null && value !== undefined && value !== '';
  const pick = (index, ...keys) =>
    present(values[index]) ? values[index] : keys.map((key) => parsed[key]).find(present);
  const message = pick(props.layout.bodyIndex, 'message', 'msg', 'content', 'log');
  const time = present(values[props.layout.timeIndex])
    ? displayValue(values[props.layout.timeIndex])
    : '';
  return {
    time,
    parts: /^(\d{4}-\d{2}-\d{2})[T ](.+)$/.exec(time),
    hasMessage: present(message) || entries.value.length > 0,
    message: present(message)
      ? displayValue(message)
      : entries.value.length
        ? entries.value
            .slice(0, 3)
            .map(([key, value]) => `${key}=${displayValue(value)}`)
            .join('  ·  ')
        : '此记录没有日志正文',
    level: String(pick(props.layout.levelIndex, 'level', 'severity') || '').toUpperCase(),
    source: pick(props.layout.sourceIndex, 'service', 'service_name', 'source'),
  };
});
const levelClass = computed(() =>
  ['INFO', 'WARN', 'ERROR'].includes(log.value.level) ? log.value.level : 'log-level-other',
);
const raw = computed(() =>
  extracted.value !== null && extracted.value !== undefined && jsonText(extracted.value) !== '{}'
    ? jsonText(extracted.value, null, 2)
    : '',
);
</script>
<template>
  <tr
    class="result-row log-row"
    :class="{ expanded }"
    tabindex="0"
    :data-row-id="row.index"
    data-log-row
    :aria-expanded="expanded"
    :aria-controls="`extracted-row-${row.index}`"
    :aria-label="`${expanded ? '收起' : '展开'}第 ${number} 条日志的 extracted`"
    @click="emit('toggle', row.index)"
    @keydown.enter.self.prevent="emit('toggle', row.index)"
    @keydown.space.self.prevent="emit('toggle', row.index)"
  >
    <td>
      <button
        type="button"
        class="log-expand"
        :data-expand-row="row.index"
        :aria-expanded="expanded"
        :aria-controls="`extracted-row-${row.index}`"
        :aria-label="`${expanded ? '收起' : '展开'} extracted，第 ${number} 条日志`"
        @click.stop="emit('toggle', row.index)"
      >
        <Icon name="chevron-right" />
      </button>
    </td>
    <td class="log-time" :title="log.time">
      <template v-if="log.parts"
        ><span>{{ log.parts[1] }}</span
        ><strong>{{ log.parts[2] }}</strong></template
      ><template v-else>{{ log.time || '—' }}</template>
    </td>
    <td class="log-content">
      <div class="log-message" :class="{ 'log-message-empty': !log.hasMessage }">
        {{ log.message }}
      </div>
      <div class="log-context">
        <span v-if="log.level && log.level !== 'UNKNOWN'" class="level-badge" :class="levelClass">{{
          log.level
        }}</span
        ><span
          v-if="log.source !== undefined && log.source !== null && log.source !== ''"
          class="log-source"
          :title="displayValue(log.source)"
          >{{ displayValue(log.source) }}</span
        ><span class="log-extracted-count"
          ><Icon name="code" />extracted<span>{{ entries.length }}</span></span
        >
      </div>
    </td>
  </tr>
  <tr
    v-if="expanded"
    :id="`extracted-row-${row.index}`"
    class="log-expanded-row"
    :data-expanded-row="row.index"
  >
    <td colspan="3">
      <section class="extracted-panel" :aria-label="`第 ${number} 条日志的 extracted`">
        <div class="extracted-heading">
          <span
            ><Icon name="code" /><strong>extracted</strong
            ><span class="extracted-field-count">{{ entries.length }} 个解析字段</span></span
          ><button
            type="button"
            class="text-button"
            :data-record-detail="row.index"
            @click="emit('detail', row.index)"
          >
            全部字段<Icon name="arrow-up-right" />
          </button>
        </div>
        <dl v-if="entries.length" class="extracted-fields">
          <div v-for="[key, value] in entries" :key="key" class="extracted-field">
            <dt>{{ key }}</dt>
            <dd :class="{ 'null-value': value === null }">
              {{
                typeof value === 'object' && value !== null
                  ? jsonText(value, null, 2)
                  : displayValue(value)
              }}
            </dd>
          </div>
        </dl>
        <pre v-else-if="raw" class="extracted-raw">{{ raw }}</pre>
        <p v-else class="extracted-empty">暂无提取字段</p>
      </section>
    </td>
  </tr>
</template>
