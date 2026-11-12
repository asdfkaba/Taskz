<script setup>
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import InputText from 'primevue/inputtext'
import Button from 'primevue/button'
import { useToast } from 'primevue/usetoast'
import { useBoardStore } from '@/stores/board'
import TaskTemplateEditor from '@/components/TaskTemplateEditor.vue'
import LabelPicker from '@/components/LabelPicker.vue'
import SharePanel from '@/components/SharePanel.vue'
import BoardMembers from '@/components/BoardMembers.vue'
import BoardRules from '@/components/BoardRules.vue'

// Settings of the open board, shown by BoardView at /boards/<slug>/settings.
// Deleting is only asked for here; BoardView owns the delete dialog.
const emit = defineEmits(['delete'])

// Same limit as BoardService.MAX_NAME_LENGTH.
const MAX_NAME_LENGTH = 50

const store = useBoardStore()
const router = useRouter()
const toast = useToast()

// { id, name, slug } of the open board, from the board list.
const boardSummary = computed(() => store.boards.find((b) => b.id === store.board?.id) ?? null)

const name = ref(store.board?.name ?? '')
const renaming = ref(false)
watch(
  () => store.board?.name,
  (current) => (name.value = current ?? ''),
)
const canRename = computed(() => {
  const trimmed = name.value.trim()
  return trimmed.length > 0 && trimmed !== store.board?.name && !renaming.value
})

async function rename() {
  if (!canRename.value) return
  renaming.value = true
  try {
    const board = await store.renameBoard(store.board.id, name.value.trim())
    router.replace({ name: 'board-settings', params: { slug: board.slug } })
    toast.add({ severity: 'success', summary: 'Board renamed', life: 2500 })
  } catch (err) {
    toast.add({ severity: 'error', summary: 'Could not rename the board', detail: err.message, life: 5000 })
  } finally {
    renaming.value = false
  }
}
</script>

<template>
  <div class="flex max-w-4xl flex-col gap-4" data-testid="board-settings">
    <section class="flex flex-col gap-3 rounded-xl border bg-white p-4 dark:bg-slate-900 border-slate-200 dark:border-slate-800" aria-labelledby="settings-name">
      <h2 id="settings-name" class="text-base font-semibold text-slate-900 dark:text-slate-100">Name</h2>
      <form class="flex gap-2" @submit.prevent="rename">
        <InputText v-model="name" :maxlength="MAX_NAME_LENGTH" aria-label="Board name" fluid />
        <Button type="submit" label="Rename" class="shrink-0" :disabled="!canRename" :loading="renaming" />
      </form>
      <p class="text-xs text-slate-500 dark:text-slate-400">
        The board's address changes with its name. Share links keep working.
      </p>
    </section>

    <section class="flex flex-col gap-3 rounded-xl border bg-white p-4 dark:bg-slate-900 border-slate-200 dark:border-slate-800" aria-labelledby="settings-template">
      <h2 id="settings-template" class="text-base font-semibold text-slate-900 dark:text-slate-100">Task template</h2>
      <TaskTemplateEditor :key="store.board?.id" />
    </section>

    <section class="flex flex-col gap-3 rounded-xl border bg-white p-4 dark:bg-slate-900 border-slate-200 dark:border-slate-800" aria-labelledby="settings-labels">
      <h2 id="settings-labels" class="text-base font-semibold text-slate-900 dark:text-slate-100">Labels</h2>
      <p class="text-sm text-slate-600 dark:text-slate-400">Labels can also be created while editing a task.</p>
      <LabelPicker :selectable="false" />
    </section>

    <section class="flex flex-col gap-3 rounded-xl border bg-white p-4 dark:bg-slate-900 border-slate-200 dark:border-slate-800" aria-labelledby="settings-members">
      <h2 id="settings-members" class="text-base font-semibold text-slate-900 dark:text-slate-100">Members</h2>
      <BoardMembers v-if="boardSummary" :key="boardSummary.id" :board="boardSummary" />
    </section>

    <!-- Automations, sharing and deleting are for the owner only. -->
    <section v-if="boardSummary?.own" class="flex flex-col gap-3 rounded-xl border bg-white p-4 dark:bg-slate-900 border-slate-200 dark:border-slate-800" aria-labelledby="settings-rules">
      <h2 id="settings-rules" class="text-base font-semibold text-slate-900 dark:text-slate-100">Automations</h2>
      <BoardRules :key="boardSummary.id" :boardId="boardSummary.id" />
    </section>

    <section v-if="boardSummary?.own" class="flex flex-col gap-3 rounded-xl border bg-white p-4 dark:bg-slate-900 border-slate-200 dark:border-slate-800" aria-labelledby="settings-share">
      <h2 id="settings-share" class="text-base font-semibold text-slate-900 dark:text-slate-100">Share link</h2>
      <!-- Remounted after a rename, so the link shows the new name. -->
      <SharePanel v-if="boardSummary" :key="boardSummary.slug" :board="boardSummary" />
    </section>

    <section v-if="boardSummary?.own" class="flex flex-col gap-3 rounded-xl border bg-white p-4 dark:bg-slate-900 border-red-200 dark:border-red-900/60" aria-labelledby="settings-delete">
      <h2 id="settings-delete" class="text-base font-semibold text-red-700 dark:text-red-400">Delete board</h2>
      <div class="flex flex-wrap items-center justify-between gap-3">
        <p class="text-sm text-slate-600 dark:text-slate-400">Deletes the board with all its columns, tasks and labels.</p>
        <Button
          label="Delete board"
          icon="pi pi-trash"
          severity="danger"
          outlined
          :disabled="!boardSummary"
          @click="emit('delete', boardSummary)"
        />
      </div>
    </section>
  </div>
</template>

