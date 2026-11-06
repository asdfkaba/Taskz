<script setup>
import { computed, onMounted, ref } from 'vue'
import Button from 'primevue/button'
import Checkbox from 'primevue/checkbox'
import InputText from 'primevue/inputtext'
import Select from 'primevue/select'
import Textarea from 'primevue/textarea'
import { useToast } from 'primevue/usetoast'
import { useBoardStore } from '@/stores/board'

// Automations of the open board: when a task enters a column, change its checklist or add a note with a
// reminder (written as whoever moved the task, so the reminder is theirs).
// Part of the board settings, owner only. Column and action of a rule are fixed; its items or days can be edited.
const props = defineProps({ boardId: { type: Number, required: true } })

// Same limits as BoardRuleService and TaskNoteService.
const MAX_ITEMS = 20
const MAX_DAYS = 365
const MAX_NOTE_LENGTH = 5000
const ACTIONS = [
  { value: 'APPEND_CHECKLIST', label: 'Add checklist items' },
  { value: 'REPLACE_CHECKLIST', label: 'Replace the checklist' },
  { value: 'ADD_NOTE_REMINDER', label: 'Add a note with a reminder' },
]

const store = useBoardStore()
const toast = useToast()

const rules = ref([])
const loading = ref(true)
const busy = ref(false)

const columnId = ref(store.columns[0]?.id ?? null)
const action = ref('APPEND_CHECKLIST')
const hasItems = (ruleAction) => ruleAction !== 'ADD_NOTE_REMINDER'
const itemsText = ref('')
const noteText = ref('')
const days = ref('14')
const removeOnLeave = ref(false)

const toItems = (text) => text.split('\n').map((line) => line.trim()).filter(Boolean)
const toDays = (text) => (/^\d+$/.test(text.trim()) ? Number(text.trim()) : null)
// The settings the backend expects for the action, or null if they aren't valid yet.
function settings(ruleAction, text, note, daysText, remove) {
  if (hasItems(ruleAction)) {
    const list = toItems(text)
    return list.length > 0 && list.length <= MAX_ITEMS ? { items: list } : null
  }
  const n = toDays(daysText)
  return note.trim() && n != null && n <= MAX_DAYS ? { text: note.trim(), days: n, removeOnLeave: remove } : null
}

const newSettings = computed(() => settings(action.value, itemsText.value, noteText.value, days.value, removeOnLeave.value))
const canAdd = computed(() => !busy.value && columnId.value != null && newSettings.value != null)

// The rule being edited: { id, itemsText, noteText, days, removeOnLeave }, or null.
const editing = ref(null)
const editSettings = computed(() => {
  const e = editing.value
  const rule = e && rules.value.find((r) => r.id === e.id)
  return rule ? settings(rule.action, e.itemsText, e.noteText, e.days, e.removeOnLeave) : null
})

// Rules grouped under their column, in the board's column order.
const byColumn = computed(() =>
  store.columns
    .map((column) => ({ column, rules: rules.value.filter((r) => r.columnId === column.id) }))
    .filter((group) => group.rules.length > 0),
)

function describe(rule) {
  if (rule.action === 'APPEND_CHECKLIST') return `Add checklist items: ${rule.items.join(', ')}`
  if (rule.action === 'REPLACE_CHECKLIST') return `Replace the checklist with: ${rule.items.join(', ')}`
  const removed = rule.removeOnLeave ? ', removed when the task leaves' : ''
  return `Add the note “${rule.text}” with a reminder in ${rule.days} ${rule.days === 1 ? 'day' : 'days'}${removed}`
}

function showError(summary, err) {
  toast.add({ severity: 'error', summary, detail: err.message, life: 5000 })
}

onMounted(async () => {
  try {
    rules.value = await store.fetchRules(props.boardId)
  } catch (err) {
    showError('Could not load automations', err)
  } finally {
    loading.value = false
  }
})

async function add() {
  if (!canAdd.value) return
  busy.value = true
  try {
    rules.value.push(await store.addRule(props.boardId, { columnId: columnId.value, action: action.value, ...newSettings.value }))
    itemsText.value = ''
    noteText.value = ''
    removeOnLeave.value = false
  } catch (err) {
    showError('Could not add the automation', err)
  } finally {
    busy.value = false
  }
}

async function setEnabled(rule, enabled) {
  try {
    const updated = await store.updateRule({ ...rule, enabled })
    rules.value.splice(rules.value.indexOf(rule), 1, updated)
  } catch (err) {
    showError('Could not change the automation', err)
  }
}

function startEdit(rule) {
  editing.value = {
    id: rule.id,
    itemsText: (rule.items ?? []).join('\n'),
    noteText: rule.text ?? '',
    days: String(rule.days ?? ''),
    removeOnLeave: rule.removeOnLeave,
  }
}

async function saveEdit(rule) {
  if (!editSettings.value || busy.value) return
  busy.value = true
  try {
    const updated = await store.updateRule({ ...rule, ...editSettings.value })
    rules.value.splice(rules.value.indexOf(rule), 1, updated)
    editing.value = null
  } catch (err) {
    showError('Could not change the automation', err)
  } finally {
    busy.value = false
  }
}

