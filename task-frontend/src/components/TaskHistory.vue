<script setup>
import UserIcon from '@/components/UserIcon.vue'
import { computed, ref, watch } from 'vue'
import { format, formatDistanceToNow } from 'date-fns'
import { useBoardStore } from '@/stores/board'
import { formatDue, fromApi } from '@/utils/date'
import { lineDiff } from '@/utils/diff'

// The task's change history, collapsed until opened. Only for the owner; shared boards don't show it.
const props = defineProps({ task: { type: Object, required: true } })

const store = useBoardStore()
const open = ref(false)
const events = ref([])
const status = ref('idle') // 'idle' | 'loading' | 'ok' | 'error'
const openDiffs = ref(new Set()) // event ids whose description diff is shown

async function load() {
  status.value = 'loading'
  try {
    events.value = await store.fetchTaskHistory(props.task.id)
    status.value = 'ok'
  } catch {
    status.value = 'error'
  }
}

function toggle() {
  open.value = !open.value
  if (open.value) load()
}

// Reload after the task was saved, moved or got attachments while the history is open.
watch(
  () => [props.task.edited, props.task.columnId, props.task.labelIds?.join(), props.task.attachments],
  () => {
    if (open.value) load()
  },
)

// Tasks from before the history existed have no "created" event; show their creation date instead.
// Events whose changes no longer exist as fields (old priority changes) have nothing to say and are left out.
const entries = computed(() => {
  const list = events.value.filter((e) => lines(e).length > 0)
  const created = fromApi(props.task.created)
  if (status.value === 'ok' && created && !list.some((e) => e.type === 'created')) {
    list.push({ id: 'created', type: 'created', at: props.task.created, user: null, changes: {} })
  }
  return list
})

const dueText = (value) => formatDue(fromApi(value))
const quoted = (text) => `“${text}”`
const names = (list) => list.join(', ')

// One readable line per change of an event.
function lines(event) {
  const c = event.changes
  if (event.type === 'created') return [c.column ? `Created in ${c.column}` : 'Created']
  if (event.type === 'moved') return [`Moved from ${c.column[0]} to ${c.column[1]}`]
  if (event.type === 'deleted') return ['Deleted']
  if (event.type === 'checked' || event.type === 'unchecked') return [checkboxLine(event.type === 'checked', c)]
  if (event.type === 'attached') return [`Attached ${quoted(c.file)}`]
  if (event.type === 'detached') return [`Removed attachment ${quoted(c.file)}`]
  const out = []
  if (c.title) out.push(`Renamed from ${quoted(c.title[0])} to ${quoted(c.title[1])}`)
  if (c.description) {
    const [before, after] = c.description
    out.push(!before ? 'Added a description' : !after ? 'Removed the description' : 'Changed the description')
  }
  if (c.due_date) {
    const [before, after] = c.due_date
    out.push(
      !before ? `Set the due date to ${dueText(after)}`
        : !after ? 'Removed the due date'
          : `Changed the due date from ${dueText(before)} to ${dueText(after)}`,
    )
  }
  if (c.labelsAdded) out.push(`Added ${c.labelsAdded.length === 1 ? 'label' : 'labels'} ${names(c.labelsAdded)}`)
  if (c.labelsRemoved) out.push(`Removed ${c.labelsRemoved.length === 1 ? 'label' : 'labels'} ${names(c.labelsRemoved)}`)
  if (c.checklistAdded) out.push(`Added ${c.checklistAdded.length === 1 ? 'checklist item' : 'checklist items'} ${c.checklistAdded.map(quoted).join(', ')}`)
  if (c.checklistRemoved) out.push(`Removed ${c.checklistRemoved.length === 1 ? 'checklist item' : 'checklist items'} ${c.checklistRemoved.map(quoted).join(', ')}`)
  return out
}

// e.g. "Checked “CV”, completing “Application”" or "Unchecked “Application” and 3 sub-items"
function checkboxLine(checked, c) {
  let text = `${checked ? 'Checked' : 'Unchecked'} ${quoted(c.item)}`
  if (c.subItems) text += ` and ${c.subItems} ${c.subItems === 1 ? 'sub-item' : 'sub-items'}`
  if (c.parents?.length) text += `, ${checked ? 'completing' : 'reopening'} ${c.parents.map(quoted).join(', ')}`
  return text
}

