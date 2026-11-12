<script setup>
import { computed, defineAsyncComponent, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import Skeleton from 'primevue/skeleton'
import Tag from 'primevue/tag'
import TaskCard from '@/components/TaskCard.vue'
import TaskDetails from '@/components/TaskDetails.vue'
import BoardFilter from '@/components/BoardFilter.vue'
import { useBoardFilter } from '@/composables/useBoardFilter'
import { useBoardViewPrefs } from '@/composables/useBoardViewPrefs'
import ViewSwitch from '@/components/ViewSwitch.vue'
import { usePageTitle } from '@/utils/title'
import { useBoardEvents } from '@/composables/useBoardEvents'

// PrimeVue's DataTable is large; load it only when the table view is opened.
const TaskTable = defineAsyncComponent(() => import('@/components/TaskTable.vue'))

// Read-only board behind a share link. Works without login, so it uses plain fetch.
const route = useRoute()
const router = useRouter()

const board = ref(null) // { name, columns, labels, tasks }
const status = ref('loading') // 'loading' | 'ok' | 'not-found' | 'error'

const tasksByColumn = computed(() => {
  const byColumn = Object.fromEntries((board.value?.columns ?? []).map((c) => [c.id, []]))
  for (const task of board.value?.tasks ?? []) byColumn[task.columnId]?.push(task)
  return byColumn
})

const fetchBoard = (token) =>
  fetch(`/api/share/${encodeURIComponent(token)}`, { headers: { Accept: 'application/json' } })

async function load(token) {
  stopListening()
  status.value = 'loading'
  board.value = null
  try {
    const response = await fetchBoard(token)
    if (response.status === 404) {
      status.value = 'not-found'
      return
    }
    if (!response.ok) throw new Error(`HTTP ${response.status}`)
    board.value = await response.json()
    status.value = 'ok'
    // Old links (plain token) or a renamed board: show the link's current form in the address bar.
    if (board.value.path && board.value.path !== token) {
      router.replace({ name: 'shared-board', params: { token: board.value.path } })
    }
    listen(board.value.path ?? token)
  } catch {
    status.value = 'error'
  }
}

// Live updates (see useBoardEvents): fetched again in the background, so the page stays as it is;
// Vue only patches what differs.
let refreshes = 0
async function refresh() {
  const token = board.value?.path
  if (!token) return
  const current = ++refreshes
  try {
    const response = await fetchBoard(token)
    if (current !== refreshes) return // a newer refresh is under way
    if (response.status === 404) {
      stopListening()
      status.value = 'not-found'
      board.value = null
      return 'gone'
    }
    if (response.ok) board.value = await response.json()
  } catch {
    // Keep showing the board; the next change or reconnect tries again.
  }
}

const { listen: listenTo, stop: stopListening } = useBoardEvents({ refresh })
const listen = (path) => listenTo(`/api/share/${encodeURIComponent(path)}/events`)

const labelsOf = (task) => (board.value?.labels ?? []).filter((l) => task.labelIds?.includes(l.id))

const {
  text: filterText,
  labelIds: filterLabelIds,
  terms: filterTerms,
  active: filterActive,
  match: matchOf,
  clear: clearFilter,
} = useBoardFilter(labelsOf)
// Like the board: columns without any task are a narrow strip on wide screens.
const isEmpty = (columnId) => !tasksByColumn.value[columnId]?.length
const visibleCount = (columnId) => tasksByColumn.value[columnId].filter((t) => matchOf(t).matches).length
const countLabel = (columnId) => {
  const total = tasksByColumn.value[columnId].length
  return filterActive.value ? `${visibleCount(columnId)} / ${total}` : `${total}`
}

// Remembered per share link (its key, which survives a rename of the board) on this device.
const { view, compact, mode, setMode } = useBoardViewPrefs(() =>
  board.value?.path ? `share:${board.value.path.slice(-22)}` : null,
)
const tableTasks = computed(() =>
  (board.value?.columns ?? []).flatMap((c) => tasksByColumn.value[c.id] ?? []).filter((t) => matchOf(t).matches),
)

// The task shown in the dialog; read-only, so there is no edit mode. Looked up by id, so the
// dialog follows live updates.
const openTaskId = ref(null)
const openTask = computed(() => board.value?.tasks.find((t) => t.id === openTaskId.value) ?? null)
const detailsVisible = ref(false)
// Deleted while open, or the link stopped working.
watch(openTask, (task) => {
  if (!task) detailsVisible.value = false
})
const openColumn = computed(() => board.value?.columns.find((c) => c.id === openTask.value?.columnId) ?? null)

// Browser title, e.g. "Bewerbungen (read only) · Taskz", with the open task in front.
usePageTitle(() => {
  if (status.value === 'not-found') return ['Link not working']
  if (status.value === 'error') return ['Could not load the board']
  if (!board.value) return []
  const boardTitle = `${board.value.name} (read only)`
  return [detailsVisible.value ? openTask.value?.title : null, boardTitle]
})

function showTask(task) {
  openTaskId.value = task.id
  detailsVisible.value = true
}

// Correcting the address (see load) must not load the board again.
watch(
  () => route.params.token,
  (token) => {
    if (token !== board.value?.path) load(token)
  },
  { immediate: true },
)
</script>

<template>
  <section class="flex flex-col gap-6 px-4 py-8">
    <div v-if="status === 'loading'" class="flex gap-4 overflow-hidden">
      <Skeleton v-for="n in 3" :key="n" width="18rem" height="20rem" borderRadius="0.75rem" class="shrink-0" />
    </div>

    <div
      v-else-if="status !== 'ok'"
      class="mx-auto flex max-w-md flex-col items-center gap-3 rounded-xl border border-dashed border-slate-300 dark:border-slate-700 bg-white dark:bg-slate-900 px-6 py-14 text-center"
    >
      <i class="pi pi-link text-4xl text-slate-300 dark:text-slate-600" aria-hidden="true" />
      <template v-if="status === 'not-found'">
        <p class="font-medium text-slate-700 dark:text-slate-300">This link doesn't work anymore</p>
        <p class="text-sm text-slate-500 dark:text-slate-400">The board may no longer be shared, or the link was replaced by a new one.</p>
      </template>
      <template v-else>
        <p class="font-medium text-slate-700 dark:text-slate-300">Could not load the board</p>
        <p class="text-sm text-slate-500 dark:text-slate-400">Please try again later.</p>
      </template>
    </div>

    <template v-else>
      <div class="flex flex-wrap items-center gap-3">
        <h1 class="text-2xl font-semibold tracking-tight text-slate-900 dark:text-slate-100">{{ board.name }}</h1>
        <Tag value="Read only" icon="pi pi-eye" severity="secondary" />
        <ViewSwitch class="ml-auto" labelsFrom="48rem" :mode="mode" @update:mode="setMode" />
      </div>

      <BoardFilter
        v-model:text="filterText"
        v-model:labelIds="filterLabelIds"
        :labels="board.labels ?? []"
        :active="filterActive"
        class="-mt-3"
        @clear="clearFilter"
      />

      <TaskTable
        v-if="view === 'table'"
        :tasks="tableTasks"
        :columns="board.columns"
        :labelsOf="labelsOf"
        :terms="filterTerms"
        :filtered="filterActive && board.tasks.length > 0"
        readonly
        @open="showTask"
      />

      <div v-else class="-mx-4 overflow-x-auto px-4 pb-4">
        <div class="flex items-start gap-4">
          <div
            v-for="column in board.columns"
            :key="column.id"
            :data-column-id="column.id"
            class="flex w-72 shrink-0 flex-col rounded-xl bg-slate-100 dark:bg-slate-900"
            :class="{ 'md:w-12 md:pb-3': isEmpty(column.id) }"
          >
            <header class="flex items-center gap-2 px-4 pt-3" :class="{ 'md:flex-col md:px-1': isEmpty(column.id) }">
              <h2
                class="min-w-0 flex-1 truncate text-sm font-semibold text-slate-700 dark:text-slate-300"
                :class="{ 'md:w-full md:flex-none md:text-center md:text-[0.6875rem]': isEmpty(column.id) }"
                :title="column.name"
              >
                {{ column.name }}
              </h2>
              <span class="rounded-full bg-white dark:bg-slate-800 px-2 text-xs font-medium text-slate-500 dark:text-slate-400">
                {{ countLabel(column.id) }}
              </span>
            </header>
            <div class="flex min-h-12 flex-col gap-2 p-2" :class="{ 'md:hidden': isEmpty(column.id) }">
              <p v-if="filterActive && visibleCount(column.id) === 0" class="px-2 text-sm text-slate-400 dark:text-slate-500">
                No matches
              </p>
              <TaskCard
                v-for="task in tasksByColumn[column.id]"
                :key="task.id"
                :task="task"
                :color="column.color"
                :labels="labelsOf(task)"
                readonly
                v-show="matchOf(task).matches"
                :terms="filterTerms"
                :matchInDescription="matchOf(task).inDescription && !matchOf(task).inTitle"
                :compact="compact"
                @open="showTask"
              />
            </div>
          </div>
        </div>
      </div>
    </template>

    <Dialog
      v-model:visible="detailsVisible"
      modal
      header="Task"
      :style="{ width: '48rem' }"
      :breakpoints="{ '820px': '95vw' }"
      :draggable="false"
    >
      <TaskDetails v-if="openTask" :task="openTask" :column="openColumn" :labels="labelsOf(openTask)" />
      <template #footer>
        <Button label="Close" severity="secondary" text @click="detailsVisible = false" />
      </template>
    </Dialog>
  </section>
</template>
