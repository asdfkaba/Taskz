<script setup>
import { onBeforeUnmount, ref } from 'vue'
import Button from 'primevue/button'

// Board, compact board or table, as one button group. Compact sits next to Board, as it is the
// board with smaller cards; the table has no compact form. See useBoardViewPrefs.
// Labels only where the header has room for them; below that the buttons are icons.
const props = defineProps({
  mode: { type: String, required: true }, // 'board' | 'compact' | 'table'
  // From which width the labels show. Default: Tailwind's xl, as the board header also holds
  // Settings, Add column and New task; shared boards have room earlier.
  labelsFrom: { type: String, default: '80rem' },
})
const emit = defineEmits(['update:mode'])

const MODES = [
  { value: 'board', label: 'Board', icon: 'pi pi-th-large', name: 'Board view' },
  { value: 'compact', label: 'Compact', icon: 'pi pi-list', name: 'Compact board view' },
  { value: 'table', label: 'Table', icon: 'pi pi-table', name: 'Table view' },
]

const wideQuery = window.matchMedia(`(min-width: ${props.labelsFrom})`)
const wide = ref(wideQuery.matches)
const onChange = (event) => (wide.value = event.matches)
wideQuery.addEventListener('change', onChange)
onBeforeUnmount(() => wideQuery.removeEventListener('change', onChange))
</script>

<template>
  <div role="group" aria-label="View" class="flex">
    <Button
      v-for="(option, index) in MODES"
      :key="option.value"
      :icon="option.icon"
      :label="wide ? option.label : undefined"
      :severity="mode === option.value ? undefined : 'secondary'"
      :outlined="mode !== option.value"
      :aria-label="option.name"
      :title="option.name"
      :aria-pressed="mode === option.value"
      :class="[index > 0 && '-ml-px !rounded-l-none', index < MODES.length - 1 && '!rounded-r-none']"
      @click="emit('update:mode', option.value)"
    />
  </div>
</template>
