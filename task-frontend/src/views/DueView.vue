<script setup>
import { computed, onMounted, ref } from 'vue'
import { addDays, isToday, startOfDay } from 'date-fns'
import Button from 'primevue/button'
import { useBoardStore } from '@/stores/board'
import { formatDue, fromApi } from '@/utils/date'
import { DEFAULT_COLUMN_COLOR } from '@/utils/task'

// Tasks with a due date on all of the user's boards, grouped by when they are due. Tasks in the last
// column of a board count as done and aren't listed (see BoardService.dueTasksOf).
const store = useBoardStore()

const tasks = ref([])
const status = ref('loading') // 'loading' | 'ok' | 'error'

onMounted(async () => {
  try {
    tasks.value = (await store.fetchDue()).map((t) => ({ ...t, due: fromApi(t.due_date) }))
    status.value = 'ok'
  } catch (err) {
    // Not logged in: App takes care of it.
    if (err.name !== 'AuthError') status.value = 'error'
  }
})

const groups = computed(() => {
  const now = new Date()
  const weekEnd = addDays(startOfDay(now), 8)
  const groups = [
    { key: 'overdue', title: 'Overdue', tasks: [] },
    { key: 'today', title: 'Today', tasks: [] },
    { key: 'week', title: 'Next 7 days', tasks: [] },
    { key: 'later', title: 'Later', tasks: [] },
  ]
  for (const task of tasks.value) {
    const index = task.due < now ? 0 : isToday(task.due) ? 1 : task.due < weekEnd ? 2 : 3
    groups[index].tasks.push(task)
  }
  return groups.filter((g) => g.tasks.length > 0)
})
</script>

<template>
  <div class="mx-auto flex max-w-3xl flex-col gap-6" data-testid="due">
    <div class="flex items-center justify-between gap-3">
      <h1 class="text-2xl font-semibold text-slate-900 dark:text-slate-100">What's due</h1>
      <RouterLink :to="{ name: 'board' }" custom v-slot="{ navigate }">
        <Button label="Boards" icon="pi pi-th-large" severity="secondary" outlined size="small" @click="navigate" />
      </RouterLink>
    </div>

    <i v-if="status === 'loading'" class="pi pi-spin pi-spinner text-2xl text-slate-400" />
    <p v-else-if="status === 'error'" class="text-slate-600 dark:text-slate-400">Could not load the due tasks.</p>
    <p v-else-if="!groups.length" class="text-slate-600 dark:text-slate-400">
      Nothing is due. Tasks with a due date show here, except those in the last column of their board.
    </p>

    <section v-for="group in groups" :key="group.key" class="flex flex-col gap-2" :data-group="group.key">
      <h2
        class="text-sm font-semibold"
        :class="group.key === 'overdue' ? 'text-red-600 dark:text-red-400' : 'text-slate-700 dark:text-slate-300'"
      >
        {{ group.title }} <span class="font-normal text-slate-400">{{ group.tasks.length }}</span>
      </h2>
      <ul class="flex flex-col divide-y divide-slate-200 rounded-lg border border-slate-200 bg-white dark:divide-slate-700 dark:border-slate-700 dark:bg-slate-800">
        <li v-for="task in group.tasks" :key="task.id">
          <RouterLink
            :to="{ name: 'board', params: { slug: task.boardSlug }, query: { task: task.id } }"
            class="flex items-center gap-3 px-3 py-2 hover:bg-slate-50 dark:hover:bg-slate-700/50"
            data-testid="due-task"
          >
            <span class="min-w-0 flex-1">
              <span class="block truncate text-sm font-medium text-slate-900 dark:text-slate-100">{{ task.title }}</span>
              <span class="flex items-center gap-1.5 truncate text-xs text-slate-500 dark:text-slate-400">
                <span
                  class="h-2 w-2 shrink-0 rounded-full"
                  :style="{ backgroundColor: task.columnColor ?? DEFAULT_COLUMN_COLOR }"
                  aria-hidden="true"
                />
                {{ task.boardName }} · {{ task.columnName }}
              </span>
            </span>
            <span
              class="shrink-0 text-xs font-medium"
              :class="group.key === 'overdue' ? 'text-red-600 dark:text-red-400' : 'text-slate-500 dark:text-slate-400'"
            >
              {{ formatDue(task.due) }}
            </span>
          </RouterLink>
        </li>
      </ul>
    </section>
  </div>
</template>
