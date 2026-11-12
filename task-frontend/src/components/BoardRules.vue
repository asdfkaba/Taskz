<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import Button from 'primevue/button'
import Checkbox from 'primevue/checkbox'
import InputText from 'primevue/inputtext'
import MultiSelect from 'primevue/multiselect'
import Select from 'primevue/select'
import Textarea from 'primevue/textarea'
import { useToast } from 'primevue/usetoast'
import { useBoardStore } from '@/stores/board'

// Automations of the open board. A rule belongs to a column and runs on a trigger (see TRIGGERS):
// when a task enters the column, change its checklist, add a note with a reminder (written as whoever
// moved the task, so the reminder is theirs) or notify people of the board; when it stayed there for
// some days (checked hourly by the backend), archive it, move it or notify; when its checklist gets
// completed there, move it or notify.
// Part of the board settings, owner only. Column, trigger and action of a rule are fixed; its settings can be edited.
const props = defineProps({ boardId: { type: Number, required: true } })

// Same limits as BoardRuleService and TaskNoteService.
const MAX_ITEMS = 20
const MAX_DAYS = 365
const MAX_NOTE_LENGTH = 5000
const MAX_NOTIFICATION_LENGTH = 500
// hint: the fine print, shown under the form only while the action (or trigger) is chosen.
const ACTIONS = [
  { value: 'APPEND_CHECKLIST', label: 'Add checklist items', hint: 'Items the checklist already has are skipped.' },
  { value: 'REPLACE_CHECKLIST', label: 'Replace the checklist', hint: 'Items that are in both lists stay ticked.' },
  { value: 'ADD_NOTE_REMINDER', label: 'Add a note with a reminder', hint: "The note is written as whoever moved the task, and the reminder shows in their “What's due”." },
  { value: 'NOTIFY', label: 'Notify people', hint: 'It shows under their bell; whoever moved the task is not told.' },
  { value: 'ARCHIVE', label: 'Archive it', hint: "The task can be restored from the board's archive." },
  { value: 'MOVE', label: 'Move it to another column', hint: 'The task goes to the end of that column, and its rules for entering run.' },
]
// When a rule runs and which actions it can have then; same as BoardRule.Trigger. label continues
// "When a task ...", heading is the same for the list of rules.
const TRIGGERS = [
  { value: 'ENTERED_COLUMN', label: 'enters', heading: 'enters', hint: 'Runs when a task is moved into the column or created in it.', actions: ['APPEND_CHECKLIST', 'REPLACE_CHECKLIST', 'ADD_NOTE_REMINDER', 'NOTIFY'] },
  { value: 'IN_COLUMN_FOR', label: 'stays in', heading: 'stays in', hint: 'Checked every hour; a task that leaves the column earlier is left alone.', actions: ['ARCHIVE', 'NOTIFY', 'MOVE'] },
  { value: 'CHECKLIST_DONE', label: 'completes its checklist in', heading: 'completes its checklist in', hint: 'Runs when someone ticks the last open item.', actions: ['MOVE', 'NOTIFY'] },
]
// Which settings an action has: 'items', 'note', 'notify', 'move' or 'none'.
const KINDS = { ADD_NOTE_REMINDER: 'note', NOTIFY: 'notify', MOVE: 'move', ARCHIVE: 'none' }
const kindOf = (ruleAction) => KINDS[ruleAction] ?? 'items'

const store = useBoardStore()
const toast = useToast()

const rules = ref([])
// Owner and members ([{ userId, name, owner }]), whom a NOTIFY rule can tell.
const people = ref([])
const loading = ref(true)
const busy = ref(false)

