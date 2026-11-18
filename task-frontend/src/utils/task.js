// Used for tasks in a column without its own color.
export const DEFAULT_COLUMN_COLOR = '#cbd5e1'

// Suggestions in the column dialog; any other #rrggbb color can be picked too.
export const COLUMN_COLOR_PRESETS = [
  '#64748b',
  '#ef4444',
  '#f97316',
  '#eab308',
  '#22c55e',
  '#14b8a6',
  '#3b82f6',
  '#8b5cf6',
  '#ec4899',
]

// Text on a label pill: white or dark slate, whichever has more contrast to the color (WCAG).
const DARK_TEXT = '#0f172a'

function relativeLuminance(hex) {
  const n = parseInt(hex.slice(1), 16)
  const [r, g, b] = [(n >> 16) & 255, (n >> 8) & 255, n & 255].map((v) => {
    const c = v / 255
    return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4
  })
  return 0.2126 * r + 0.7152 * g + 0.0722 * b
}

export function contrastRatio(a, b) {
  const [hi, lo] = [relativeLuminance(a), relativeLuminance(b)].sort((x, y) => y - x)
  return (hi + 0.05) / (lo + 0.05)
}

export function labelTextColor(hex) {
  return contrastRatio(hex, '#ffffff') >= contrastRatio(hex, DARK_TEXT) ? '#ffffff' : DARK_TEXT
}

// The line under a board's title, e.g. "12 tasks (3 archived) · 4 columns".
export function boardSummary(taskCount, archivedCount, columnCount) {
  const tasks = `${taskCount} ${taskCount === 1 ? 'task' : 'tasks'}`
  const archived = archivedCount > 0 ? ` (${archivedCount} archived)` : ''
  return `${tasks}${archived} · ${columnCount} ${columnCount === 1 ? 'column' : 'columns'}`
}
