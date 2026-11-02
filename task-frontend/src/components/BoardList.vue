<script setup>
import UserIcon from '@/components/UserIcon.vue'
import { useRoute } from 'vue-router'
import Button from 'primevue/button'
import { useBoardStore } from '@/stores/board'

// List of the user's boards for the sidebar (desktop) and the drawer (mobile).
// The parent owns the dialogs, so this only emits what the user wants to do.
const emit = defineEmits(['create', 'delete', 'navigate'])

const store = useBoardStore()
const route = useRoute()
// Highlighted on the board and on its settings page.
const isOpen = (board) => route.params.slug === board.slug
</script>

<template>
  <nav class="flex min-h-0 flex-1 flex-col gap-1" aria-label="Boards">
    <ul class="flex min-h-0 flex-1 flex-col gap-0.5 overflow-y-auto">
      <li v-for="board in store.boards" :key="board.id" class="group relative">
        <RouterLink
          :to="{ name: 'board', params: { slug: board.slug } }"
          class="flex items-center gap-2 rounded-lg py-2 pr-9 pl-3 text-sm text-slate-600 dark:text-slate-400 hover:bg-slate-100 dark:hover:bg-slate-800 hover:text-slate-900 dark:hover:text-slate-100"
          :class="{ 'bg-slate-100 dark:bg-slate-800 font-semibold !text-slate-900 dark:!text-slate-100': isOpen(board) }"
          :aria-current="isOpen(board) ? 'page' : undefined"
          @click="emit('navigate')"
        >
          <i class="pi pi-th-large text-xs text-slate-400 dark:text-slate-500" aria-hidden="true" />
          <span class="min-w-0">
            <span class="block truncate">{{ board.name }}</span>
            <span v-if="!board.own" class="block truncate text-xs font-normal text-slate-400 dark:text-slate-500" data-testid="board-owner">
              <UserIcon :seed="board.ownerId" size="xs" class="-mt-0.5 mr-1" />by {{ board.ownerName ?? 'someone' }}
            </span>
          </span>
        </RouterLink>
        <!-- Only the owner deletes a board; members leave it in its settings. -->
        <Button
          v-if="board.own"
          icon="pi pi-trash"
          text
          rounded
          severity="secondary"
          size="small"
          :aria-label="`Delete board ${board.name}`"
          class="!absolute top-1/2 right-1 -translate-y-1/2 md:opacity-0 md:group-focus-within:opacity-100 md:group-hover:opacity-100"
          @click="emit('delete', board)"
        />
      </li>
    </ul>
    <Button
      label="New board"
      icon="pi pi-plus"
      text
      severity="secondary"
      size="small"
      class="justify-start"
      @click="emit('create')"
    />
  </nav>
</template>