const columnId = ref(store.columns[0]?.id ?? null)
const trigger = ref('ENTERED_COLUMN')
const action = ref('APPEND_CHECKLIST')
const actionOptions = computed(() => {
  // In the trigger's own order, so its most likely action comes first.
  const allowed = TRIGGERS.find((t) => t.value === trigger.value).actions
  return allowed.map((value) => ACTIONS.find((a) => a.value === value))
})
watch(trigger, () => {
  if (!actionOptions.value.some((a) => a.value === action.value)) action.value = actionOptions.value[0].value
})
const hint = computed(() =>
  [TRIGGERS.find((t) => t.value === trigger.value), ACTIONS.find((a) => a.value === action.value)].map((x) => x.hint).join(' '),
)
// Where a rule of the given column can move tasks: any other column.
const targetsFor = (ownColumnId) => store.columns.filter((c) => c.id !== ownColumnId)
// The fields of a rule's settings, for the new rule and the one being edited.
// stayDays: the days of the trigger IN_COLUMN_FOR; days: those of a note's reminder.
const emptyFields = () => ({ itemsText: '', noteText: '', days: '14', removeOnLeave: false, notifyText: '', userIds: [], stayDays: '30', targetColumnId: null })
const fields = ref(emptyFields())

const toItems = (text) => text.split('\n').map((line) => line.trim()).filter(Boolean)
const toDays = (text) => (/^\d+$/.test(text.trim()) ? Number(text.trim()) : null)
// The settings the backend expects for the trigger and action of a rule in the given column, or
// null if they aren't valid yet.
function settings(ruleTrigger, ruleAction, ownColumnId, f) {
  if (ruleTrigger !== 'IN_COLUMN_FOR') return actionSettings(ruleAction, ownColumnId, f)
  const days = toDays(f.stayDays)
  const rest = actionSettings(ruleAction, ownColumnId, f)
  return rest && days != null && days >= 1 && days <= MAX_DAYS ? { ...rest, days } : null
}

function actionSettings(ruleAction, ownColumnId, f) {
  const kind = kindOf(ruleAction)
  if (kind === 'none') return {}
  if (kind === 'move') {
    return targetsFor(ownColumnId).some((c) => c.id === f.targetColumnId) ? { targetColumnId: f.targetColumnId } : null
  }
  if (kind === 'items') {
    const list = toItems(f.itemsText)
    return list.length > 0 && list.length <= MAX_ITEMS ? { items: list } : null
  }
  if (kind === 'notify') {
    const text = f.notifyText.trim()
    return text && f.userIds.length ? { text, userIds: f.userIds } : null
  }
  const n = toDays(f.days)
  return f.noteText.trim() && n != null && n <= MAX_DAYS ? { text: f.noteText.trim(), days: n, removeOnLeave: f.removeOnLeave } : null
}

const newSettings = computed(() => settings(trigger.value, action.value, columnId.value, fields.value))
const canAdd = computed(() => !busy.value && columnId.value != null && newSettings.value != null)

// The rule being edited: { id, ...fields }, or null.
const editing = ref(null)
const editSettings = computed(() => {
  const e = editing.value
  const rule = e && rules.value.find((r) => r.id === e.id)
  return rule ? settings(rule.trigger, rule.action, rule.columnId, e) : null
})

// Rules grouped under their column and trigger, in the board's column order.
const groups = computed(() =>
  store.columns
    .flatMap((column) =>
      TRIGGERS.map((t) => ({
        key: `${column.id}-${t.value}`,
        heading: `When a task ${t.heading} ${column.name}`,
        rules: rules.value.filter((r) => r.columnId === column.id && r.trigger === t.value),
      })),
    )
    .filter((group) => group.rules.length > 0),
)

// Names of the people a NOTIFY rule tells; someone who left the board is no longer told.
function names(userIds) {
  return userIds.map((id) => people.value.find((p) => p.userId === id)?.name ?? 'someone who left').join(', ')
}

function describe(rule) {
  const text = describeAction(rule)
  if (rule.trigger !== 'IN_COLUMN_FOR') return text
  return `After ${rule.days} ${rule.days === 1 ? 'day' : 'days'}: ${text[0].toLowerCase()}${text.slice(1)}`
}

