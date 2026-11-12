<script setup>
import { computed, onMounted, ref } from 'vue'
import Button from 'primevue/button'
import Checkbox from 'primevue/checkbox'
import InputText from 'primevue/inputtext'
import MultiSelect from 'primevue/multiselect'
import Select from 'primevue/select'
import Textarea from 'primevue/textarea'
import { useToast } from 'primevue/usetoast'
import { useBoardStore } from '@/stores/board'

// Automations of the open board: when a task enters a column, change its checklist, add a note with a
// reminder (written as whoever moved the task, so the reminder is theirs) or notify people of the board.
// A task can also be archived once it was in the column for some days (done hourly by the backend).
// Part of the board settings, owner only. Column and action of a rule are fixed; its settings can be edited.
const props = defineProps({ boardId: { type: Number, required: true } })

// Same limits as BoardRuleService and TaskNoteService.
const MAX_ITEMS = 20
const MAX_DAYS = 365
const MAX_NOTE_LENGTH = 5000
const MAX_NOTIFICATION_LENGTH = 500
const ACTIONS = [
  { value: 'APPEND_CHECKLIST', label: 'Add checklist items' },
  { value: 'REPLACE_CHECKLIST', label: 'Replace the checklist' },
  { value: 'ADD_NOTE_REMINDER', label: 'Add a note with a reminder' },
  { value: 'NOTIFY', label: 'Notify people' },
  { value: 'ARCHIVE_AFTER', label: 'Archive it after some days' },
]
// Which settings an action has: 'items', 'note', 'notify' or 'archive'.
const KINDS = { ADD_NOTE_REMINDER: 'note', NOTIFY: 'notify', ARCHIVE_AFTER: 'archive' }
const kindOf = (ruleAction) => KINDS[ruleAction] ?? 'items'

const store = useBoardStore()
const toast = useToast()

const rules = ref([])
// Owner and members ([{ userId, name, owner }]), whom a NOTIFY rule can tell.
const people = ref([])
const loading = ref(true)
const busy = ref(false)

const columnId = ref(store.columns[0]?.id ?? null)
const action = ref('APPEND_CHECKLIST')
// The fields of a rule's settings, for the new rule and the one being edited.
const emptyFields = () => ({ itemsText: '', noteText: '', days: '14', removeOnLeave: false, notifyText: '', userIds: [], archiveDays: '30' })
const fields = ref(emptyFields())

const toItems = (text) => text.split('\n').map((line) => line.trim()).filter(Boolean)
const toDays = (text) => (/^\d+$/.test(text.trim()) ? Number(text.trim()) : null)
// The settings the backend expects for the action, or null if they aren't valid yet.
function settings(ruleAction, f) {
  const kind = kindOf(ruleAction)
  if (kind === 'items') {
    const list = toItems(f.itemsText)
    return list.length > 0 && list.length <= MAX_ITEMS ? { items: list } : null
  }
  if (kind === 'notify') {
    const text = f.notifyText.trim()
    return text && f.userIds.length ? { text, userIds: f.userIds } : null
  }
  if (kind === 'archive') {
    const days = toDays(f.archiveDays)
    return days != null && days >= 1 && days <= MAX_DAYS ? { days } : null
  }
  const n = toDays(f.days)
  return f.noteText.trim() && n != null && n <= MAX_DAYS ? { text: f.noteText.trim(), days: n, removeOnLeave: f.removeOnLeave } : null
}

const newSettings = computed(() => settings(action.value, fields.value))
const canAdd = computed(() => !busy.value && columnId.value != null && newSettings.value != null)

// The rule being edited: { id, ...fields }, or null.
const editing = ref(null)
const editSettings = computed(() => {
  const e = editing.value
  const rule = e && rules.value.find((r) => r.id === e.id)
  return rule ? settings(rule.action, e) : null
})

// Rules grouped under their column, in the board's column order.
const byColumn = computed(() =>
  store.columns
    .map((column) => ({ column, rules: rules.value.filter((r) => r.columnId === column.id) }))
    .filter((group) => group.rules.length > 0),
)

// Names of the people a NOTIFY rule tells; someone who left the board is no longer told.
function names(userIds) {
  return userIds.map((id) => people.value.find((p) => p.userId === id)?.name ?? 'someone who left').join(', ')
}

function describe(rule) {
  if (rule.action === 'APPEND_CHECKLIST') return `Add checklist items: ${rule.items.join(', ')}`
  if (rule.action === 'REPLACE_CHECKLIST') return `Replace the checklist with: ${rule.items.join(', ')}`
  if (rule.action === 'ARCHIVE_AFTER') return `Archive it after ${rule.days} ${rule.days === 1 ? 'day' : 'days'} in the column`
  if (rule.action === 'NOTIFY') return `Notify ${names(rule.userIds)}: “${rule.text}”`
  const removed = rule.removeOnLeave ? ', removed when the task leaves' : ''
  return `Add the note “${rule.text}” with a reminder in ${rule.days} ${rule.days === 1 ? 'day' : 'days'}${removed}`
}

