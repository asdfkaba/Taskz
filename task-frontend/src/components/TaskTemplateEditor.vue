<script setup>
import { computed, ref } from 'vue'
import Textarea from 'primevue/textarea'
import Button from 'primevue/button'
import { useToast } from 'primevue/usetoast'
import { useBoardStore } from '@/stores/board'
import { renderMarkdown } from '@/utils/markdown'
import MarkdownHelp from '@/components/MarkdownHelp.vue'

// The description new tasks of the open board start with. Part of the board settings.

// Same limit as a description (BoardService.MAX_TASK_TEMPLATE_LENGTH).
const MAX_LENGTH = 10000

const store = useBoardStore()
const toast = useToast()

const text = ref(store.taskTemplate ?? '')
const mode = ref('write') // 'write' | 'preview'
const busy = ref(false)
const previewHtml = computed(() => renderMarkdown(text.value))
// The backend stores the template trimmed, and an empty one as null.
const changed = computed(() => (text.value.trim() || null) !== (store.taskTemplate ?? null))

async function save() {
  busy.value = true
  try {
    await store.setTaskTemplate(text.value)
    text.value = store.taskTemplate ?? ''
    toast.add({ severity: 'success', summary: store.taskTemplate ? 'Template saved' : 'Template removed', life: 2500 })
  } catch (err) {
    toast.add({ severity: 'error', summary: 'Could not save the template', detail: err.message, life: 5000 })
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <form class="flex flex-col gap-1.5" @submit.prevent="save">
    <div class="flex items-end justify-between gap-2">
      <label for="task-template" class="text-sm text-slate-600 dark:text-slate-400">
        New tasks on this board start with this description. Leave it empty for none.
      </label>
      <div role="group" aria-label="Template mode" class="flex shrink-0 rounded-md bg-slate-100 p-0.5 text-xs dark:bg-slate-800">
        <button
          v-for="m in ['write', 'preview']"
          :key="m"
          type="button"
          class="rounded px-2 py-0.5 font-medium capitalize"
          :class="mode === m ? 'bg-white text-slate-800 shadow-sm dark:bg-slate-700 dark:text-slate-200' : 'text-slate-500 hover:text-slate-700 dark:text-slate-400 dark:hover:text-slate-300'"
          :aria-pressed="mode === m"
          @click="mode = m"
        >
          {{ m }}
        </button>
      </div>
    </div>
    <Textarea
      v-show="mode === 'write'"
      id="task-template"
      v-model="text"
      rows="6"
      autoResize
      :maxlength="MAX_LENGTH"
      placeholder="e.g. ## Contact&#10;- Name:&#10;- Link:"
      class="max-h-[50vh] font-mono !text-sm"
      fluid
    />
    <div
      v-if="mode === 'preview'"
      data-testid="template-preview"
      class="max-h-[50vh] min-h-24 overflow-y-auto rounded-md border border-slate-200 px-3 py-2 dark:border-slate-700"
    >
      <!-- Sanitized in renderMarkdown. -->
      <div v-if="previewHtml" class="markdown" v-html="previewHtml" />
      <p v-else class="text-sm text-slate-400 dark:text-slate-500">Nothing to preview</p>
    </div>
    <MarkdownHelp />
    <Button
      type="submit"
      label="Save template"
      icon="pi pi-check"
      size="small"
      class="self-end"
      :disabled="!changed"
      :loading="busy"
    />
  </form>
</template>
