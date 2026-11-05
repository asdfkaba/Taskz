<script setup>
import { computed } from 'vue'
import Button from 'primevue/button'
import { fromApi, formatDue, formatShort } from '@/utils/date'
import { DEFAULT_COLUMN_COLOR } from '@/utils/task'
import { renderMarkdown } from '@/utils/markdown'
import LabelPill from '@/components/LabelPill.vue'
import { highlight } from '@/utils/search'

const props = defineProps({
  task: { type: Object, required: true },
  // Color of the column the task is in; the card shows it as a stripe.
  color: { type: String, default: null },
  // Shared boards: no menu, no move arrows, no dragging; a click still opens the task.
  readonly: { type: Boolean, default: false },
  // Neighbour columns ({ id, name }) for the move arrows, an alternative to drag & drop.
  leftColumn: { type: Object, default: null },
  rightColumn: { type: Object, default: null },
  // The task's labels as { id, name, color }
  labels: { type: Array, default: () => [] },
  // Search terms to highlight in the title (see utils/search).
  terms: { type: Array, default: () => [] },
  // The search matched the description but not the title; say so, the description may be cut off.
  matchInDescription: { type: Boolean, default: false },
  // Compact cards for large boards: one row with the title cut off, labels as colored dots, no
  // description and no arrows (the menu and dragging still move the task).
  compact: { type: Boolean, default: false },
})
const emit = defineEmits(['open', 'menu', 'move'])

const due = computed(() => fromApi(props.task.due_date))
const isOverdue = computed(() => due.value !== null && due.value < new Date())
// Last change, like the table's "Changed" column; never-edited tasks show when they were created.
const changed = computed(() => (props.compact ? null : fromApi(props.task.edited) ?? fromApi(props.task.created)))
const showArrows = computed(() => !props.readonly && !props.compact && (props.leftColumn || props.rightColumn))
// The menu button sets the header height of editable cards; read-only ones need their own spacing.
const descriptionHtml = computed(() => (props.compact ? '' : renderMarkdown(props.task.description)))
const titlePieces = computed(() => highlight(props.task.title, props.terms))
// Progress of the checklist, e.g. { done: 2, total: 5 }; null without one.
const checklist = computed(() => {
  const items = props.task.checklist ?? []
  return items.length ? { done: items.filter((i) => i.checked).length, total: items.length } : null
})

// A click on a link in the description follows the link instead of opening the task.
function onClick(event) {
  if (event.target.closest('a')) return
  emit('open', props.task)
}
</script>