function describeAction(rule) {
  if (rule.action === 'ARCHIVE') return 'Archive it'
  if (rule.action === 'MOVE') return `Move it to ${store.columnById(rule.targetColumnId)?.name ?? 'a deleted column'}`
  if (rule.action === 'APPEND_CHECKLIST') return `Add checklist items: ${rule.items.join(', ')}`
  if (rule.action === 'REPLACE_CHECKLIST') return `Replace the checklist with: ${rule.items.join(', ')}`
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
    rules.value.push(await store.addRule(props.boardId, { columnId: columnId.value, trigger: trigger.value, action: action.value, ...newSettings.value }))
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
    stayDays: String(rule.days ?? ''),
    targetColumnId: rule.targetColumnId ?? null,
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
      Rules that run by themselves when something happens to a task in a column.
    </p>

    <p v-if="loading" class="text-sm text-slate-400">Loading…</p>
    <p v-else-if="!rules.length" class="text-sm text-slate-500 dark:text-slate-400">No automations yet.</p>
    <div v-for="group in groups" :key="group.key" class="flex flex-col gap-1">
      <h3 class="text-sm font-medium text-slate-800 dark:text-slate-200">{{ group.heading }}</h3>
      <ul class="flex flex-col">
        <li v-for="rule in group.rules" :key="rule.id" class="py-1" data-testid="rule">
          <form v-if="editing?.id === rule.id" class="flex flex-col gap-2" @submit.prevent="saveEdit(rule)" @keydown.esc.stop="editing = null">
            <div v-if="rule.trigger === 'IN_COLUMN_FOR'" class="flex flex-wrap items-center gap-2 text-sm text-slate-700 dark:text-slate-300">
              <span>After</span>
              <InputText v-model="editing.stayDays" inputmode="numeric" class="w-20" size="small" aria-label="Edit days in the column" />
              <span>days in the column</span>
            </div>
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
            <div v-else-if="kindOf(rule.action) === 'move'" class="flex flex-wrap items-center gap-2 text-sm text-slate-700 dark:text-slate-300">
              <span>Move it to</span>
              <Select v-model="editing.targetColumnId" :options="targetsFor(rule.columnId)" optionLabel="name" optionValue="id" aria-label="Edit target column" size="small" />
            </div>
            <div v-else-if="kindOf(rule.action) === 'note'" class="flex flex-wrap items-center gap-2 text-sm text-slate-700 dark:text-slate-300">
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
        <span>When a task</span>
        <Select v-model="trigger" :options="TRIGGERS" optionLabel="label" optionValue="value" aria-label="Trigger" size="small" />
        <Select v-model="columnId" :options="store.columns" optionLabel="name" optionValue="id" aria-label="Column" size="small" />
        <template v-if="trigger === 'IN_COLUMN_FOR'">
          <span>for</span>
          <InputText v-model="fields.stayDays" inputmode="numeric" class="w-20" size="small" aria-label="Days in the column" />
          <span>days</span>
        </template>
        <Select v-model="action" :options="actionOptions" optionLabel="label" optionValue="value" aria-label="Action" size="small" />
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
      <div v-else-if="kindOf(action) === 'move'" class="flex flex-wrap items-center gap-2 text-sm text-slate-700 dark:text-slate-300">
        <span>Move it to</span>
        <Select
          v-model="fields.targetColumnId"
          :options="targetsFor(columnId)"
          optionLabel="name"
          optionValue="id"
          placeholder="Column"
          aria-label="Target column"
          size="small"
        />
      </div>
      <div v-else-if="kindOf(action) === 'note'" class="flex flex-wrap items-center gap-2 text-sm text-slate-700 dark:text-slate-300">
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
      <p class="text-xs text-slate-500 dark:text-slate-400" data-testid="rule-hint">{{ hint }}</p>
      <div>
        <Button type="submit" label="Add automation" icon="pi pi-plus" size="small" :disabled="!canAdd" :loading="busy" />
      </div>
    </form>
  </div>
</template>