function toggleDiff(id) {
  const next = new Set(openDiffs.value)
  next.has(id) ? next.delete(id) : next.add(id)
  openDiffs.value = next
}

const relative = (at) => formatDistanceToNow(fromApi(at), { addSuffix: true })
const absolute = (at) => format(fromApi(at), 'yyyy-MM-dd HH:mm')
const ICONS = {
  created: 'pi-plus',
  updated: 'pi-pencil',
  moved: 'pi-arrow-right',
  deleted: 'pi-trash',
  checked: 'pi-check-square',
  unchecked: 'pi-stop',
  attached: 'pi-paperclip',
  detached: 'pi-paperclip',
}
</script>

<template>
  <section class="border-t border-slate-200 pt-3 dark:border-slate-800" data-testid="task-history">
    <button
      type="button"
      class="flex items-center gap-2 text-sm font-medium text-slate-600 hover:text-slate-900 dark:text-slate-400 dark:hover:text-slate-100"
      :aria-expanded="open"
      @click="toggle"
    >
      <i class="pi text-xs" :class="open ? 'pi-chevron-down' : 'pi-chevron-right'" aria-hidden="true" />
      History
    </button>

    <div v-if="open" class="mt-3">
      <p v-if="status === 'loading' && !events.length" class="text-sm text-slate-400">Loading…</p>
      <p v-else-if="status === 'error'" class="text-sm text-red-600 dark:text-red-400">Could not load the history.</p>
      <ol v-else class="flex flex-col gap-3">
        <li v-for="event in entries" :key="event.id" class="flex gap-3 text-sm" :data-event-type="event.type">
          <span
            class="mt-0.5 flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-slate-100 text-slate-500 dark:bg-slate-800 dark:text-slate-400"
            aria-hidden="true"
          >
            <i class="pi text-[0.65rem]" :class="ICONS[event.type]" />
          </span>
          <div class="min-w-0 flex-1">
            <p v-for="line in lines(event)" :key="line" class="break-words text-slate-700 dark:text-slate-300">{{ line }}</p>
            <p class="text-xs text-slate-400 dark:text-slate-500">
              <span v-if="event.changes?.automation" data-testid="event-user"><i class="pi pi-bolt mr-1 text-[0.65rem]" aria-hidden="true" />Automation · </span>
              <span v-else-if="event.user" data-testid="event-user"><UserIcon :seed="event.userId" size="xs" class="-mt-0.5 mr-1" />{{ event.user }} · </span>
              <time :datetime="absolute(event.at)" :title="absolute(event.at)">{{ relative(event.at) }}</time>
            </p>
            <template v-if="event.changes?.description">
              <button
                type="button"
                class="mt-1 text-xs text-blue-600 hover:underline dark:text-blue-400"
                :aria-expanded="openDiffs.has(event.id)"
                @click="toggleDiff(event.id)"
              >
                {{ openDiffs.has(event.id) ? 'Hide changes' : 'Show changes' }}
              </button>
              <pre
                v-if="openDiffs.has(event.id)"
                class="mt-1 max-h-64 overflow-auto rounded-md bg-slate-50 p-2 font-mono text-xs whitespace-pre-wrap dark:bg-slate-800/60"
                data-testid="description-diff"
              ><span
                  v-for="(line, i) in lineDiff(event.changes.description[0], event.changes.description[1])"
                  :key="i"
                  class="block"
                  :class="{
                    'bg-red-100 text-red-800 line-through dark:bg-red-950/60 dark:text-red-300': line.type === 'removed',
                    'bg-green-100 text-green-800 dark:bg-green-950/60 dark:text-green-300': line.type === 'added',
                    'text-slate-500 dark:text-slate-400': line.type === 'same',
                  }"
                  :data-line="line.type"
                >{{ ({ removed: '− ', added: '+ ', same: '  ' })[line.type] }}{{ line.text || ' ' }}</span></pre>
            </template>
          </div>
        </li>
        <li v-if="!entries.length" class="text-sm text-slate-400">No changes recorded yet.</li>
      </ol>
    </div>
  </section>
</template>
