<script setup>
import UserIcon from '@/components/UserIcon.vue'
import { ref, watch } from 'vue'
import { format, formatDistanceToNow } from 'date-fns'
import Button from 'primevue/button'
import DatePicker from 'primevue/datepicker'
import Textarea from 'primevue/textarea'
import { useConfirm } from 'primevue/useconfirm'
import { useToast } from 'primevue/usetoast'
import { useBoardStore } from '@/stores/board'
import { formatDue, fromApi, toApi } from '@/utils/date'

// Plain-text notes on a task, newest first. Only for the owner; shared boards don't show them.
// A note can have a reminder: it then shows in its author's "What's due" until marked done.
const MAX_LENGTH = 5000 // same as TaskNoteService.MAX_LENGTH

const props = defineProps({ taskId: { type: Number, required: true } })

const store = useBoardStore()
const confirm = useConfirm()
const toast = useToast()

const notes = ref([])
const status = ref('loading') // 'loading' | 'ok' | 'error'
const draft = ref('')
const draftRemindAt = ref(null)
const adding = ref(false)
const editingId = ref(null)
const editText = ref('')
const editRemindAt = ref(null)
const saving = ref(false)

// A first pick of a day means noon, like the due date in TaskDialog; the time can be changed after.
function atNoonIfNew(before, date) {
  if (before) return date
  const noon = new Date(date)
  noon.setHours(12, 0, 0, 0)
  return noon
}

async function load() {
  status.value = 'loading'
  try {
    notes.value = await store.fetchNotes(props.taskId)
    status.value = 'ok'
  } catch {
    status.value = 'error'
  }
}
watch(() => props.taskId, load, { immediate: true })

function showError(summary, err) {
  toast.add({ severity: 'error', summary, detail: err.message, life: 5000 })
}

async function add() {
  if (!draft.value.trim() || adding.value) return
  adding.value = true
  try {
    notes.value.unshift(await store.addNote(props.taskId, draft.value, toApi(draftRemindAt.value)))
    draft.value = ''
    draftRemindAt.value = null
  } catch (err) {
    showError('Could not add note', err)
  } finally {
    adding.value = false
  }
}

function startEdit(note) {
  editingId.value = note.id
  editText.value = note.text
  editRemindAt.value = fromApi(note.remindAt)
}

async function saveEdit(note) {
  if (!editText.value.trim() || saving.value) return
  saving.value = true
  try {
    const updated = await store.updateNote(note.id, editText.value, toApi(editRemindAt.value))
    notes.value.splice(notes.value.indexOf(note), 1, updated)
    editingId.value = null
  } catch (err) {
    showError('Could not save note', err)
  } finally {
    saving.value = false
  }
}

async function reminderDone(note) {
  try {
    const updated = await store.updateNote(note.id, note.text, null)
    notes.value.splice(notes.value.indexOf(note), 1, updated)
  } catch (err) {
    showError('Could not change the reminder', err)
  }
}

function askDelete(note) {
  confirm.require({
    header: 'Delete note?',
    message: 'The note is deleted for good.',
    icon: 'pi pi-trash',
    rejectProps: { label: 'Cancel', severity: 'secondary', text: true },
    acceptProps: { label: 'Delete note', severity: 'danger' },
    accept: async () => {
      try {
        await store.deleteNote(note.id)
        notes.value = notes.value.filter((n) => n.id !== note.id)
      } catch (err) {
        showError('Could not delete note', err)
      }
    },
  })
}

const relative = (at) => formatDistanceToNow(fromApi(at), { addSuffix: true })
const absolute = (at) => format(fromApi(at), 'yyyy-MM-dd HH:mm')
</script>

