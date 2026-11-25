<script setup>
import { computed, onMounted, ref } from 'vue'
import InputText from 'primevue/inputtext'
import Button from 'primevue/button'
import Checkbox from 'primevue/checkbox'
import { useConfirm } from 'primevue/useconfirm'
import { useToast } from 'primevue/usetoast'
import { useBoardStore } from '@/stores/board'
import { fromApi, formatShort } from '@/utils/date'

// Creates, shows and revokes the read-only share link of a board. Part of the board settings.
const props = defineProps({ board: { type: Object, required: true } }) // { id, name }

const store = useBoardStore()
const confirm = useConfirm()
const toast = useToast()

const share = ref(null) // { path, created, showAttachments, showNotes, showHistory, hideNames } or null; path is "<board-slug>-<key>"
const loading = ref(true)
const busy = ref(false)
const urlInput = ref()

const url = computed(() => (share.value ? `${window.location.origin}/share/${share.value.path}` : ''))
const created = computed(() => fromApi(share.value?.created))

function showError(summary, err) {
  toast.add({ severity: 'error', summary, detail: err.message, life: 5000 })
}

onMounted(async () => {
  loading.value = true
  try {
    share.value = await store.fetchShare(props.board.id)
  } catch (err) {
    showError('Could not load share link', err)
  } finally {
    loading.value = false
  }
})

async function createLink() {
  busy.value = true
  try {
    share.value = await store.createShare(props.board.id)
  } catch (err) {
    showError('Could not create share link', err)
  } finally {
    busy.value = false
  }
}

// What the link shows besides the board. Changes apply to the current link right away.
const OPTIONS = [
  { key: 'showAttachments', label: 'Attachments', hint: 'Viewers can open and download the files.' },
  { key: 'showNotes', label: 'Notes', hint: 'Reminders stay private.' },
  { key: 'showHistory', label: 'History', hint: 'Who changed what and when.' },
]
// Only matters while one of the options above shows something with names.
const namesShown = computed(() => OPTIONS.some((o) => share.value?.[o.key]))

async function setOption(key, value) {
  const before = share.value[key]
  share.value = { ...share.value, [key]: value }
  try {
    share.value = await store.updateShareOptions(props.board.id, { [key]: value })
  } catch (err) {
    share.value = { ...share.value, [key]: before }
    showError('Could not save the setting', err)
  }
}

async function copyLink() {
  try {
    await navigator.clipboard.writeText(url.value)
    toast.add({ severity: 'success', summary: 'Link copied', life: 2000 })
  } catch {
    // Clipboard API needs https; let the user copy by hand.
    urlInput.value?.$el?.select()
    toast.add({ severity: 'info', summary: 'Press Ctrl+C to copy the link', life: 4000 })
  }
}

function askReplace() {
  confirm.require({
    header: 'Replace link?',
    message: 'The current link stops working. Anyone who should keep access needs the new link.',
    icon: 'pi pi-refresh',
    rejectProps: { label: 'Cancel', severity: 'secondary', text: true },
    acceptProps: { label: 'Replace link' },
    accept: createLink,
  })
}

function askStop() {
  confirm.require({
    header: 'Stop sharing?',
    message: 'The link stops working. You can create a new one later.',
    icon: 'pi pi-ban',
    rejectProps: { label: 'Cancel', severity: 'secondary', text: true },
    acceptProps: { label: 'Stop sharing', severity: 'danger' },
    accept: async () => {
      busy.value = true
      try {
        await store.revokeShare(props.board.id)
        share.value = null
        toast.add({ severity: 'success', summary: 'Sharing stopped', life: 2500 })
      } catch (err) {
        showError('Could not stop sharing', err)
      } finally {
        busy.value = false
      }
    },
  })
}
</script>

<template>
  <div class="flex flex-col gap-4">
    <p class="text-sm text-slate-600 dark:text-slate-400">
      Anyone with the link can view this board without logging in. They can't change anything.
    </p>

    <p v-if="loading" class="text-sm text-slate-400 dark:text-slate-500">Loading…</p>

    <Button
      v-else-if="!share"
      label="Create link"
      icon="pi pi-link"
      :loading="busy"
      class="self-start"
      @click="createLink"
    />

    <template v-else>
      <div class="flex gap-2">
        <InputText
          ref="urlInput"
          :value="url"
          readonly
          aria-label="Share link"
          fluid
          class="font-mono !text-xs"
          @focus="$event.target.select()"
        />
        <Button label="Copy" icon="pi pi-copy" class="shrink-0" @click="copyLink" />
      </div>
      <p v-if="created" class="text-xs text-slate-500 dark:text-slate-400">Created {{ formatShort(created) }}</p>
      <fieldset class="flex flex-col gap-2" :disabled="busy">
        <legend class="mb-2 text-sm font-medium text-slate-700 dark:text-slate-300">Also show in each task</legend>
        <div v-for="option in OPTIONS" :key="option.key" class="flex items-start gap-2">
          <Checkbox
            :modelValue="share[option.key]"
            binary
            :inputId="`share-${option.key}`"
            class="mt-0.5"
            @update:modelValue="setOption(option.key, $event)"
          />
          <label :for="`share-${option.key}`" class="flex flex-col text-sm text-slate-700 dark:text-slate-300">
            {{ option.label }}
            <span class="text-xs text-slate-500 dark:text-slate-400">{{ option.hint }}</span>
          </label>
        </div>
        <div class="flex items-start gap-2">
          <Checkbox
            :modelValue="share.hideNames"
            binary
            inputId="share-hideNames"
            class="mt-0.5"
            :disabled="!namesShown"
            @update:modelValue="setOption('hideNames', $event)"
          />
          <label for="share-hideNames" class="flex flex-col text-sm text-slate-700 dark:text-slate-300" :class="{ 'opacity-60': !namesShown }">
            Hide names
            <span class="text-xs text-slate-500 dark:text-slate-400">Viewers don't see who uploaded, wrote or changed something.</span>
          </label>
        </div>
      </fieldset>
      <div class="flex flex-wrap gap-2">
        <Button
          label="Replace link"
          icon="pi pi-refresh"
          severity="secondary"
          text
          size="small"
          :disabled="busy"
          @click="askReplace"
        />
        <Button
          label="Stop sharing"
          icon="pi pi-ban"
          severity="danger"
          text
          size="small"
          :disabled="busy"
          @click="askStop"
        />
      </div>
    </template>
  </div>
</template>
