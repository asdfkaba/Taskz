<script setup>
import { ref } from 'vue'
import { renderMarkdown } from '@/utils/markdown'

// Collapsible list of the Markdown that descriptions support, with each example rendered by the
// same renderer as the description, so help and result can't drift apart.

const open = ref(false)

const SECTIONS = [
  {
    title: 'Text',
    examples: [{ source: '**bold**, *italic*' }, { source: '~~struck~~, `code`' }],
  },
  {
    title: 'Headings',
    examples: [{ source: '## Heading', note: '# to ######. On cards all levels look the same.' }],
  },
  {
    title: 'Links',
    examples: [
      { source: '[Job ad](https://example.com)' },
      { source: 'https://example.com', note: 'Plain addresses become links too. Links open in a new tab.' },
    ],
  },
  {
    title: 'Line breaks',
    examples: [
      { source: 'Line one\nLine two\n\nNew paragraph', note: 'Enter starts a new line, an empty line a new paragraph.' },
    ],
  },
  {
    title: 'Lists',
    examples: [
      { source: '- Item\n- Item\n  - Sub-item', note: 'Indent sub-items by 2 spaces per level.' },
      { source: '1. First\n2. Second\n   - Sub-item', note: 'After a number it takes 3 spaces.' },
    ],
  },
  {
    title: 'Quotes and code',
    examples: [
      { source: '> Quoted text' },
      { source: '```\nCode block\n```' },
    ],
  },
  {
    title: 'Tables',
    examples: [{ source: '| Company | Salary |\n| --- | --- |\n| XY | 60k |' }],
  },
  {
    title: 'Divider',
    examples: [{ source: 'Above\n\n---\n\nBelow', note: 'Keep the empty line above it; right under text, --- turns that text into a heading.' }],
  },
].map((section) => ({
  ...section,
  examples: section.examples.map((example) => ({ ...example, html: renderMarkdown(example.source) })),
}))
</script>

<template>
  <div class="text-xs text-slate-400 dark:text-slate-500">
    <button
      type="button"
      class="inline-flex items-center gap-1 hover:text-slate-600 dark:hover:text-slate-300"
      :aria-expanded="open"
      aria-controls="markdown-help"
      @click="open = !open"
    >
      <i class="pi text-[0.65rem]" :class="open ? 'pi-chevron-down' : 'pi-chevron-right'" aria-hidden="true" />
      Formatting help (Markdown)
    </button>

    <div
      v-if="open"
      id="markdown-help"
      data-testid="markdown-help"
      class="mt-2 flex flex-col gap-3 rounded-md border border-slate-200 p-3 dark:border-slate-700"
    >
      <section v-for="section in SECTIONS" :key="section.title" class="flex flex-col gap-1.5">
        <h4 class="font-semibold text-slate-600 dark:text-slate-300">{{ section.title }}</h4>
        <div
          v-for="example in section.examples"
          :key="example.source"
          class="grid gap-x-3 gap-y-1 sm:grid-cols-2"
          data-testid="markdown-example"
        >
          <pre
            class="overflow-x-auto rounded bg-slate-100 px-2 py-1 font-mono whitespace-pre text-slate-700 dark:bg-slate-800 dark:text-slate-200"
            aria-label="You type"
          >{{ example.source }}</pre>
          <!-- Sanitized in renderMarkdown. Only a preview: links and boxes don't react. -->
          <div class="markdown pointer-events-none px-1 py-1" aria-label="You get" v-html="example.html" />
          <p v-if="example.note" class="sm:col-span-2">{{ example.note }}</p>
        </div>
      </section>
      <p>Images and HTML formatting are not shown.</p>
    </div>
  </div>
</template>
