<script setup>
import { computed, nextTick, reactive, ref, watch } from 'vue'
import Dialog from 'primevue/dialog'
import InputText from 'primevue/inputtext'
import Textarea from 'primevue/textarea'
import DatePicker from 'primevue/datepicker'
import Button from 'primevue/button'
import Message from 'primevue/message'
import { useConfirm } from 'primevue/useconfirm'
import { useToast } from 'primevue/usetoast'
import { useBoardStore } from '@/stores/board'
import { fromApi, toApi } from '@/utils/date'
import { applyCheckbox, renderMarkdown } from '@/utils/markdown'
import LabelPicker from '@/components/LabelPicker.vue'
import MarkdownHelp from '@/components/MarkdownHelp.vue'
import LabelPill from '@/components/LabelPill.vue'
import TaskDetails from '@/components/TaskDetails.vue'
import TaskHistory from '@/components/TaskHistory.vue'
import TaskNotes from '@/components/TaskNotes.vue'
import TaskAttachments from '@/components/TaskAttachments.vue'

// Shows a task (view) and edits or creates it (edit) in one dialog.

// Same limit as the backend (TaskController.MAX_DESCRIPTION_LENGTH).
const MAX_DESCRIPTION_LENGTH = 10000

const visible = defineModel('visible', { type: Boolean, default: false })
const props = defineProps({
  // null = create a new task, otherwise the task to show or edit
  task: { type: Object, default: null },
  // Mode the dialog opens in for an existing task: 'view' | 'edit'
  initialMode: { type: String, default: 'view' },
  // Column a new task is created in
  columnId: { type: Number, default: null },
})

const store = useBoardStore()
const confirm = useConfirm()
const toast = useToast()

const isNew = computed(() => props.task === null)
const mode = ref('view') // 'view' | 'edit'
// Opened in view: Save and Cancel go back to view. Opened in edit: they close the dialog.
const openedInView = ref(false)

// The store replaces a task object when it is saved, so look up the current one.
const current = computed(() => (props.task ? (store.findTask(props.task.id)?.task ?? props.task) : null))
const column = computed(() => store.columns.find((c) => c.id === current.value?.columnId) ?? null)
const labels = computed(() => (current.value ? store.labelsOf(current.value) : []))

const busy = ref(false)
const submitted = ref(false)
const form = reactive({ title: '', description: '', dueDate: null, labelIds: [] })
const labelPickerOpen = ref(false)
const formLabels = computed(() => store.labels.filter((l) => form.labelIds.includes(l.id)))
let initialForm = ''
// The task's revision when editing started; the server refuses the save if it changed since.
let baseRevision = null
const isDirty = computed(() => JSON.stringify(form) !== initialForm)
const titleMissing = computed(() => submitted.value && !form.title.trim())

const descriptionMode = ref('write') // 'write' | 'preview'
const previewHtml = computed(() => renderMarkdown(form.description))

const MODE_OPTIONS = [
  { value: 'view', label: 'View', icon: 'pi pi-eye' },
  { value: 'edit', label: 'Edit', icon: 'pi pi-pencil' },
]

function fillForm() {
  const t = current.value
  submitted.value = false
  descriptionMode.value = 'write'
  form.title = t?.title ?? ''
  // A new task starts with the board's template.
  form.description = t ? (t.description ?? '') : (store.taskTemplate ?? '')
  form.dueDate = fromApi(t?.due_date)
  form.labelIds = [...(t?.labelIds ?? [])].sort((a, b) => a - b)
  labelPickerOpen.value = false
  initialForm = JSON.stringify(form)
  baseRevision = t?.revision ?? null
}

watch(visible, (open) => {
  if (!open) return
  mode.value = isNew.value ? 'edit' : props.initialMode
  openedInView.value = mode.value === 'view'
  fillForm()
})

const titleInput = ref()
async function startEditing() {
  fillForm()
  mode.value = 'edit'
  await nextTick()
  titleInput.value?.$el?.focus()
}

function backToView() {
  if (!isDirty.value) {
    mode.value = 'view'
    return
  }
  confirm.require({
    header: 'Discard changes?',
    message: 'Your changes to this task are not saved.',
    icon: 'pi pi-exclamation-triangle',
    rejectProps: { label: 'Keep editing', severity: 'secondary', text: true },
    acceptProps: { label: 'Discard', severity: 'danger' },
    accept: () => (mode.value = 'view'),
  })
}

function switchMode(target) {
  if (target === mode.value) return
  if (target === 'edit') startEditing()
  else backToView()
}

