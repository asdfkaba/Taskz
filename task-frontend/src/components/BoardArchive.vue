<script setup>
import { onMounted, ref } from 'vue'
import Button from 'primevue/button'
import { useConfirm } from 'primevue/useconfirm'
import { useToast } from 'primevue/usetoast'
import { useBoardStore } from '@/stores/board'
import { fromApi, formatShort } from '@/utils/date'
import { DEFAULT_COLUMN_COLOR } from '@/utils/task'

// The archived tasks of the open board, shown by BoardView at /boards/<slug>/archive: each can go
// back to its column or be deleted for good.
// shortcut: loaded when the page opens, not on live updates; reload on board events once two people work in the archive.
const store = useBoardStore()
const confirm = useConfirm()
const toast = useToast()

const tasks = ref([])
const status = ref('loading') // 'loading' | 'ok' | 'error'

onMounted(async () => {
  try {
    tasks.value = await store.fetchArchive()
    status.value = 'ok'
  } catch {
    status.value = 'error'
  }
})

const remove = (task) => (tasks.value = tasks.value.filter((t) => t.id !== task.id))
const showError = (summary, err) => toast.add({ severity: 'error', summary, detail: err.message, life: 5000 })

async function restore(task) {
  try {
    await store.restoreTask(task.id)
    remove(task)
    toast.add({ severity: 'success', summary: 'Task restored', detail: `Back in ${store.columnById(task.columnId)?.name}`, life: 2500 })
  } catch (err) {
    showError('Could not restore task', err)
  }
}

function askDelete(task) {
  confirm.require({
    header: 'Delete task?',
    message: `"${task.title}" will be deleted permanently.`,
    icon: 'pi pi-trash',
    rejectProps: { label: 'Cancel', severity: 'secondary', text: true },
    acceptProps: { label: 'Delete', severity: 'danger' },
    accept: async () => {
      try {
        await store.deleteTask(task.id)
        remove(task)
        toast.add({ severity: 'success', summary: 'Task deleted', life: 2500 })
      } catch (err) {
        showError('Could not delete task', err)
      }
    },
  })
}
</script>

<template>
  <div class="max-w-4xl" data-testid="board-archive">
    <p v-if="status === 'loading'" class="text-sm text-slate-500 dark:text-slate-400">Loading…</p>
    <p v-else-if="status === 'error'" class="text-sm text-red-600 dark:text-red-400">Could not load the archive.</p>
    <p
      v-else-if="tasks.length === 0"
      class="rounded-xl border border-dashed border-slate-300 bg-white px-6 py-10 text-center text-sm text-slate-500 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-400"
    >
      No archived tasks. Archive a task from its menu on the board.
    </p>
    <ul v-else class="divide-y divide-slate-200 rounded-xl border border-slate-200 bg-white dark:divide-slate-800 dark:border-slate-800 dark:bg-slate-900">
      <li v-for="task in tasks" :key="task.id" class="flex flex-wrap items-center gap-x-3 gap-y-2 px-4 py-3" :data-archived-id="task.id">
        <div class="min-w-0 flex-1">
          <p class="font-medium break-words text-slate-900 dark:text-slate-100">{{ task.title }}</p>
          <p class="flex items-center gap-2 text-sm text-slate-500 dark:text-slate-400">
            <span
              class="h-2.5 w-2.5 shrink-0 rounded-full"
              :style="{ backgroundColor: store.columnById(task.columnId)?.color ?? DEFAULT_COLUMN_COLOR }"
            />
            <span class="truncate">{{ store.columnById(task.columnId)?.name }}</span>
            <span>· archived {{ formatShort(fromApi(task.archived)) }}</span>
          </p>
        </div>
        <Button label="Restore" icon="pi pi-undo" severity="secondary" outlined size="small" @click="restore(task)" />
        <Button
          icon="pi pi-trash"
          severity="danger"
          text
          size="small"
          :aria-label="`Delete ${task.title} permanently`"
          title="Delete permanently"
          @click="askDelete(task)"
        />
      </li>
    </ul>
  </div>
</template>
