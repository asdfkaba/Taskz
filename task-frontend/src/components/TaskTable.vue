<script setup>
import { computed } from 'vue'
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import Select from 'primevue/select'
import { fromApi, formatDue, formatShort } from '@/utils/date'
import { DEFAULT_COLUMN_COLOR } from '@/utils/task'
import { highlight } from '@/utils/search'

// The board's tasks as a sortable table. Without a sort they are in board order (column by column).
// A click on a row opens the task; the status select moves it to the end of another column
// (shared boards are read-only and show the status as text).
const props = defineProps({
  // Tasks to show, already filtered, in board order.
  tasks: { type: Array, required: true },
  columns: { type: Array, required: true }, // [{ id, name, color, position }]
  labelsOf: { type: Function, required: true },
  // Search terms to highlight in the title.
  terms: { type: Array, default: () => [] },
  // The board has tasks, but the filter hides all of them.
  filtered: { type: Boolean, default: false },
  readonly: { type: Boolean, default: false },
})
const emit = defineEmits(['open', 'move'])

// Sort keys next to the shown values; missing dates sort after all others in ascending order.
const LAST = Number.MAX_SAFE_INTEGER
const rows = computed(() =>
  props.tasks.map((task, order) => {
    const column = props.columns.find((c) => c.id === task.columnId)
    const due = fromApi(task.due_date)
    const created = fromApi(task.created)
    const edited = fromApi(task.edited) ?? created
    const items = task.checklist ?? []
    const done = items.filter((i) => i.checked).length
    return {
      id: task.id,
      task,
      order,
      title: task.title,
      titlePieces: highlight(task.title, props.terms),
      columnId: task.columnId,
      statusOrder: column?.position ?? LAST,
      labels: props.labelsOf(task),
      due,
      dueSort: due?.getTime() ?? LAST,
      overdue: due !== null && due < new Date(),
      checklist: items.length ? { done, total: items.length } : null,
      checklistSort: items.length ? done / items.length : -1,
      created,
      createdSort: created?.getTime() ?? LAST,
      edited,
      editedSort: edited?.getTime() ?? LAST,
    }
  }),
)

const colorOf = (columnId) => props.columns.find((c) => c.id === columnId)?.color ?? DEFAULT_COLUMN_COLOR

function onRowClick(event) {
  // Clicks in the status select or on a link don't open the task.
  if (event.originalEvent.target.closest('[data-no-open], a')) return
  emit('open', event.data.task)
}

function onStatusChange(row, columnId) {
  if (columnId !== row.columnId) emit('move', row.task, columnId)
}
</script>

<template>
  <DataTable
    :value="rows"
    dataKey="id"
    removableSort
    size="small"
    rowHover
    class="task-table overflow-hidden rounded-xl border border-slate-200 text-sm dark:border-slate-800"
    :rowClass="() => 'cursor-pointer'"
    data-testid="task-table"
    @row-click="onRowClick"
  >
    <template #empty>
      <p class="py-6 text-center text-slate-500 dark:text-slate-400">
        {{ filtered ? 'No tasks match the filter.' : 'No tasks yet.' }}
      </p>
    </template>

    <Column field="title" header="Title" sortable sortField="title">
      <template #body="{ data }">
        <!-- A real button, so the task can be opened with the keyboard too. -->
        <button
          type="button"
          class="text-left font-medium break-words text-slate-900 hover:underline dark:text-slate-100"
          @click.stop="emit('open', data.task)"
        >
          <template v-for="(piece, i) in data.titlePieces" :key="i">
            <mark v-if="piece.hit" class="rounded-sm bg-yellow-200 text-inherit dark:bg-yellow-500/40">{{ piece.text }}</mark>
            <template v-else>{{ piece.text }}</template>
          </template>
        </button>
      </template>
    </Column>

    <Column header="Status" sortable sortField="statusOrder" :style="{ width: '11rem' }">
      <template #body="{ data }">
        <span v-if="readonly" class="flex min-w-0 items-center gap-2">
          <span class="h-2.5 w-2.5 shrink-0 rounded-full" :style="{ backgroundColor: colorOf(data.columnId) }" />
          <span class="truncate">{{ columns.find((c) => c.id === data.columnId)?.name }}</span>
        </span>
        <div v-else data-no-open>
          <Select
            :modelValue="data.columnId"
            :options="columns"
            optionLabel="name"
            optionValue="id"
            size="small"
            fluid
            :aria-label="`Status of ${data.title}`"
            @update:modelValue="onStatusChange(data, $event)"
          >
            <template #value="{ value }">
              <span class="flex min-w-0 items-center gap-2">
                <span class="h-2.5 w-2.5 shrink-0 rounded-full" :style="{ backgroundColor: colorOf(value) }" />
                <span class="truncate">{{ columns.find((c) => c.id === value)?.name }}</span>
              </span>
            </template>
            <template #option="{ option }">
              <span class="flex items-center gap-2">
                <span class="h-2.5 w-2.5 shrink-0 rounded-full" :style="{ backgroundColor: option.color ?? DEFAULT_COLUMN_COLOR }" />
                {{ option.name }}
              </span>
            </template>
          </Select>
        </div>
      </template>
    </Column>

    <Column header="Labels" headerClass="hidden md:table-cell" bodyClass="hidden md:table-cell">
      <template #body="{ data }">
        <span class="flex flex-wrap items-center gap-1" data-testid="label-dots">
          <span
            v-for="label in data.labels"
            :key="label.id"
            role="img"
            :aria-label="`Label ${label.name}`"
            :title="label.name"
            class="h-2.5 w-2.5 rounded-full ring-1 ring-black/10 dark:ring-white/20"
            :style="{ backgroundColor: label.color }"
          />
        </span>
      </template>
    </Column>

    <Column header="Due" sortable sortField="dueSort" bodyClass="whitespace-nowrap">
      <template #body="{ data }">
        <span v-if="data.due" :class="data.overdue ? 'font-medium text-red-600 dark:text-red-400' : ''">
          {{ formatDue(data.due) }}
        </span>
      </template>
    </Column>

    <Column
      header="Checklist"
      sortable
      sortField="checklistSort"
      headerClass="hidden md:table-cell"
      bodyClass="hidden md:table-cell whitespace-nowrap"
    >
      <template #body="{ data }">
        <span
          v-if="data.checklist"
          :class="data.checklist.done === data.checklist.total ? 'font-medium text-green-700 dark:text-green-400' : ''"
          :aria-label="`${data.checklist.done} of ${data.checklist.total} checklist items done`"
        >
          {{ data.checklist.done }}/{{ data.checklist.total }}
        </span>
      </template>
    </Column>

    <Column
      header="Created"
      sortable
      sortField="createdSort"
      headerClass="hidden lg:table-cell"
      bodyClass="hidden lg:table-cell whitespace-nowrap text-slate-500 dark:text-slate-400"
    >
      <template #body="{ data }">{{ formatShort(data.created) }}</template>
    </Column>

    <Column
      header="Changed"
      sortable
      sortField="editedSort"
      headerClass="hidden lg:table-cell"
      bodyClass="hidden lg:table-cell whitespace-nowrap text-slate-500 dark:text-slate-400"
    >
      <template #body="{ data }">{{ formatShort(data.edited) }}</template>
    </Column>
  </DataTable>
</template>