<template>
  <section class="flex flex-col gap-3 border-t border-slate-200 pt-3 dark:border-slate-800" data-testid="task-notes">
    <h3 class="text-sm font-medium text-slate-600 dark:text-slate-400">
      Notes <span v-if="notes.length" class="font-normal text-slate-400 dark:text-slate-500">({{ notes.length }})</span>
    </h3>

    <form class="flex flex-col gap-2" @submit.prevent="add">
      <Textarea
        v-model="draft"
        rows="2"
        autoResize
        :maxlength="MAX_LENGTH"
        placeholder="Add a note…"
        aria-label="New note"
        class="max-h-60 !text-sm"
        fluid
        @keydown.enter.ctrl.prevent="add"
        @keydown.enter.meta.prevent="add"
      />
      <div v-if="draft.trim()" class="flex flex-wrap items-center justify-end gap-2">
        <DatePicker
          :modelValue="draftRemindAt"
          inputId="note-remind"
          dateFormat="yy-mm-dd"
          showTime
          hourFormat="24"
          :stepMinute="15"
          showIcon
          iconDisplay="input"
          showButtonBar
          :manualInput="false"
          placeholder="No reminder"
          ariaLabel="Remind me"
          size="small"
          class="w-52"
          @update:modelValue="(date) => (draftRemindAt = date && atNoonIfNew(draftRemindAt, date))"
        />
        <span class="ml-auto text-xs text-slate-400 dark:text-slate-500">Ctrl+Enter</span>
        <Button type="submit" label="Add note" size="small" :loading="adding" />
      </div>
    </form>

    <p v-if="status === 'loading' && !notes.length" class="text-sm text-slate-400">Loading…</p>
    <p v-else-if="status === 'error'" class="text-sm text-red-600 dark:text-red-400">Could not load the notes.</p>

    <ol v-else class="flex flex-col gap-3">
      <li
        v-for="note in notes"
        :key="note.id"
        class="group rounded-md bg-slate-50 px-3 py-2 dark:bg-slate-800/60"
        data-testid="note"
      >
        <template v-if="editingId === note.id">
          <Textarea
            v-model="editText"
            rows="2"
            autoResize
            :maxlength="MAX_LENGTH"
            aria-label="Edit note"
            class="max-h-60 !text-sm"
            fluid
            @keydown.enter.ctrl.prevent="saveEdit(note)"
            @keydown.enter.meta.prevent="saveEdit(note)"
            @keydown.esc.stop="editingId = null"
          />
          <div class="mt-2 flex flex-wrap items-center justify-end gap-2">
            <DatePicker
              :modelValue="editRemindAt"
              inputId="note-edit-remind"
              dateFormat="yy-mm-dd"
              showTime
              hourFormat="24"
              :stepMinute="15"
              showIcon
              iconDisplay="input"
              showButtonBar
              :manualInput="false"
              placeholder="No reminder"
              ariaLabel="Edit reminder"
              size="small"
              class="w-52"
              @update:modelValue="(date) => (editRemindAt = date && atNoonIfNew(editRemindAt, date))"
            />
            <span class="flex-1" />
            <Button label="Cancel" text size="small" severity="secondary" @click="editingId = null" />
            <Button label="Save" size="small" :disabled="!editText.trim()" :loading="saving" @click="saveEdit(note)" />
          </div>
        </template>
        <template v-else>
          <p class="text-sm break-words whitespace-pre-wrap text-slate-700 dark:text-slate-300">{{ note.text }}</p>
          <p v-if="note.remindAt" class="mt-1 flex items-center gap-2 text-xs font-medium text-amber-700 dark:text-amber-400" data-testid="note-reminder">
            <i class="pi pi-bell text-xs" aria-hidden="true" />
            <span>Reminder {{ formatDue(fromApi(note.remindAt)) }}</span>
            <Button v-if="note.canEdit" label="Done" text size="small" class="!px-1 !py-0" @click="reminderDone(note)" />
          </p>
          <div class="mt-1 flex items-center gap-2 text-xs text-slate-400 dark:text-slate-500">
            <span>
              <span v-if="note.user" data-testid="note-user"><UserIcon :seed="note.userId" size="xs" class="-mt-0.5 mr-1" />{{ note.user }} · </span>
              <time :datetime="absolute(note.created)" :title="absolute(note.created)">{{ relative(note.created) }}</time>
              <span v-if="note.edited" :title="`Edited ${absolute(note.edited)}`"> · edited</span>
            </span>
            <span class="ml-auto flex gap-1 transition-opacity group-focus-within:opacity-100 group-hover:opacity-100 [@media(hover:hover)]:opacity-0">
              <Button
                icon="pi pi-pencil"
                text
                rounded
                size="small"
                severity="secondary"
                v-if="note.canEdit"
                aria-label="Edit note"
                @click="startEdit(note)"
              />
              <Button
                icon="pi pi-trash"
                text
                rounded
                size="small"
                severity="danger"
                v-if="note.canDelete"
                aria-label="Delete note"
                @click="askDelete(note)"
              />
            </span>
          </div>
        </template>
      </li>
    </ol>
  </section>
</template>
