<script setup>
import UserIcon from '@/components/UserIcon.vue'
import { computed, onMounted, ref, watch } from 'vue'
import { format, formatDistanceToNow } from 'date-fns'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import { useConfirm } from 'primevue/useconfirm'
import { useToast } from 'primevue/usetoast'
import { useBoardStore } from '@/stores/board'
import { fromApi } from '@/utils/date'
import { renderMarkdown } from '@/utils/markdown'

// Files attached to a task, newest first. Only for the owner; shared boards don't show them.
// The backend checks type and size (TaskAttachmentService) and says why it refuses a file. Its
// size limit and accepted types come from /attachments/settings, so a too large file is refused
// here before it is sent. Text and Markdown files open in a preview; everything else in a new tab.

const props = defineProps({ taskId: { type: Number, required: true } })

const store = useBoardStore()
const confirm = useConfirm()
const toast = useToast()

const attachments = ref([])
const status = ref('loading') // 'loading' | 'ok' | 'error'
const uploading = ref([]) // { id, name } of files being uploaded
let nextUploadId = 0
const dragging = ref(false)
const fileInput = ref(null)
const settings = ref(null) // { maxFileSize, types, extensions }; null until loaded
const accept = computed(() => settings.value?.extensions.map((e) => `.${e}`).join(',') ?? '')
const tooLarge = () => `Files can be at most ${size(settings.value.maxFileSize)}`

// Without the settings the backend still checks everything; only the hint and the early check are missing.
onMounted(async () => {
  try {
    settings.value = await store.fetchAttachmentSettings()
  } catch {
    settings.value = null
  }
})

// Answers for a task that is no longer shown (the dialog switched tasks) are dropped.
async function load() {
  const taskId = props.taskId
  status.value = 'loading'
  try {
    const list = await store.fetchAttachments(taskId)
    if (taskId !== props.taskId) return
    attachments.value = list
    status.value = 'ok'
  } catch {
    if (taskId === props.taskId) status.value = 'error'
  }
}
watch(() => props.taskId, load, { immediate: true })

function showError(summary, err) {
  toast.add({ severity: 'error', summary, detail: err.message, life: 6000 })
}

// One after the other, so the storage limit is checked against the files before.
async function upload(files) {
  const taskId = props.taskId
  for (const file of files) {
    if (taskId !== props.taskId) return
    if (settings.value && file.size > settings.value.maxFileSize) {
      showError(`Could not attach ${file.name}`, new Error(tooLarge()))
      continue
    }
    const pending = { id: nextUploadId++, name: file.name }
    uploading.value.push(pending)
    try {
      const attachment = await store.uploadAttachment(taskId, file)
      if (taskId === props.taskId) attachments.value.unshift(attachment)
    } catch (err) {
      showError(`Could not attach ${file.name}`, err)
    } finally {
      uploading.value = uploading.value.filter((u) => u.id !== pending.id)
    }
  }
}

function onPick(event) {
  upload([...event.target.files])
  event.target.value = '' // picking the same file again fires change again
}

function onDrop(event) {
  dragging.value = false
  upload([...event.dataTransfer.files])
}

// Leaving for a child element is no leaving; relatedTarget is null when the drag leaves the
// window or is cancelled.
function onDragLeave(event) {
  if (!event.currentTarget.contains(event.relatedTarget)) dragging.value = false
}

// Only react to dragged files, not to dragged text or cards.
function onDragOver(event) {
  if (!event.dataTransfer.types.includes('Files')) return
  event.preventDefault()
  dragging.value = true
}

function askDelete(attachment) {
  confirm.require({
    header: 'Delete attachment?',
    message: `${attachment.name} is deleted for good.`,
    icon: 'pi pi-trash',
    rejectProps: { label: 'Cancel', severity: 'secondary', text: true },
    acceptProps: { label: 'Delete attachment', severity: 'danger' },
    accept: async () => {
      try {
        await store.deleteAttachment(props.taskId, attachment.id)
        attachments.value = attachments.value.filter((a) => a.id !== attachment.id)
      } catch (err) {
        showError('Could not delete attachment', err)
      }
    },
  })
}

// Larger text files open in a new tab instead: rendering megabytes of Markdown would freeze the page.
const MAX_PREVIEW_SIZE = 1024 * 1024
const markdown = (attachment) => attachment.contentType.startsWith('text/markdown')
const previewable = (attachment) =>
  (markdown(attachment) || attachment.contentType.startsWith('text/plain')) && attachment.size <= MAX_PREVIEW_SIZE

const preview = ref(null) // { attachment, text }; text is null while loading
let previewRequest = 0 // answers for an earlier preview are dropped
const previewBody = ref(null)
const previewHtml = computed(() => (preview.value && markdown(preview.value.attachment) ? renderMarkdown(preview.value.text) : ''))

async function open(event, attachment) {
  // Ctrl/Cmd or middle click keep the browser's own "open in new tab".
  if (!previewable(attachment) || event.ctrlKey || event.metaKey || event.shiftKey || event.button !== 0) return
  event.preventDefault()
  const request = ++previewRequest
  preview.value = { attachment, text: null }
  try {
    const text = await store.fetchAttachmentText(attachment.id)
    if (request === previewRequest && preview.value) preview.value = { attachment, text }
  } catch (err) {
    if (request === previewRequest) preview.value = null
    showError(`Could not open ${attachment.name}`, err)
  }
}

const url = (attachment) => `/api/attachments/${attachment.id}`
const icon = (attachment) => (attachment.contentType === 'application/pdf' ? 'pi-file-pdf' : 'pi-file')
const relative = (at) => formatDistanceToNow(fromApi(at), { addSuffix: true })
const absolute = (at) => format(fromApi(at), 'yyyy-MM-dd HH:mm')

