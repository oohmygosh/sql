<script setup>
import { computed } from 'vue';
import { icons } from '../icons.js';
const props = defineProps({ name: { type: String, required: true } });
const shapes = computed(() => icons[props.name] || icons.code);
</script>
<template>
  <span :data-icon="name"
    ><svg class="icon" viewBox="0 0 24 24" aria-hidden="true">
      <component
        :is="shape.tag"
        v-for="(shape, index) in shapes"
        :key="index"
        v-bind="shape.attrs"
      /></svg
  ></span>
</template>
