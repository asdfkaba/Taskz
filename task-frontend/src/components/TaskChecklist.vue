<script setup>
import Checkbox from 'primevue/checkbox'

// The checklist of a task in the view. Ticking saves right away (the parent does); read-only on shared boards.
const props = defineProps({
  items: { type: Array, required: true }, // [{ id, text, checked }]
  canEdit: { type: Boolean, default: false },
  // A tick is being saved; further clicks wait.
  busy: { type: Boolean, default: false },
})
const emit = defineEmits(['toggle']) // (item)

const done = () => props.items.filter((i) => i.checked).length
</script>

<template>
  <section
    v-if="items.length"
    class="flex flex-col gap-2 border-t border-slate-200 pt-4 dark:border-slate-800"
    :class="{ 'opacity-60': busy }"
    :aria-busy="busy"
    aria-labelledby="task-checklist-title"
    data-testid="task-checklist"
  >
    <h3 id="task-checklist-title" class="text-sm font-medium text-slate-700 dark:text-slate-300">
      Checklist <span class="font-normal text-slate-400 dark:text-slate-500">{{ done() }}/{{ items.length }}</span>
    </h3>
    <ul class="flex flex-col gap-1.5">
      <li v-for="item in items" :key="item.id" class="flex items-start gap-2">
        <Checkbox
          :modelValue="item.checked"
          binary
          :inputId="`checklist-item-${item.id}`"
          :disabled="!canEdit"
          @update:modelValue="() => !busy && emit('toggle', item)"
        />
        <label
          :for="`checklist-item-${item.id}`"
          class="min-w-0 break-words text-sm"
          :class="item.checked ? 'text-slate-400 line-through dark:text-slate-500' : 'text-slate-700 dark:text-slate-300'"
        >
          {{ item.text }}
        </label>
      </li>
    </ul>
  </section>
</template>