async function remove(rule) {
  try {
    await store.deleteRule(rule.id)
    rules.value.splice(rules.value.indexOf(rule), 1)
  } catch (err) {
    showError('Could not delete the automation', err)
  }
}
</script>

<template>
  <div class="flex flex-col gap-4" data-testid="board-rules">
    <p class="text-sm text-slate-600 dark:text-slate-400">
      Run when a task is moved into a column or created in it. Adding skips items the checklist already has.
      Replacing makes the list the task's checklist; items in both stay ticked. A note with a reminder is
      written as whoever moved the task, and shows in their “What's due”; it can be removed again when the
      task leaves the column.
    </p>

    <p v-if="loading" class="text-sm text-slate-400">Loading…</p>
    <p v-else-if="!rules.length" class="text-sm text-slate-500 dark:text-slate-400">No automations yet.</p>
    <div v-for="group in byColumn" :key="group.column.id" class="flex flex-col gap-1">
      <h3 class="text-sm font-medium text-slate-800 dark:text-slate-200">When a task enters {{ group.column.name }}</h3>
      <ul class="flex flex-col">
        <li v-for="rule in group.rules" :key="rule.id" class="py-1" data-testid="rule">
          <form v-if="editing?.id === rule.id" class="flex flex-col gap-2" @submit.prevent="saveEdit(rule)" @keydown.esc.stop="editing = null">
            <Textarea
              v-if="hasItems(rule.action)"
              v-model="editing.itemsText"
              rows="3"
              autoResize
              aria-label="Edit checklist items"
            />
            <div v-else class="flex flex-wrap items-center gap-2 text-sm text-slate-700 dark:text-slate-300">
              <InputText v-model="editing.noteText" :maxlength="MAX_NOTE_LENGTH" class="min-w-0 flex-1" size="small" aria-label="Edit note" />
              <span>remind in</span>
              <InputText v-model="editing.days" inputmode="numeric" class="w-20" size="small" aria-label="Edit days from now" />
              <span>days</span>
              <label class="flex w-full items-center gap-2">
                <Checkbox v-model="editing.removeOnLeave" binary />
                Remove the note when the task leaves the column
              </label>
            </div>
            <div class="flex gap-2">
              <Button type="submit" label="Save" size="small" :disabled="!editSettings || busy" :loading="busy" />
              <Button type="button" label="Cancel" severity="secondary" text size="small" @click="editing = null" />
            </div>
          </form>
          <div v-else class="flex items-center gap-3">
            <Checkbox
              :modelValue="rule.enabled"
              binary
              :inputId="`rule-${rule.id}`"
              @update:modelValue="(value) => setEnabled(rule, value)"
            />
            <label
              :for="`rule-${rule.id}`"
              class="min-w-0 flex-1 break-words text-sm"
              :class="rule.enabled ? 'text-slate-700 dark:text-slate-300' : 'text-slate-400 line-through dark:text-slate-500'"
            >
              {{ describe(rule) }}
            </label>
            <Button
              icon="pi pi-pencil"
              severity="secondary"
              text
              size="small"
              :aria-label="`Edit automation: ${describe(rule)}`"
              @click="startEdit(rule)"
            />
            <Button
              icon="pi pi-trash"
              severity="secondary"
              text
              size="small"
              :aria-label="`Delete automation: ${describe(rule)}`"
              @click="remove(rule)"
            />
          </div>
        </li>
      </ul>
    </div>

    <form class="flex flex-col gap-2 border-t border-slate-200 pt-3 dark:border-slate-800" @submit.prevent="add">
      <div class="flex flex-wrap items-center gap-2 text-sm text-slate-700 dark:text-slate-300">
        <span>When a task enters</span>
        <Select v-model="columnId" :options="store.columns" optionLabel="name" optionValue="id" aria-label="Column" size="small" />
        <Select v-model="action" :options="ACTIONS" optionLabel="label" optionValue="value" aria-label="Action" size="small" />
      </div>
      <Textarea
        v-if="hasItems(action)"
        v-model="itemsText"
        rows="3"
        autoResize
        placeholder="One item per line, e.g. Update CV"
        aria-label="Checklist items"
      />
      <div v-else class="flex flex-wrap items-center gap-2 text-sm text-slate-700 dark:text-slate-300">
        <InputText
          v-model="noteText"
          :maxlength="MAX_NOTE_LENGTH"
          placeholder="Note, e.g. Follow up"
          class="min-w-0 flex-1"
          size="small"
          aria-label="Note"
        />
        <span>remind in</span>
        <InputText v-model="days" inputmode="numeric" class="w-20" size="small" aria-label="Days from now" />
        <span>days, at the current time</span>
        <label class="flex w-full items-center gap-2">
          <Checkbox v-model="removeOnLeave" binary />
          Remove the note when the task leaves the column
        </label>
      </div>
      <div>
        <Button type="submit" label="Add automation" icon="pi pi-plus" size="small" :disabled="!canAdd" :loading="busy" />
      </div>
    </form>
  </div>
</template>
