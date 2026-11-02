<script setup>
import { ref, watch } from 'vue'
import { format, formatDistanceToNow } from 'date-fns'
import Button from 'primevue/button'
import { useConfirm } from 'primevue/useconfirm'
import { useToast } from 'primevue/usetoast'
import { useBoardStore } from '@/stores/board'
import { fromApi } from '@/utils/date'

// Files attached to a task, newest first. Only for the owner; shared boards don't show them.
// The backend checks type and size by content (TaskAttachmentService); the checks here only save
// a pointless upload.
const MAX_SIZE = 10 * 1024 * 1024 // attachments.max-file-size
const ACCEPT = '.png,.jpg,.jpeg,.gif,.webp,.pdf,.txt,.md,.csv'

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

async function load() {
  status.value = 'loading'
  try {
    attachments.value = await store.fetchAttachments(props.taskId)
    status.value = 'ok'
  } catch {
    status.value = 'error'
  }
}
watch(() => props.taskId, load, { immediate: true })

function showError(summary, err) {
  toast.add({ severity: 'error', summary, detail: err.message, life: 6000 })
}

// One after the other, so the storage limit is checked against the files before.
async function upload(files) {
  for (const file of files) {
    if (file.size > MAX_SIZE) {
      showError(`Could not attach ${file.name}`, new Error('Files can be at most 10 MB'))
      continue
    }
    const pending = { id: nextUploadId++, name: file.name }
    uploading.value.push(pending)
    try {
      attachments.value.unshift(await store.uploadAttachment(props.taskId, file))
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

const url = (attachment) => `/api/attachments/${attachment.id}`
const icon = (attachment) => (attachment.contentType === 'application/pdf' ? 'pi-file-pdf' : 'pi-file')
const relative = (at) => formatDistanceToNow(fromApi(at), { addSuffix: true })
const absolute = (at) => format(fromApi(at), 'yyyy-MM-dd HH:mm')

function size(bytes) {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}
</script>

<template>
  <section
    class="flex flex-col gap-3 border-t border-slate-200 pt-3 dark:border-slate-800"
    :class="{ 'rounded-sm bg-emerald-50 outline-2 outline-offset-4 outline-emerald-400 outline-dashed dark:bg-emerald-950/30': dragging }"
    data-testid="task-attachments"
    @dragover="onDragOver"
    @dragleave.self="dragging = false"
    @drop.prevent="onDrop"
  >
    <div class="flex items-center gap-2">
      <h3 class="text-sm font-medium text-slate-600 dark:text-slate-400">
        Attachments
        <span v-if="attachments.length" class="font-normal text-slate-400 dark:text-slate-500">({{ attachments.length }})</span>
      </h3>
      <Button
        label="Add file"
        icon="pi pi-paperclip"
        text
        size="small"
        severity="secondary"
        class="ml-auto"
        @click="fileInput.click()"
      />
      <input ref="fileInput" type="file" multiple :accept="ACCEPT" class="hidden" aria-label="Attach files" @change="onPick" />
    </div>

    <p v-if="status === 'loading' && !attachments.length" class="text-sm text-slate-400">Loading…</p>
    <p v-else-if="status === 'error'" class="text-sm text-red-600 dark:text-red-400">Could not load the attachments.</p>
    <p
      v-else-if="!attachments.length && !uploading.length"
      class="text-xs text-slate-400 dark:text-slate-500"
    >
      Drop files here: images, PDF or text, up to 10 MB.
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
          >{{ attachment.name }}</a>
          <span class="text-xs text-slate-400 dark:text-slate-500">
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
  </section>
</template>
