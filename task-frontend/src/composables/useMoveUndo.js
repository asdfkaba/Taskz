import { onBeforeUnmount } from 'vue'
import { useToast } from 'primevue/usetoast'
import { useBoardStore } from '@/stores/board'

// After a move to another column the board's rules wait a minute (BoardService.moveTaskByHand);
// that long a toast offers to undo it. Moving on within the minute keeps where the task came from,
// so undo goes back there; moving back there leaves nothing to undo.
export const UNDO_WINDOW_MS = 60_000

export function useMoveUndo(reload) {
  const toast = useToast()
  const store = useBoardStore()
  const origins = new Map() // taskId → { columnId, timer }: where the task was before this minute's moves

  function forget(taskId) {
    clearTimeout(origins.get(taskId)?.timer)
    origins.delete(taskId)
  }

  // shortcut: one undo toast at a time (the latest move); an earlier task's rules still wait, its
  // undo is just no longer offered. Keep a toast per task if several moves at once need undoing.
  function offer(taskId, fromColumnId, toColumnId) {
    if (fromColumnId === toColumnId) return
    const origin = origins.get(taskId)?.columnId ?? fromColumnId
    forget(taskId)
    toast.removeGroup('undo')
    if (toColumnId === origin) return
    origins.set(taskId, { columnId: origin, timer: setTimeout(() => forget(taskId), UNDO_WINDOW_MS) })
    const column = store.columns.find((c) => c.id === toColumnId)
    toast.add({ group: 'undo', severity: 'info', summary: `Moved to ${column?.name ?? 'another column'}`, life: UNDO_WINDOW_MS, taskId })
  }

  async function undo(message) {
    toast.removeGroup('undo')
    forget(message.taskId)
    try {
      await store.undoMove(message.taskId)
      toast.add({ severity: 'success', summary: 'Move undone', life: 2500 })
    } catch (err) {
      toast.add({ severity: 'error', summary: "Can't undo the move anymore", detail: err.message, life: 5000 })
    }
    await reload()
  }

  onBeforeUnmount(() => {
    for (const taskId of [...origins.keys()]) forget(taskId)
    toast.removeGroup('undo')
  })

  return { offer, undo }
}