function size(bytes) {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`
  return `${+(bytes / (1024 * 1024)).toFixed(1)} MB` // "10 MB", not "10.0 MB"
}
</script>

<template>
  <section
    class="flex flex-col gap-3 border-t border-slate-200 pt-3 dark:border-slate-800"
    :class="{ 'rounded-sm bg-emerald-50 outline-2 outline-offset-4 outline-emerald-400 outline-dashed dark:bg-emerald-950/30': dragging }"
    data-testid="task-attachments"
    @dragover="onDragOver"
    @dragleave="onDragLeave"
    @drop.prevent="onDrop"
  >
    <div class="flex items-center gap-2">
      <div class="flex flex-col">
        <h3 class="text-sm font-medium text-slate-600 dark:text-slate-400">
          Attachments
          <span v-if="attachments.length" class="font-normal text-slate-400 dark:text-slate-500">({{ attachments.length }})</span>
        </h3>
        <p v-if="settings" class="text-[0.6875rem] text-slate-400 dark:text-slate-500" data-testid="allowed-types">
          {{ settings.types }} · up to {{ size(settings.maxFileSize) }}
        </p>
      </div>
      <Button
        label="Add file"
        icon="pi pi-paperclip"
        text
        size="small"
        severity="secondary"
        class="ml-auto"
        @click="fileInput.click()"
      />
      <input ref="fileInput" type="file" multiple :accept="accept" class="hidden" aria-label="Attach files" @change="onPick" />
    </div>

    <p v-if="status === 'loading' && !attachments.length" class="text-sm text-slate-400">Loading…</p>
    <p v-else-if="status === 'error'" class="text-sm text-red-600 dark:text-red-400">Could not load the attachments.</p>
    <p
      v-else-if="!attachments.length && !uploading.length"
      class="text-xs text-slate-400 dark:text-slate-500"
    >
      Drop files here.
    </p>

    <ul v-if="attachments.length || uploading.length" class="flex flex-col gap-1">
      <li
        v-for="pending in uploading"
        :key="`uploading-${pending.id}`"
        class="flex items-center gap-3 px-1 py-1 text-sm text-slate-500 dark:text-slate-400"
        data-testid="uploading"
      >
        <i class="pi pi-spinner pi-spin w-10 text-center" aria-hidden="true" />
        <span class="truncate">{{ pending.name }}</span>
        <span class="sr-only">uploading</span>
      </li>
      <li
        v-for="attachment in attachments"
        :key="attachment.id"
        class="group flex items-center gap-3 rounded-md px-1 py-1 hover:bg-slate-50 dark:hover:bg-slate-800/60"
        data-testid="attachment"
      >
        <a
          :href="url(attachment)"
          target="_blank"
          rel="noopener"
          class="flex h-10 w-10 shrink-0 items-center justify-center overflow-hidden rounded border border-slate-200 bg-slate-50 dark:border-slate-700 dark:bg-slate-800"
          tabindex="-1"
          aria-hidden="true"
          @click="open($event, attachment)"
        >
          <img v-if="attachment.image" :src="url(attachment)" alt="" loading="lazy" class="h-full w-full object-cover" />
          <i v-else class="pi text-lg text-slate-400" :class="icon(attachment)" />
        </a>
        <div class="flex min-w-0 flex-col">
          <a
            :href="url(attachment)"
            target="_blank"
            rel="noopener"
            class="truncate text-sm text-slate-700 hover:underline dark:text-slate-200"
            :title="attachment.name"
            @click="open($event, attachment)"
          >{{ attachment.name }}</a>
          <span class="text-xs text-slate-400 dark:text-slate-500">
            <span v-if="attachment.user" data-testid="attachment-user"><UserIcon :seed="attachment.userId" size="xs" class="-mt-0.5 mr-1" />{{ attachment.user }} · </span>
            {{ size(attachment.size) }} ·
            <time :datetime="absolute(attachment.created)" :title="absolute(attachment.created)">{{ relative(attachment.created) }}</time>
          </span>
        </div>
        <Button
          icon="pi pi-trash"
          text
          rounded
          size="small"
          severity="danger"
          :aria-label="`Delete ${attachment.name}`"
          class="ml-auto shrink-0 transition-opacity group-focus-within:opacity-100 group-hover:opacity-100 [@media(hover:hover)]:opacity-0"
          @click="askDelete(attachment)"
        />
      </li>
    </ul>

    <Dialog
      :visible="!!preview"
      modal
      dismissableMask
      :closeOnEscape="false"
      :header="preview?.attachment.name"
      class="w-[48rem] max-w-[calc(100vw-2rem)]"
      data-testid="attachment-preview"
      @update:visible="(v) => !v && (preview = null)"
      @keydown.esc.stop="preview = null"
      @show="previewBody?.focus()"
    >
      <!-- Escape is handled here and kept from the document, where the task dialog would close too.
           For that, focus has to be in here; PrimeVue leaves it on the link that opened the preview. -->
      <div ref="previewBody" tabindex="-1" class="outline-none">
        <p v-if="preview?.text == null" class="text-sm text-slate-400">Loading…</p>
        <!-- Sanitized in renderMarkdown. -->
        <div v-else-if="previewHtml" class="markdown markdown-large" v-html="previewHtml" />
        <pre v-else class="font-mono text-sm break-words whitespace-pre-wrap text-slate-800 dark:text-slate-200">{{ preview.text }}</pre>
      </div>
      <template #footer>
        <a
          v-if="preview"
          :href="url(preview.attachment)"
          target="_blank"
          rel="noopener"
          class="text-sm text-blue-600 hover:underline dark:text-blue-400"
        >Open in new tab</a>
      </template>
    </Dialog>
  </section>
</template>
