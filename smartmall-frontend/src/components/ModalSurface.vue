<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'

const props = withDefaults(defineProps<{
  open: boolean
  labelledby: string
  drawer?: boolean
  dismissible?: boolean
}>(), { drawer: false, dismissible: true })
const emit = defineEmits<{ dismiss: [] }>()
const element = ref<HTMLDialogElement | null>(null)

function syncOpen() {
  if (props.open && !element.value?.open) element.value?.showModal()
  else if (!props.open && element.value?.open) element.value.close()
}
function cancel(event: Event) {
  event.preventDefault()
  if (props.dismissible) emit('dismiss')
}
function backdrop(event: MouseEvent) {
  if (!props.dismissible || !element.value || event.target !== element.value) return
  const bounds = element.value.getBoundingClientRect()
  if (event.clientX < bounds.left || event.clientX > bounds.right ||
      event.clientY < bounds.top || event.clientY > bounds.bottom) emit('dismiss')
}
watch(() => props.open, syncOpen, { flush: 'post' })
onMounted(syncOpen)
</script>

<template>
  <dialog ref="element" :class="['modal-surface', { drawer }]" :aria-labelledby="labelledby"
          @cancel="cancel" @click="backdrop">
    <slot />
  </dialog>
</template>

<style scoped>
.modal-surface { width: min(420px, calc(100vw - 36px)); max-height: calc(100dvh - 36px); padding: 32px; border: 1px solid #e7e7e7; border-radius: 22px; color: var(--ink); background: white; box-shadow: 0 24px 100px #00000018; overscroll-behavior: contain; }
.modal-surface::backdrop { background: #20232740; backdrop-filter: blur(4px); }
.drawer { position: fixed; inset: 0 0 0 auto; width: min(500px, 100vw); max-width: 100vw; height: 100dvh; max-height: 100dvh; margin: 0; padding: 24px; border: 0; border-left: 1px solid var(--line); border-radius: 0; box-shadow: -16px 0 60px #0000000d; }
.drawer::backdrop { background: #20232720; backdrop-filter: none; }
@media (max-width: 480px) { .modal-surface:not(.drawer) { padding: 26px; } .drawer { padding: 20px; } }
</style>