function showError(summary, err) {
  toast.add({ severity: 'error', summary, detail: err.message, life: 5000 })
}

onMounted(async () => {
  try {
    ;[rules.value, people.value] = await Promise.all([store.fetchRules(props.boardId), store.fetchMembers(props.boardId)])
    people.value = people.value.map((p) => ({ ...p, name: p.name ?? 'Someone' }))
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
    fields.value = emptyFields()
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
    ...emptyFields(),
    id: rule.id,
    itemsText: (rule.items ?? []).join('\n'),
    noteText: rule.text ?? '',
    days: String(rule.days ?? ''),
    archiveDays: String(rule.days ?? ''),
    removeOnLeave: rule.removeOnLeave,
    notifyText: rule.text ?? '',
    userIds: [...(rule.userIds ?? [])],
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
      task leaves the column. Notifying skips whoever moved the task; it shows under their bell for the others.
      Archiving happens once the task has been in the column for the given days, checked every hour; a task
      that leaves the column earlier stays on the board.
    </p>

    <p v-if="loading" class="text-sm text-slate-400">Loading…</p>
    <p v-else-if="!rules.length" class="text-sm text-slate-500 dark:text-slate-400">No automations yet.</p>
    <div v-for="group in byColumn" :key="group.column.id" class="flex flex-col gap-1">
      <h3 class="text-sm font-medium text-slate-800 dark:text-slate-200">When a task enters {{ group.column.name }}</h3>
      <ul class="flex flex-col">
        <li v-for="rule in group.rules" :key="rule.id" class="py-1" data-testid="rule">
          <form v-if="editing?.id === rule.id" class="flex flex-col gap-2" @submit.prevent="saveEdit(rule)" @keydown.esc.stop="editing = null">
            <Textarea
              v-if="kindOf(rule.action) === 'items'"
              v-model="editing.itemsText"
              rows="3"
              autoResize
              aria-label="Edit checklist items"
            />
            <div v-else-if="kindOf(rule.action) === 'notify'" class="flex flex-col gap-2 text-sm text-slate-700 dark:text-slate-300">
              <InputText v-model="editing.notifyText" :maxlength="MAX_NOTIFICATION_LENGTH" size="small" aria-label="Edit message" />
              <MultiSelect
                v-model="editing.userIds"
                :options="people"
                optionLabel="name"
                optionValue="userId"
                display="chip"
                placeholder="Whom to notify"
                aria-label="Edit whom to notify"
                size="small"
              />
            </div>
            <div v-else-if="kindOf(rule.action) === 'archive'" class="flex flex-wrap items-center gap-2 text-sm text-slate-700 dark:text-slate-300">
              <span>Archive it after</span>
              <InputText v-model="editing.archiveDays" inputmode="numeric" class="w-20" size="small" aria-label="Edit days in the column" />
              <span>days in the column</span>
            </div>
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
        v-if="kindOf(action) === 'items'"
        v-model="fields.itemsText"
        rows="3"
        autoResize
        placeholder="One item per line, e.g. Update CV"
        aria-label="Checklist items"
      />
      <div v-else-if="kindOf(action) === 'notify'" class="flex flex-col gap-2 text-sm text-slate-700 dark:text-slate-300">
        <InputText
          v-model="fields.notifyText"
          :maxlength="MAX_NOTIFICATION_LENGTH"
          placeholder="Message, e.g. Ready for review"
          size="small"
          aria-label="Message"
        />
        <MultiSelect
          v-model="fields.userIds"
          :options="people"
          optionLabel="name"
          optionValue="userId"
          display="chip"
          placeholder="Whom to notify"
          aria-label="Whom to notify"
          size="small"
        />
      </div>
      <div v-else-if="kindOf(action) === 'archive'" class="flex flex-wrap items-center gap-2 text-sm text-slate-700 dark:text-slate-300">
        <span>after</span>
        <InputText v-model="fields.archiveDays" inputmode="numeric" class="w-20" size="small" aria-label="Days in the column" />
        <span>days in the column</span>
      </div>
      <div v-else class="flex flex-wrap items-center gap-2 text-sm text-slate-700 dark:text-slate-300">
        <InputText
          v-model="fields.noteText"
          :maxlength="MAX_NOTE_LENGTH"
          placeholder="Note, e.g. Follow up"
          class="min-w-0 flex-1"
          size="small"
          aria-label="Note"
        />
        <span>remind in</span>
        <InputText v-model="fields.days" inputmode="numeric" class="w-20" size="small" aria-label="Days from now" />
        <span>days, at the current time</span>
        <label class="flex w-full items-center gap-2">
          <Checkbox v-model="fields.removeOnLeave" binary />
          Remove the note when the task leaves the column
        </label>
      </div>
      <div>
        <Button type="submit" label="Add automation" icon="pi pi-plus" size="small" :disabled="!canAdd" :loading="busy" />
      </div>
    </form>
  </div>
</template>