<template>
  <article
    class="group relative flex flex-col overflow-hidden rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 shadow-sm"
    :class="[
      'transition hover:border-slate-300 hover:shadow-md dark:hover:border-slate-600',
      readonly ? 'cursor-pointer' : 'cursor-grab active:cursor-grabbing',
    ]"
    tabindex="0"
    :data-task-id="task.id"
    @click="onClick"
    @keydown.enter.self="emit('open', task)"
  >
    <div class="absolute inset-y-0 left-0 w-1.5" :style="{ backgroundColor: color ?? DEFAULT_COLUMN_COLOR }" />

    <!-- Compact: one row; the header's title and menu become items of that row. -->
    <div class="flex pr-2 pl-4" :class="compact ? ['items-center gap-2', readonly ? 'py-2' : 'py-1'] : 'flex-col gap-2 py-3'">
      <header :class="compact ? 'contents' : 'flex items-start justify-between gap-2'">
        <h3
          class="leading-snug font-semibold text-slate-900 dark:text-slate-100"
          :class="compact ? 'min-w-0 flex-1 truncate text-[0.8125rem]' : 'break-words text-sm'"
          :title="compact ? task.title : undefined"
        >
          <template v-for="(piece, i) in titlePieces" :key="i">
            <mark v-if="piece.hit" class="rounded-sm bg-yellow-200 text-inherit dark:bg-yellow-500/40">{{ piece.text }}</mark>
            <template v-else>{{ piece.text }}</template>
          </template>
        </h3>
        <Button
          v-if="!readonly"
          icon="pi pi-ellipsis-v"
          text
          rounded
          severity="secondary"
          size="small"
          aria-label="Task actions"
          class="-mr-1 shrink-0"
          :class="compact ? 'order-last -my-1' : '-mt-1.5'"
          @click.stop="emit('menu', $event, task)"
        />
      </header>

      <div v-if="labels.length && !compact" class="-mt-1 flex flex-wrap gap-1">
        <LabelPill v-for="label in labels" :key="label.id" :label="label" />
      </div>

      <p
        v-if="matchInDescription"
        class="flex shrink-0 items-center gap-1 text-xs text-slate-500 dark:text-slate-400"
        :class="{ '-mt-1': !compact }"
        :title="compact ? 'Match in description' : undefined"
      >
        <i class="pi pi-search text-[0.65rem]" aria-hidden="true" />
        <span :class="{ 'sr-only': compact }">Match in description</span>
      </p>

      <!-- Sanitized in renderMarkdown. -->
      <div v-if="descriptionHtml" class="markdown line-clamp-3" v-html="descriptionHtml" />

      <div
        v-if="due || checklist || task.attachments || changed || showArrows || (compact && labels.length)"
        class="flex items-center gap-2 text-xs"
        :class="compact ? 'shrink-0' : 'flex-wrap'"
      >
        <!-- Compact: labels as dots, the name shows on hover and is read out by screen readers. -->
        <span v-if="compact && labels.length" class="flex flex-wrap items-center gap-1" data-testid="label-dots">
          <span
            v-for="label in labels"
            :key="label.id"
            role="img"
            :aria-label="`Label ${label.name}`"
            :title="label.name"
            class="h-2.5 w-2.5 rounded-full ring-1 ring-black/10 dark:ring-white/20"
            :style="{ backgroundColor: label.color }"
          />
        </span>
        <span
          v-if="checklist"
          data-testid="checklist-progress"
          class="flex items-center gap-1 font-medium"
          :class="
            checklist.done === checklist.total
              ? 'text-green-700 dark:text-green-400'
              : 'text-slate-500 dark:text-slate-400'
          "
          :aria-label="`${checklist.done} of ${checklist.total} checklist items done`"
        >
          <i class="pi pi-check-square text-xs" aria-hidden="true" />
          {{ checklist.done }}/{{ checklist.total }}
        </span>
        <span
          v-if="task.attachments"
          data-testid="attachment-count"
          class="flex items-center gap-1 font-medium text-slate-500 dark:text-slate-400"
          :title="`${task.attachments} ${task.attachments === 1 ? 'attachment' : 'attachments'}`"
        >
          <i class="pi pi-paperclip text-xs" aria-hidden="true" />
          {{ task.attachments }}
          <span class="sr-only">{{ task.attachments === 1 ? 'attachment' : 'attachments' }}</span>
        </span>
        <span
          v-if="due"
          class="flex items-center gap-1 font-medium"
          :class="isOverdue ? 'text-red-600 dark:text-red-400' : 'text-slate-500 dark:text-slate-400'"
        >
          <i class="pi text-xs" :class="isOverdue ? 'pi-exclamation-circle' : 'pi-calendar'" />
          {{ formatDue(due) }}
        </span>
        <div v-if="changed || showArrows" class="relative ml-auto flex items-center gap-1">
          <span
            v-if="changed"
            data-testid="card-changed"
            class="flex items-center gap-1 text-slate-400 transition-opacity dark:text-slate-500"
            :class="{ 'group-hover:opacity-0 [@media(hover:hover)]:group-focus-within:opacity-0': showArrows }"
            :title="`Last changed ${formatShort(changed)}`"
          >
            <i class="pi pi-pencil text-[0.65rem]" aria-hidden="true" />
            <span class="sr-only">Last changed </span>{{ formatShort(changed) }}
          </span>
          <!-- With a mouse the arrows show on hover or keyboard focus, on top of the date; on touch
               screens always, next to it. -->
          <div
            v-if="showArrows"
            class="-my-1 flex transition-opacity group-focus-within:opacity-100 group-hover:opacity-100 [@media(hover:hover)]:opacity-0"
            :class="{ '[@media(hover:hover)]:absolute [@media(hover:hover)]:right-0': changed }"
          >
            <Button
              v-if="leftColumn"
              icon="pi pi-chevron-left"
              text
              rounded
              severity="secondary"
              size="small"
              :aria-label="`Move to ${leftColumn.name}`"
              :title="`Move to ${leftColumn.name}`"
              @click.stop="emit('move', task, leftColumn.id)"
            />
            <Button
              v-if="rightColumn"
              icon="pi pi-chevron-right"
              text
              rounded
              severity="secondary"
              size="small"
              :aria-label="`Move to ${rightColumn.name}`"
              :title="`Move to ${rightColumn.name}`"
              @click.stop="emit('move', task, rightColumn.id)"
            />
          </div>
        </div>
      </div>
    </div>
  </article>
</template>