// Ticking a checkbox in the view saves right away; sub-items and parents follow (see applyCheckbox).
const checking = ref(false)
async function onToggleCheckbox(index) {
  const task = current.value
  const change = applyCheckbox(task.description ?? '', index)
  if (change === null) {
    toast.add({ severity: 'error', summary: 'Could not find that checkbox', life: 5000 })
    return
  }
  checking.value = true
  try {
    await store.toggleCheckbox(task.id, change.description, change.line)
  } catch (err) {
    if (err.status === 409) {
      toast.add({ severity: 'warn', summary: 'The task was changed elsewhere', detail: 'Loaded the current version.', life: 5000 })
      await store.fetchBoard(store.board.id).catch(() => {})
    } else {
      toast.add({ severity: 'error', summary: 'Could not save the checkbox', detail: err.message, life: 5000 })
    }
  } finally {
    checking.value = false
  }
}

function cancel() {
  if (openedInView.value) backToView()
  else visible.value = false
}

// The calendar's "Clear" button doesn't empty the field by itself (PrimeVue 4.3 resets it to its
// current value), so the template sets the date to null on clear-click.

// Without a previous value the picker takes the current time (e.g. 23:07).
// Noon is a more useful default for a freshly picked day.
const dueWasEmpty = ref(false)
function onDateSelect(date) {
  if (!dueWasEmpty.value) return
  dueWasEmpty.value = false
  const noon = new Date(date)
  noon.setHours(12, 0, 0, 0)
  form.dueDate = noon
}

// overwrite: save even though someone else changed the task since editing started.
async function save(overwrite = false) {
  submitted.value = true
  if (!form.title.trim()) return

  const data = {
    title: form.title.trim(),
    description: form.description.trim(),
    due_date: toApi(form.dueDate),
    labelIds: form.labelIds,
  }
  if (!isNew.value && !overwrite) data.revision = baseRevision

  busy.value = true
  try {
    if (isNew.value) {
      await store.createTask(props.columnId, data)
      toast.add({ severity: 'success', summary: 'Task created', life: 2500 })
      visible.value = false
    } else {
      await store.updateTask(props.task.id, data)
      toast.add({ severity: 'success', summary: 'Task updated', life: 2500 })
      if (openedInView.value) mode.value = 'view'
      else visible.value = false
    }
  } catch (err) {
    if (err.status === 409) askOverwrite()
    else toast.add({ severity: 'error', summary: 'Could not save task', detail: err.message, life: 5000 })
  } finally {
    busy.value = false
  }
}

// Someone else saved the task while it was being edited here.
function askOverwrite() {
  confirm.require({
    header: 'Changed in the meantime',
    message: 'Someone else saved this task while you were editing it. Save yours over theirs, or drop yours and see their version?',
    icon: 'pi pi-exclamation-triangle',
    rejectProps: { label: 'See their version', severity: 'secondary', text: true },
    acceptProps: { label: 'Save mine' },
    accept: () => save(true),
    reject: async () => {
      // Their version, even if no live update brought it yet.
      await store.refreshBoard()
      fillForm()
      if (openedInView.value) mode.value = 'view'
    },
  })
}
</script>

