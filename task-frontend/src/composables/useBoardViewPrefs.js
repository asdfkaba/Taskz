import { computed, ref, watch } from 'vue'
import { readStorage, writeStorage } from '@/utils/storage'

// Stored as a JSON array of column ids; anything else counts as none.
function readIds(key) {
  try {
    const ids = JSON.parse(readStorage(key))
    return Array.isArray(ids) ? ids : []
  } catch {
    return []
  }
}

/**
 * Board or table view, compact cards and collapsed columns, remembered per board on this device (localStorage).
 * getId returns the key of the open board, e.g. its id or a share link key; null while none is open.
 */
export function useBoardViewPrefs(getId) {
  const view = ref('board') // 'board' | 'table'
  const compact = ref(false)
  const collapsed = ref(new Set()) // ids of the columns shown as a narrow strip

  watch(
    getId,
    (id) => {
      view.value = id != null && readStorage(`boardView:${id}`) === 'table' ? 'table' : 'board'
      compact.value = id != null && readStorage(`compactCards:${id}`) === '1'
      collapsed.value = new Set(id != null ? readIds(`collapsedColumns:${id}`) : [])
    },
    { immediate: true },
  )

  function setView(value) {
    view.value = value
    const id = getId()
    if (id != null) writeStorage(`boardView:${id}`, value)
  }

  // One switch for both (see ViewSwitch): 'board', 'compact' (the board with compact cards) or 'table'.
  const mode = computed(() => (view.value === 'table' ? 'table' : compact.value ? 'compact' : 'board'))

  function setMode(value) {
    setView(value === 'table' ? 'table' : 'board')
    if (value === 'table') return // the table keeps the board's card size for the way back
    compact.value = value === 'compact'
    const id = getId()
    if (id != null) writeStorage(`compactCards:${id}`, compact.value ? '1' : '0')
  }

  function toggleCollapsed(columnId) {
    const next = new Set(collapsed.value)
    if (!next.delete(columnId)) next.add(columnId)
    collapsed.value = next
    const id = getId()
    if (id != null) writeStorage(`collapsedColumns:${id}`, JSON.stringify([...next]))
  }

  return { view, compact, mode, setMode, collapsed, toggleCollapsed }
}
