<script setup>
import { computed, defineAsyncComponent, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { VueDraggable } from 'vue-draggable-plus'
import Button from 'primevue/button'
import Skeleton from 'primevue/skeleton'
import Menu from 'primevue/menu'
import { useConfirm } from 'primevue/useconfirm'
import { useToast } from 'primevue/usetoast'
import { useBoardStore } from '@/stores/board'
import { useAuthStore } from '@/stores/auth'
import TaskCard from '@/components/TaskCard.vue'
import BoardFilter from '@/components/BoardFilter.vue'
import { useBoardFilter } from '@/composables/useBoardFilter'
import TaskDialog from '@/components/TaskDialog.vue'
import ColumnDialog from '@/components/ColumnDialog.vue'
import ColumnDeleteDialog from '@/components/ColumnDeleteDialog.vue'
import BoardArchive from '@/components/BoardArchive.vue'
import SidebarLayout from '@/components/SidebarLayout.vue'
import BoardSettings from '@/components/BoardSettings.vue'
import UserIcon from '@/components/UserIcon.vue'
import { usePageTitle } from '@/utils/title'
import { readStorage, writeStorage } from '@/utils/storage'
import { useBoardViewPrefs } from '@/composables/useBoardViewPrefs'
import { useBoardEvents } from '@/composables/useBoardEvents'
import ViewSwitch from '@/components/ViewSwitch.vue'

// PrimeVue's DataTable is large; load it only when someone opens the table view.
const TaskTable = defineAsyncComponent(() => import('@/components/TaskTable.vue'))

const store = useBoardStore()
const route = useRoute()
const router = useRouter()
const confirm = useConfirm()
const toast = useToast()

function showError(summary, err) {
  toast.add({ severity: 'error', summary, detail: err.message, life: 5000 })
}

const LAST_BOARD_KEY = 'lastBoardId'

function handleLoadError(summary, err) {
  if (err.name === 'AuthError') {
    router.push('/')
    return
  }
  showError(summary, err)
}

// Opens the board from the URL slug. Without one, it opens the last used board, or else the first one.
// The board list maps the slug to the id the API works with.
async function openBoardFromRoute() {
  if (!['board', 'board-settings', 'board-archive'].includes(route.name)) return
  try {
    if (!store.boardsLoaded) await store.fetchBoards()
  } catch (err) {
    handleLoadError('Could not load boards', err)
    return
  }

  const slug = route.params.slug
  if (!slug) {
    const last = Number(readStorage(LAST_BOARD_KEY))
    const target = store.boards.find((b) => b.id === last) ?? store.boards[0]
    if (target) {
      router.replace({ name: 'board', params: { slug: target.slug } })
    } else {
      store.closeBoard()
    }
    return
  }

  const board = store.boards.find((b) => b.slug === slug)
  if (!board) {
    toast.add({ severity: 'warn', summary: 'Board not found', life: 4000 })
    router.replace({ name: 'board' })
    return
  }

  writeStorage(LAST_BOARD_KEY, String(board.id))
  // Same board under a new slug (renamed): it is loaded already.
  if (store.loaded && store.board?.id === board.id) {
    openTaskFromQuery()
    return
  }
  try {
    await store.fetchBoard(board.id)
    if (store.board?.id === board.id) openTaskFromQuery()
  } catch (err) {
    if (err.status === 404) {
      // Deleted in the meantime, e.g. in another tab.
      store.boardsLoaded = false
      router.replace({ name: 'board' })
      return
    }
    handleLoadError('Could not load board', err)
  }
}

watch(() => route.params.slug, openBoardFromRoute, { immediate: true })
// Only ?task= changed (a notification about the open board): the board is there already. With a new
// slug as well, openBoardFromRoute opens the task once that board has loaded.
watch(
  () => route.query.task,
  (task) => {
    const routeBoard = store.boards.find((b) => b.slug === route.params.slug)
    if (task && store.loaded && routeBoard && routeBoard.id === store.board?.id) openTaskFromQuery()
  },
)

// /boards/<slug>?task=<id> (from "What's due") opens that task; the query is dropped again.
// Async: the first call can come while this component is still being set up (board already loaded).
async function openTaskFromQuery() {
  const id = Number(route.query.task)
  if (!id) return
  await nextTick()
  router.replace({ name: 'board', params: { slug: route.params.slug } })
  const found = store.findTask(id)
  if (found) openTask(found.task)
  else toast.add({ severity: 'warn', summary: 'Task not found', detail: 'It was moved to another board or deleted.', life: 4000 })
}

// Puts the open board back to the server state, e.g. after a failed move.
async function load() {
  if (!store.board) return
  try {
    await store.fetchBoard(store.board.id)
  } catch (err) {
    handleLoadError('Could not load board', err)
  }
}

// ---- Board or table view, compact cards ----
const { view, compact, mode, setMode } = useBoardViewPrefs(() => store.board?.id ?? null)
// All tasks that pass the filter, in board order (column by column).
const tableTasks = computed(() =>
  store.columns.flatMap((c) => store.tasksByColumn[c.id] ?? []).filter((t) => matchOf(t).matches),
)

// /boards/<slug>/settings shows the board's settings instead of its columns.
const settingsOpen = computed(() => route.name === 'board-settings')
// /boards/<slug>/archive shows the board's archived tasks.
const archiveOpen = computed(() => route.name === 'board-archive')
// Either of them: a page of the board without its tasks, with a way back.
const subPage = computed(() => settingsOpen.value || archiveOpen.value)

// Opens the settings or the archive; a second click on the open one goes back to the board.
function togglePage(name) {
  router.push({ name: route.name === name ? 'board' : name, params: { slug: route.params.slug } })
}

// From the settings or the archive, the view switch also leads back to the board.
function chooseMode(value) {
  setMode(value)
  if (subPage.value) router.push({ name: 'board', params: { slug: route.params.slug } })
}

// ---- Small screens: one column at a time, with tabs ----
// Matches Tailwind's md breakpoint (48rem).
const mobileQuery = window.matchMedia('(max-width: 47.99rem)')
const isMobile = ref(mobileQuery.matches)

// ---- Search and label filter ----
// Hidden cards stay in the draggable lists (v-show), so drag & drop keeps working on the full order.
const {
  text: filterText,
  labelIds: filterLabelIds,
  terms: filterTerms,
  active: filterActive,
  match: matchOf,
  clear: clearFilter,
} = useBoardFilter((task) => store.labelsOf(task))
const filterBar = ref(null)
const filterOpen = ref(false) // small screens: the search row is opened with the search button

// Columns without any task shrink to a narrow strip on wide screens (not with a search that only
// hides their tasks); a task moved or added in widens them again.
const isEmpty = (columnId) => !store.tasksByColumn[columnId]?.length
const visibleCount = (columnId) => (store.tasksByColumn[columnId] ?? []).filter((t) => matchOf(t).matches).length
const matchingCount = computed(() => store.columns.reduce((sum, c) => sum + visibleCount(c.id), 0))
// "3" without a filter, "1 / 3" with one.
const countLabel = (columnId) => {
  const total = (store.tasksByColumn[columnId] ?? []).length
  return filterActive.value ? `${visibleCount(columnId)} / ${total}` : `${total}`
}

async function toggleFilterRow() {
  filterOpen.value = !filterOpen.value
  if (filterOpen.value) {
    await nextTick()
    filterBar.value?.focus()
  }
}
const onMobileChange = (event) => (isMobile.value = event.matches)
mobileQuery.addEventListener('change', onMobileChange)
onBeforeUnmount(() => mobileQuery.removeEventListener('change', onMobileChange))

const boardScroller = ref(null)
const scrolledColumnId = ref(null)
const activeColumnId = computed(() => scrolledColumnId.value ?? store.columns[0]?.id ?? null)

// The column whose left edge is closest to the board's left edge is the one in view.
function updateActiveColumn() {
  const scroller = boardScroller.value
  if (!scroller) return
  const left = scroller.getBoundingClientRect().left + parseFloat(getComputedStyle(scroller).paddingLeft)
  let closest = null
  let distance = Infinity
  for (const el of scroller.querySelectorAll('[data-column-id]')) {
    const d = Math.abs(el.getBoundingClientRect().left - left)
    if (d < distance) {
      distance = d
      closest = Number(el.dataset.columnId)
    }
  }
  scrolledColumnId.value = closest
}

function scrollToColumn(id) {
  boardScroller.value
    ?.querySelector(`[data-column-id="${id}"]`)
    ?.scrollIntoView({ behavior: 'smooth', inline: 'start', block: 'nearest' })
}

// Keep the active tab visible in the tab bar.
const tabBar = ref(null)
watch(activeColumnId, async (id) => {
  await nextTick()
  tabBar.value?.querySelector(`[data-tab-column-id="${id}"]`)?.scrollIntoView({ inline: 'nearest', block: 'nearest' })
})

// A different board starts at its first column.
watch(
  () => store.board?.id,
  () => (scrolledColumnId.value = null),
)

function neighbours(index) {
  return { left: store.columns[index - 1] ?? null, right: store.columns[index + 1] ?? null }
}

// ---- Live updates and who else is here (see useBoardEvents) ----
const others = ref([]) // [{ id, name }] of the others who have this board open
// A refresh during a drag would pull the card from under the mouse: it waits for the drop.
let dragging = false
let refreshWaiting = false

async function liveRefresh() {
  if (dragging) {
    refreshWaiting = true
    return
  }
  const result = await store.refreshBoard()
  if (result === 'gone') {
    toast.add({ severity: 'warn', summary: 'This board is no longer available to you', life: 5000 })
    router.replace({ name: 'board' })
  }
  return result
}

const live = useBoardEvents({ refresh: liveRefresh, onPresence: (names) => (others.value = names) })
watch(
  () => (store.loaded ? store.board?.id : null),
  (id) => {
    others.value = []
    if (id) live.listen(`/api/boards/${id}/events`)
    else live.stop()
  },
  { immediate: true },
)
// Shown first and set apart, but only with others: alone, an icon of yourself says nothing.
const auth = useAuthStore()
const othersLabel = computed(() => `You and ${others.value.map((o) => o.name).join(', ')} have this board open`)
// At most five circles: you and up to four others, or you, three others and "+N" for the rest.
const shownOthers = computed(() => (others.value.length > 4 ? others.value.slice(0, 3) : others.value))
const hiddenOthers = computed(() => others.value.slice(shownOthers.value.length))

function dragStarted() {
  dragging = true
}

function dragEnded() {
  dragging = false
  if (refreshWaiting) {
    refreshWaiting = false
    liveRefresh()
  }
}

// ---- Drag & drop ----
// The draggable lists already changed the store arrays when "end" fires, so only the
// backend is left. If that fails, reloading the board puts everything back.

async function onTaskDragEnd(event) {
  dragEnded()
  if (event.from === event.to && event.oldIndex === event.newIndex) return
  try {
    await store.persistTaskMove(Number(event.item.dataset.taskId))
  } catch (err) {
    showError('Could not move task', err)
    await load()
  }
}

async function onColumnDragEnd(event) {
  dragEnded()
  if (event.oldIndex === event.newIndex) return
  try {
    await store.persistColumnMove(Number(event.item.dataset.columnId))
  } catch (err) {
    showError('Could not move column', err)
    await load()
  }
}

// ---- Task dialog ----
const taskDialogVisible = ref(false)
const editingTask = ref(null)
const newTaskColumnId = ref(null)
const taskDialogMode = ref('view')

// Browser title: open task, then board, e.g. "Java Backend · Bewerbungen · Taskz".
usePageTitle(() => {
  if (store.boardsLoaded && store.boards.length === 0) return ['Boards']
  const boardName = store.board?.name
  if (settingsOpen.value) return ['Settings', boardName]
  if (archiveOpen.value) return ['Archive', boardName]
  if (!taskDialogVisible.value) return [boardName]
  if (!editingTask.value) return ['New task', boardName]
  // The store replaces a task object when it is saved, so read the current title.
  const task = store.findTask(editingTask.value.id)?.task ?? editingTask.value
  return [task.title, boardName]
})

function openCreateTask(columnId) {
  editingTask.value = null
  newTaskColumnId.value = columnId
  taskDialogVisible.value = true
}

function openTask(task, mode = 'view') {
  editingTask.value = task
  taskDialogMode.value = mode
  taskDialogVisible.value = true
}

// ---- Task menu ----
const taskMenu = ref()
const menuTask = ref(null)
const taskMenuItems = computed(() => {
  const task = menuTask.value
  if (!task) return []
  // The arrow points the way the task moves: left for columns before its own, right for those after.
  const current = store.columns.findIndex((c) => c.id === task.columnId)
  const targets = store.columns
    .map((c, index) => ({ column: c, index }))
    .filter(({ column }) => column.id !== task.columnId)
    .map(({ column, index }) => ({
      label: column.name,
      icon: index < current ? 'pi pi-arrow-left' : 'pi pi-arrow-right',
      command: () => moveTaskTo(task, column.id),
    }))
  return [
    { label: 'Edit', icon: 'pi pi-pencil', command: () => openTask(task, 'edit') },
    ...(targets.length ? [{ label: 'Move to', items: targets }] : []),
    { separator: true },
    { label: 'Archive', icon: 'pi pi-inbox', command: () => archiveTask(task) },
    { label: 'Delete', icon: 'pi pi-trash', class: 'menu-danger', command: () => askDeleteTask(task) },
  ]
})

function showTaskMenu(event, task) {
  menuTask.value = task
  taskMenu.value.toggle(event)
}

// The menu puts the task at the end of the target column.
async function moveTaskTo(task, columnId) {
  try {
    await store.moveTask(task.id, columnId, store.tasksByColumn[columnId].length)
  } catch (err) {
    showError('Could not move task', err)
    await load()
  }
}

// No question first: the archive gives the task back.
async function archiveTask(task) {
  try {
    await store.archiveTask(task.id)
    toast.add({ severity: 'success', summary: 'Task archived', life: 2500 })
  } catch (err) {
    showError('Could not archive task', err)
  }
}

function askDeleteTask(task) {
  confirm.require({
    header: 'Delete task?',
    message: `"${task.title}" will be deleted permanently.`,
    icon: 'pi pi-trash',
    rejectProps: { label: 'Cancel', severity: 'secondary', text: true },
    acceptProps: { label: 'Delete', severity: 'danger' },
    accept: async () => {
      try {
        await store.deleteTask(task.id)
        toast.add({ severity: 'success', summary: 'Task deleted', life: 2500 })
      } catch (err) {
        showError('Could not delete task', err)
      }
    },
  })
}

// ---- Columns ----
const columnDialogVisible = ref(false)
const editingColumn = ref(null)
const deleteDialogVisible = ref(false)
const deletingColumn = ref(null)

function openAddColumn() {
  editingColumn.value = null
  columnDialogVisible.value = true
}

// New columns are added at the end, which can be off screen.
async function showNewColumn(column) {
  await nextTick()
  scrollToColumn(column.id)
}

function openEditColumn(column) {
  editingColumn.value = column
  columnDialogVisible.value = true
}

const columnMenu = ref()
const menuColumn = ref(null)
const columnMenuItems = computed(() => {
  const column = menuColumn.value
  if (!column) return []
  const index = store.columns.findIndex((c) => c.id === column.id)
  return [
    { label: 'Edit', icon: 'pi pi-pencil', command: () => openEditColumn(column) },
    {
      label: 'Move left',
      icon: 'pi pi-arrow-left',
      disabled: index <= 0,
      command: () => moveColumnTo(column, index - 1),
    },
    {
      label: 'Move right',
      icon: 'pi pi-arrow-right',
      disabled: index === store.columns.length - 1,
      command: () => moveColumnTo(column, index + 1),
    },
    { separator: true },
    {
      label: 'Delete',
      icon: 'pi pi-trash',
      class: 'menu-danger',
      // The backend keeps at least one column per board.
      disabled: store.columns.length === 1,
      command: () => askDeleteColumn(column),
    },
  ]
})

async function moveColumnTo(column, index) {
  try {
    await store.moveColumn(column.id, index)
  } catch (err) {
    showError('Could not move column', err)
    await load()
  }
}

function showColumnMenu(event, column) {
  menuColumn.value = column
  columnMenu.value.toggle(event)
}

// Columns with tasks need the full dialog (move or delete the tasks, type the name).
function askDeleteColumn(column) {
  if (store.tasksByColumn[column.id]?.length) {
    deletingColumn.value = column
    deleteDialogVisible.value = true
    return
  }
  confirm.require({
    header: 'Delete column?',
    message: `The empty column "${column.name}" will be deleted.`,
    icon: 'pi pi-trash',
    rejectProps: { label: 'Cancel', severity: 'secondary', text: true },
    acceptProps: { label: 'Delete', severity: 'danger' },
    accept: async () => {
      try {
        await store.deleteColumn(column.id)
      } catch (err) {
        showError('Could not delete column', err)
      }
    },
  })
}
</script>

<template>
  <SidebarLayout v-slot="{ openBoards, createBoard, deleteBoard }">
    <section class="flex min-w-0 flex-1 flex-col gap-4 px-4 py-4 md:gap-6 md:py-8">
      <!-- The toolbar moves to its own line when it doesn't fit next to the title (phones, tablets,
           narrow windows), so the title and who is online keep their room. -->
      <div class="flex flex-wrap items-center justify-between gap-3 md:items-end">
        <div class="flex min-w-0 items-center gap-1 md:items-start md:gap-2">
          <Button
            icon="pi pi-bars"
            text
            rounded
            severity="secondary"
            aria-label="Show boards"
            class="-ml-2 shrink-0 md:!hidden"
            @click="openBoards()"
          />
          <div class="min-w-0">
            <h1 class="truncate text-xl font-semibold tracking-tight text-slate-900 dark:text-slate-100 md:text-2xl">
              {{ store.board?.name ?? 'Boards' }}
            </h1>
            <p v-if="settingsOpen" class="text-sm text-slate-500 dark:text-slate-400">Board settings</p>
            <p v-else-if="archiveOpen" class="text-sm text-slate-500 dark:text-slate-400">Archive</p>
            <p v-else-if="store.loaded" class="hidden text-sm text-slate-500 dark:text-slate-400 md:block">
              <template v-if="filterActive">{{ matchingCount }} of {{ store.taskCount }} tasks match</template>
              <template v-else>
                {{ store.taskCount }} {{ store.taskCount === 1 ? 'task' : 'tasks' }} ·
                {{ store.columns.length }} {{ store.columns.length === 1 ? 'column' : 'columns' }}
              </template>
            </p>
          </div>
          <!-- Who has the board open right now: you first, then the others. On phones only their
               number, as the icons would push the title and the toolbar aside. -->
          <div
            v-if="others.length && !subPage"
            class="ml-2 flex shrink-0 items-center self-center"
            data-testid="presence"
            role="img"
            :aria-label="othersLabel"
          >
            <span
              class="flex h-7 items-center gap-1 rounded-full bg-emerald-50 px-2 text-xs font-semibold text-emerald-700 md:hidden dark:bg-emerald-950 dark:text-emerald-300"
              :title="others.map((o) => o.name).join(', ')"
              data-testid="presence-count"
            ><i class="pi pi-users text-[0.7rem]" aria-hidden="true" />{{ others.length }}</span>
            <span class="hidden items-center gap-1.5 md:flex">
              <UserIcon
                :seed="auth.user?.id ?? 'me'"
                :name="`You (${auth.user?.name || auth.user?.email || 'you'})`"
                class="ring-2 ring-emerald-500 dark:ring-emerald-400"
                data-testid="presence-me"
              />
              <UserIcon
                v-for="person in shownOthers"
                :key="person.id"
                :seed="person.id"
                :name="person.name"
                data-testid="presence-other"
              />
              <span
                v-if="hiddenOthers.length"
                class="flex h-7 min-w-7 items-center justify-center rounded-full bg-slate-200 px-1 text-[0.6875rem] font-semibold text-slate-600 dark:bg-slate-700 dark:text-slate-300"
                :title="hiddenOthers.map((o) => o.name).join(', ')"
                data-testid="presence-more"
              >+{{ hiddenOthers.length }}</span>
            </span>
          </div>
        </div>
        <!-- The same toolbar on the board, its settings and its archive: the open page's button is
             filled, and the view switch leads back to the board. What only works on the board is hidden.
             Icon-only buttons on small screens to keep the header in one line -->
        <div v-if="store.board" class="ml-auto flex min-w-0 flex-wrap justify-end gap-2">
          <Button
            v-if="isMobile && !subPage"
            icon="pi pi-search"
            aria-label="Search"
            severity="secondary"
            :outlined="!filterOpen && !filterActive"
            :aria-expanded="filterOpen || filterActive"
            @click="toggleFilterRow"
          />
          <ViewSwitch :mode="subPage ? '' : mode" @update:mode="chooseMode" />
          <!-- Always an icon: the header is full as it is. -->
          <Button
            icon="pi pi-inbox"
            aria-label="Archive"
            title="Archive"
            :severity="archiveOpen ? undefined : 'secondary'"
            :outlined="!archiveOpen"
            :aria-pressed="archiveOpen"
            @click="togglePage('board-archive')"
          />
          <Button
            :label="isMobile ? undefined : 'Settings'"
            aria-label="Board settings"
            icon="pi pi-cog"
            :severity="settingsOpen ? undefined : 'secondary'"
            :outlined="!settingsOpen"
            :aria-pressed="settingsOpen"
            @click="togglePage('board-settings')"
          />
          <!-- Small screens have the + in the column tabs instead. -->
          <Button
            v-if="!isMobile && !subPage"
            label="Add column"
            icon="pi pi-objects-column"
            severity="secondary"
            outlined
            @click="openAddColumn"
          />
          <Button
            v-if="store.columns.length && !subPage"
            :label="isMobile ? undefined : 'New task'"
            aria-label="New task"
            icon="pi pi-plus"
            @click="openCreateTask(isMobile && view === 'board' ? activeColumnId : store.columns[0].id)"
          />
        </div>
      </div>

      <BoardFilter
        v-if="store.loaded && !subPage && (!isMobile || filterOpen || filterActive)"
        ref="filterBar"
        v-model:text="filterText"
        v-model:labelIds="filterLabelIds"
        :labels="store.labels"
        :active="filterActive"
        class="-mt-1 md:-mt-3"
        @clear="clearFilter"
      />

      <div
        v-if="store.boardsLoaded && store.boards.length === 0"
        class="flex flex-col items-center gap-3 rounded-xl border border-dashed border-slate-300 dark:border-slate-700 bg-white dark:bg-slate-900 px-6 py-14 text-center"
      >
        <i class="pi pi-th-large text-4xl text-slate-300 dark:text-slate-600" />
        <p class="font-medium text-slate-700 dark:text-slate-300">No boards yet</p>
        <p class="text-sm text-slate-500 dark:text-slate-400">Create a board to start collecting tasks.</p>
        <Button label="Create your first board" icon="pi pi-plus" class="mt-2" @click="createBoard" />
      </div>

      <div v-else-if="store.loading && !store.loaded" class="flex gap-4 overflow-hidden">
        <Skeleton v-for="n in 3" :key="n" width="18rem" height="20rem" borderRadius="0.75rem" class="shrink-0" />
      </div>

      <BoardSettings v-else-if="store.loaded && settingsOpen" @delete="deleteBoard" />

      <BoardArchive v-else-if="store.loaded && archiveOpen" />

      <TaskTable
        v-else-if="store.loaded && view === 'table'"
        :tasks="tableTasks"
        :columns="store.columns"
        :labelsOf="store.labelsOf"
        :terms="filterTerms"
        :filtered="filterActive && store.taskCount > 0"
        @open="openTask"
        @move="moveTaskTo"
      />

      <template v-else-if="store.loaded">
        <!-- Small screens: tabs to jump between columns -->
        <nav
          ref="tabBar"
          class="sticky top-14 z-[5] -mx-4 -mt-2 flex gap-1 overflow-x-auto border-b border-slate-200 dark:border-slate-800 bg-slate-50 dark:bg-slate-950 px-4 md:hidden"
          aria-label="Columns"
        >
          <button
            v-for="column in store.columns"
            :key="column.id"
            type="button"
            :data-tab-column-id="column.id"
            class="flex shrink-0 items-center gap-1.5 border-b-2 px-3 py-2.5 text-sm whitespace-nowrap"
            :class="
              column.id === activeColumnId
                ? 'border-slate-900 dark:border-slate-100 font-semibold text-slate-900 dark:text-slate-100'
                : 'border-transparent text-slate-500 dark:text-slate-400'
            "
            :aria-current="column.id === activeColumnId ? 'true' : undefined"
            @click="scrollToColumn(column.id)"
          >
            {{ column.name }}
            <span class="rounded-full bg-slate-200 dark:bg-slate-800 px-1.5 text-xs font-medium text-slate-600 dark:text-slate-400">
              {{ countLabel(column.id) }}
            </span>
          </button>
          <button
            type="button"
            class="flex shrink-0 items-center px-3 py-2.5 text-slate-500 dark:text-slate-400"
            aria-label="Add column"
            @click="openAddColumn"
          >
            <i class="pi pi-plus text-xs" aria-hidden="true" />
          </button>
        </nav>

        <!-- On small screens each column is as wide as the screen and the board snaps to columns. -->
        <div
          ref="boardScroller"
          class="-mx-4 snap-x snap-mandatory scroll-px-4 overflow-x-auto px-4 pb-4 md:snap-none"
          @scroll.passive="updateActiveColumn"
        >
          <!-- w-max: as wide as the columns, so the scroller's right padding shows after the last one -->
          <div class="flex w-max items-start gap-4">
            <VueDraggable
              v-model="store.columns"
              handle=".column-handle"
              :animation="150"
              ghostClass="opacity-40"
              class="flex items-start gap-4"
              @start="dragStarted"
              @end="onColumnDragEnd"
            >
              <div
                v-for="(column, index) in store.columns"
                :key="column.id"
                :data-column-id="column.id"
                class="flex w-[calc(100vw-2rem)] shrink-0 snap-start flex-col rounded-xl bg-slate-100 dark:bg-slate-900"
                :class="isEmpty(column.id) ? 'md:w-12' : 'md:w-72'"
              >
                <header class="flex items-center gap-1 px-2 pt-2" :class="{ 'md:flex-col md:px-1': isEmpty(column.id) }">
                  <button
                    type="button"
                    class="column-handle hidden h-8 w-6 cursor-grab items-center justify-center text-slate-400 dark:text-slate-500 hover:text-slate-600 dark:hover:text-slate-400 active:cursor-grabbing md:flex"
                    aria-label="Drag to reorder column"
                    title="Drag to reorder"
                  >
                    <i class="pi pi-bars text-xs" />
                  </button>
                  <h2
                    class="min-w-0 flex-1 truncate text-sm font-semibold text-slate-700 dark:text-slate-300"
                    :class="{ 'md:w-full md:flex-none md:text-center md:text-[0.6875rem]': isEmpty(column.id) }"
                    :title="column.name"
                  >
                    {{ column.name }}
                  </h2>
                  <span class="rounded-full bg-white dark:bg-slate-800 px-2 text-xs font-medium text-slate-500 dark:text-slate-400">
                    {{ countLabel(column.id) }}
                  </span>
                  <Button
                    icon="pi pi-ellipsis-h"
                    text
                    rounded
                    severity="secondary"
                    size="small"
                    aria-label="Column actions"
                    @click="showColumnMenu($event, column)"
                  />
                </header>

                <p
                  v-if="filterActive && visibleCount(column.id) === 0"
                  class="px-4 pt-2 text-sm text-slate-400 dark:text-slate-500"
                  :class="{ 'md:hidden': isEmpty(column.id) }"
                >
                  No matches
                </p>
                <!-- In an empty column's strip the drop target is small; outlined so it can be found. -->
                <VueDraggable
                  v-model="store.tasksByColumn[column.id]"
                  group="tasks"
                  :animation="150"
                  :delay="150"
                  :delayOnTouchOnly="true"
                  ghostClass="opacity-40"
                  class="flex min-h-16 flex-col gap-2 p-2"
                  :class="{
                    'md:m-1 md:min-h-32 md:rounded-md md:border-2 md:border-dashed md:border-slate-300 md:p-1 md:dark:border-slate-700': isEmpty(column.id),
                  }"
                  @start="dragStarted"
                  @end="onTaskDragEnd"
                >
                  <TaskCard
                    v-for="task in store.tasksByColumn[column.id]"
                    :key="task.id"
                    :task="task"
                    :color="column.color"
                    :labels="store.labelsOf(task)"
                    :leftColumn="neighbours(index).left"
                    :rightColumn="neighbours(index).right"
                    v-show="matchOf(task).matches"
                    :terms="filterTerms"
                    :matchInDescription="matchOf(task).inDescription && !matchOf(task).inTitle"
                    :compact="compact"
                    @open="openTask"
                    @menu="showTaskMenu"
                    @move="moveTaskTo"
                  />
                </VueDraggable>

                <Button
                  label="Add task"
                  aria-label="Add task"
                  icon="pi pi-plus"
                  text
                  severity="secondary"
                  size="small"
                  class="mx-2 mb-2 justify-start"
                  :class="{ 'md:mx-1 md:justify-center md:[&_.p-button-label]:hidden': isEmpty(column.id) }"
                  @click="openCreateTask(column.id)"
                />
              </div>
            </VueDraggable>
          </div>
        </div>
      </template>

      <Menu ref="taskMenu" :model="taskMenuItems" popup />
      <Menu ref="columnMenu" :model="columnMenuItems" popup />
      <TaskDialog
        v-model:visible="taskDialogVisible"
        :task="editingTask"
        :initialMode="taskDialogMode"
        :columnId="newTaskColumnId"
      />
      <ColumnDialog v-model:visible="columnDialogVisible" :column="editingColumn" @created="showNewColumn" />
      <ColumnDeleteDialog v-model:visible="deleteDialogVisible" :column="deletingColumn" />
    </section>
  </SidebarLayout>
</template>
