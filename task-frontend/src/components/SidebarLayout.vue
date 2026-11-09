<script setup>
import { onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import Button from 'primevue/button'
import Drawer from 'primevue/drawer'
import { useToast } from 'primevue/usetoast'
import { useBoardStore } from '@/stores/board'
import BoardList from '@/components/BoardList.vue'
import BoardDialog from '@/components/BoardDialog.vue'
import BoardDeleteDialog from '@/components/BoardDeleteDialog.vue'
import { readStorage, writeStorage } from '@/utils/storage'

// The board list beside the logged-in pages (boards, What's due, settings): a sidebar that can be
// collapsed on wide screens, a drawer on small ones. The slot gets openBoards (the drawer), createBoard
// and deleteBoard (the dialogs), for buttons on the page.
const store = useBoardStore()
const route = useRoute()
const router = useRouter()
const toast = useToast()

const SIDEBAR_KEY = 'boardSidebarCollapsed'
const collapsed = ref(readStorage(SIDEBAR_KEY) === '1')
watch(collapsed, (value) => writeStorage(SIDEBAR_KEY, value ? '1' : '0'))

const drawerVisible = ref(false)
const boardDialogVisible = ref(false)
const deleteBoardVisible = ref(false)
const deletingBoard = ref(null)

// The board view loads the list itself (and reports errors there); the other pages need it here.
onMounted(async () => {
  if (store.boardsLoaded || route.name === 'board' || route.name === 'board-settings') return
  try {
    await store.fetchBoards()
  } catch (err) {
    if (err.name !== 'AuthError') toast.add({ severity: 'error', summary: 'Could not load boards', detail: err.message, life: 5000 })
  }
})

function openCreateBoard() {
  drawerVisible.value = false
  boardDialogVisible.value = true
}

function askDeleteBoard(board) {
  drawerVisible.value = false
  deletingBoard.value = board
  deleteBoardVisible.value = true
}

function onBoardDeleted(board) {
  if (route.params.slug === board.slug) router.replace({ name: 'board' })
}
</script>

<template>
  <div class="flex min-h-[calc(100vh-3.5rem)]">
    <aside
      class="sticky top-14 hidden h-[calc(100vh-3.5rem)] shrink-0 flex-col border-r border-slate-200 dark:border-slate-800 bg-white dark:bg-slate-900 md:flex"
      :class="collapsed ? 'w-12 items-center py-3' : 'w-64 p-3'"
    >
      <Button
        v-if="collapsed"
        icon="pi pi-angle-double-right"
        text
        rounded
        severity="secondary"
        aria-label="Show boards"
        title="Show boards"
        @click="collapsed = false"
      />
      <template v-else>
        <div class="mb-2 flex items-center justify-between pl-3">
          <span class="text-xs font-semibold tracking-wide text-slate-500 dark:text-slate-400 uppercase">Boards</span>
          <Button
            icon="pi pi-angle-double-left"
            text
            rounded
            severity="secondary"
            size="small"
            aria-label="Hide boards"
            title="Hide boards"
            @click="collapsed = true"
          />
        </div>
        <BoardList @create="openCreateBoard" @delete="askDeleteBoard" />
      </template>
    </aside>

    <Drawer v-model:visible="drawerVisible" header="Boards" class="!w-72">
      <BoardList @create="openCreateBoard" @delete="askDeleteBoard" @navigate="drawerVisible = false" />
    </Drawer>

    <slot :openBoards="() => (drawerVisible = true)" :createBoard="openCreateBoard" :deleteBoard="askDeleteBoard" />

    <BoardDialog v-model:visible="boardDialogVisible" />
    <BoardDeleteDialog v-model:visible="deleteBoardVisible" :board="deletingBoard" @deleted="onBoardDeleted" />
  </div>
</template>
