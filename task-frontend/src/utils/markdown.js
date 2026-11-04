import { Marked } from 'marked'
import DOMPurify from 'dompurify'

// Task descriptions are Markdown (GitHub flavour). Shared boards show them to anyone with the
// link, so the HTML is always sanitized before it reaches v-html.
const marked = new Marked({ gfm: true, breaks: true })

// "- [ ]" is plain text: checklists live in their own list (task.checklist), not in the description.
marked.use({
  renderer: {
    checkbox({ checked }) {
      return checked ? '[x] ' : '[ ] '
    },
  },
})

// Links open in a new tab and don't give the target page access to ours.
DOMPurify.addHook('afterSanitizeAttributes', (node) => {
  if (node.tagName === 'A' && node.hasAttribute('href')) {
    node.setAttribute('target', '_blank')
    node.setAttribute('rel', 'noopener noreferrer nofollow')
  }
})

const SANITIZE_OPTIONS = {
  // Images would load from foreign servers (tracking on shared boards) and break the card layout.
  FORBID_TAGS: ['img', 'style', 'form', 'button', 'textarea', 'select', 'input'],
  FORBID_ATTR: ['style', 'class', 'id'],
  ADD_ATTR: ['target'],
}

export function renderMarkdown(text) {
  if (!text) return ''
  return DOMPurify.sanitize(marked.parse(text), SANITIZE_OPTIONS)
}
