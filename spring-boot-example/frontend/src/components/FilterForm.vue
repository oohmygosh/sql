<script setup>
import { reactive, computed } from 'vue';
import { parseFilterValue } from '../search-context.js';
const props = defineProps({ fields: Array });
const emit = defineEmits(['apply', 'cancel']);
const form = reactive({ field: '', operator: 'eq', type: 'auto', value: '', error: '' });
const nullOperator = computed(() => ['is_null', 'is_not_null'].includes(form.operator));
const operators = {
  eq: '等于',
  ne: '不等于',
  gt: '大于',
  gte: '大于等于',
  lt: '小于',
  lte: '小于等于',
  in: '属于集合（JSON 数组）',
  contains: '字符串包含',
  is_null: '为 null',
  is_not_null: '不为 null',
};
function submit() {
  try {
    const field = form.field.trim();
    if (!field) throw new Error('请填写源字段名。');
    const type =
      form.type === 'auto'
        ? props.fields.find((item) => item.name === field)?.type || 'string'
        : form.type;
    const value = parseFilterValue(form.value, type, form.operator);
    emit('apply', { field, operator: form.operator, ...(value === undefined ? {} : { value }) });
  } catch (error) {
    form.error = error.message;
  }
}
</script>
<template>
  <form id="filter-form" class="modal-form" @submit.prevent="submit">
    <p class="modal-note">
      条件在源数据读取后、用户语句计算和聚合前按 AND 生效。请填写源表字段；结果别名不能作为源字段。
    </p>
    <label
      >源字段<input
        id="filter-field"
        v-model="form.field"
        list="source-field-options"
        required
        autocomplete="off"
      /><datalist id="source-field-options">
        <option v-for="field in fields" :key="field.name" :value="field.name">
          {{ field.type }}
        </option>
      </datalist></label
    ><label
      >值类型<select id="filter-value-type" v-model="form.type">
        <option value="auto">使用源字段配置（未配置按字符串）</option>
        <option value="string">字符串</option>
        <option value="number">数字</option>
        <option value="boolean">布尔值</option>
      </select></label
    ><label
      >条件<select id="filter-op" v-model="form.operator">
        <option v-for="(label, operator) in operators" :key="operator" :value="operator">
          {{ label }}
        </option>
      </select></label
    ><label
      >值<input
        id="filter-value"
        v-model="form.value"
        :disabled="nullOperator"
        autocomplete="off"
        placeholder="按源字段类型填写"
    /></label>
    <p id="filter-error" class="modal-note" role="alert">{{ form.error }}</p>
    <div class="modal-actions">
      <button type="button" class="button secondary" @click="emit('cancel')">取消</button
      ><button class="button primary">应用筛选</button>
    </div>
  </form>
</template>