<template>
  <Dialog
    v-model:visible="visible"
    modal
    :style="{ width: '40rem' }"
    :breakpoints="{ '640px': '95vw' }"
    :draggable="false"
  >
    <template #header>
      <span v-if="isNew" class="p-dialog-title">New task</span>
      <!--
        The toggle is the header of an existing task; the mode tells which one it is.
        Own buttons instead of SelectButton: that one shows the new choice right away,
        even when switching is cancelled in the "Discard changes?" question.
      -->
      <div
        v-else
        role="group"
        aria-label="Dialog mode"
        class="mr-3 flex flex-1 gap-1 rounded-lg bg-slate-100 p-1 dark:bg-slate-800"
      >
        <button
          v-for="option in MODE_OPTIONS"
          :key="option.value"
          type="button"
          class="flex flex-1 items-center justify-center gap-2 rounded-md py-2 text-sm font-medium transition-colors"
          :class="
            mode === option.value
              ? 'bg-white text-slate-900 shadow-sm dark:bg-slate-700 dark:text-slate-100'
              : 'text-slate-500 hover:text-slate-800 dark:text-slate-400 dark:hover:text-slate-200'
          "
          :aria-pressed="mode === option.value"
          @click="switchMode(option.value)"
        >
          <i :class="option.icon" aria-hidden="true" />
          {{ option.label }}
        </button>
      </div>
    </template>

    <div v-if="mode === 'view' && current" class="flex flex-col gap-4">
      <TaskDetails
        :task="current"
        :column="column"
        :labels="labels"
        canEdit
        :checking="checking"
        @edit="startEditing"
        @toggle-checkbox="onToggleCheckbox"
      />
      <TaskAttachments :taskId="current.id" />
      <TaskNotes :taskId="current.id" />
      <TaskHistory :task="current" />
    </div>

    <!-- Edit / create -->
    <form v-else id="task-form" class="flex flex-col gap-5 pt-1" @submit.prevent="save()">
      <div class="flex flex-col gap-1.5">
        <label for="task-title" class="text-sm font-medium text-slate-700 dark:text-slate-300">Title</label>
        <InputText
          id="task-title"
          ref="titleInput"
          v-model="form.title"
          :invalid="titleMissing"
          maxlength="255"
          placeholder="What needs to be done?"
          autofocus
          fluid
        />
        <Message v-if="titleMissing" severity="error" size="small" variant="simple">
          Please enter a title.
        </Message>
      </div>

      <div class="flex flex-col gap-1.5">
        <div class="flex items-end justify-between gap-2">
          <label for="task-description" class="text-sm font-medium text-slate-700 dark:text-slate-300">
            Description <span class="font-normal text-slate-400 dark:text-slate-500">(optional)</span>
          </label>
          <div role="group" aria-label="Description mode" class="flex rounded-md bg-slate-100 dark:bg-slate-800 p-0.5 text-xs">
            <button
              v-for="m in ['write', 'preview']"
              :key="m"
              type="button"
              class="rounded px-2 py-0.5 font-medium capitalize"
              :class="descriptionMode === m ? 'bg-white dark:bg-slate-700 text-slate-800 dark:text-slate-200 shadow-sm' : 'text-slate-500 dark:text-slate-400 hover:text-slate-700 dark:hover:text-slate-300'"
              :aria-pressed="descriptionMode === m"
              @click="descriptionMode = m"
            >
              {{ m }}
            </button>
          </div>
        </div>
        <Textarea
          v-show="descriptionMode === 'write'"
          id="task-description"
          v-model="form.description"
          rows="6"
          autoResize
          :maxlength="MAX_DESCRIPTION_LENGTH"
          placeholder="Add some details…"
          class="max-h-[50vh] font-mono !text-sm"
          fluid
        />
        <div
          v-if="descriptionMode === 'preview'"
          data-testid="description-preview"
          class="max-h-[50vh] min-h-24 overflow-y-auto rounded-md border border-slate-200 dark:border-slate-700 px-3 py-2"
        >
          <!-- Sanitized in renderMarkdown. -->
          <div v-if="previewHtml" class="markdown" v-html="previewHtml" />
          <p v-else class="text-sm text-slate-400 dark:text-slate-500">Nothing to preview</p>
        </div>
        <MarkdownHelp />
      </div>

      <div class="flex flex-col gap-1.5">
        <div class="flex items-center justify-between gap-2">
          <span id="task-labels" class="text-sm font-medium text-slate-700 dark:text-slate-300">
            Labels <span class="font-normal text-slate-400 dark:text-slate-500">(optional)</span>
          </span>
          <Button
            :label="labelPickerOpen ? 'Done' : 'Edit labels'"
            :icon="labelPickerOpen ? 'pi pi-check' : 'pi pi-tag'"
            text
            size="small"
            severity="secondary"
            :aria-expanded="labelPickerOpen"
            @click="labelPickerOpen = !labelPickerOpen"
          />
        </div>
        <div v-if="!labelPickerOpen" class="flex min-h-6 flex-wrap gap-1" aria-labelledby="task-labels">
          <LabelPill v-for="label in formLabels" :key="label.id" :label="label" />
          <span v-if="!formLabels.length" class="text-sm text-slate-400 dark:text-slate-500">No labels</span>
        </div>
        <LabelPicker v-else v-model="form.labelIds" />
      </div>

      <div class="flex flex-col gap-1.5">
        <div class="flex items-center justify-between gap-2">
          <label for="task-due" class="text-sm font-medium text-slate-700 dark:text-slate-300">
            Due date <span class="font-normal text-slate-400 dark:text-slate-500">(optional)</span>
          </label>
          <Button
            v-if="form.dueDate"
            label="Remove"
            icon="pi pi-times"
            text
            size="small"
            severity="secondary"
            aria-label="Remove due date"
            @click="form.dueDate = null"
          />
        </div>
        <DatePicker
          v-model="form.dueDate"
          inputId="task-due"
          dateFormat="yy-mm-dd"
          showTime
          hourFormat="24"
          :stepMinute="15"
          showIcon
          iconDisplay="input"
          showButtonBar
          :manualInput="false"
          placeholder="No due date"
          fluid
          @show="dueWasEmpty = !form.dueDate"
          @date-select="onDateSelect"
          @clear-click="form.dueDate = null"
        />
      </div>

    </form>

    <template #footer>
      <Button v-if="mode === 'view'" label="Close" severity="secondary" text @click="visible = false" />
      <template v-else>
        <Button label="Cancel" severity="secondary" text @click="cancel" />
        <Button
          type="submit"
          form="task-form"
          :label="isNew ? 'Create task' : 'Save changes'"
          icon="pi pi-check"
          :loading="busy"
        />
      </template>
    </template>
  </Dialog>
</template>
