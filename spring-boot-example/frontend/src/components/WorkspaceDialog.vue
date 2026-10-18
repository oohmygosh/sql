<script setup>
import { ref, watch, onBeforeUnmount } from 'vue';
import Icon from './Icon.vue';
const props = defineProps({ open: Boolean, title: String, drawer: Boolean });
const emit = defineEmits(['close']);
const dialog = ref(null);
// Native dialog supplies modal focus management; its contents remain Vue templates.
watch(
  [() => props.open, dialog],
  ([open]) => {
    if (!dialog.value) return;
    if (open && !dialog.value.open) dialog.value.showModal();
    else if (!open && dialog.value.open) dialog.value.close();
  },
  { flush: 'post', immediate: true },
);
function backdrop(event) {
  if (event.target !== dialog.value) return;
  const rect = dialog.value.getBoundingClientRect();
  if (
    event.clientX < rect.left ||
    event.clientX > rect.right ||
    event.clientY < rect.top ||
    event.clientY > rect.bottom
  )
    emit('close');
}
onBeforeUnmount(() => dialog.value?.close());
</script>
<template>
  <dialog
    ref="dialog"
    :class="{ 'detail-drawer': drawer }"
    :aria-label="title"
    @cancel.prevent="emit('close')"
    @click="backdrop"
    @close="!dialog.open && emit('close')"
  >
    <div :class="drawer ? 'drawer-heading' : 'modal-heading'">
      <div>
        <span class="modal-eyebrow">{{ drawer ? 'RECORD DETAIL' : 'LENS WORKSPACE' }}</span>
        <h2>{{ title }}</h2>
      </div>
      <button
        type="button"
        class="icon-button"
        :aria-label="drawer ? '关闭详情' : '关闭弹窗'"
        @click="emit('close')"
      >
        <Icon name="x" />
      </button>
    </div>
    <slot />
  </dialog>
</template>
