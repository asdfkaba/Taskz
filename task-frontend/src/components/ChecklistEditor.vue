<script setup>
import { ref } from 'vue'
import InputText from 'primevue/inputtext'
import Button from 'primevue/button'

// Edits the checklist in the task form: rename, reorder, remove and add items.
// Ticks are set in the view, not here; items keep theirs through their id.
const items = defineModel({ type: Array, required: true }) // [{ id, text }], id null for new items

// Same limits as the backend (Checklists).
const MAX_ITEMS = 100
const MAX_ITEM_LENGTH = 200

const newItem = ref('')

function add() {
  const text = newItem.value.trim()
  if (!text || items.value.length >= MAX_ITEMS) return
  items.value.push({ id: null, text })
  newItem.value = ''
}

function move(index, by) {
  const target = index + by
  if (target < 0 || target >= items.value.length) return
  const [item] = items.value.splice(index, 1)
  items.value.splice(target, 0, item)
}
</script>

<template>
  <div class="flex flex-col gap-1.5" role="group" data-testid="checklist-editor">
    <ul v-if="items.length" class="flex flex-col gap-1">
      <li v-for="(item, index) in items" :key="item.id ?? `new-${index}`" class="flex items-center gap-1">
        <InputText
          v-model="item.text"
          :maxlength="MAX_ITEM_LENGTH"
          :aria-label="`Checklist item ${index + 1}`"
          size="small"
          fluid
          @keydown.enter.prevent
        />
        <Button
          icon="pi pi-arrow-up"
          severity="secondary"
          text
          size="small"
          :disabled="index === 0"
          :aria-label="`Move ${item.text || 'item'} up`"
          @click="move(index, -1)"
        />
        <Button
          icon="pi pi-arrow-down"
          severity="secondary"
          text
          size="small"
          :disabled="index === items.length - 1"
          :aria-label="`Move ${item.text || 'item'} down`"
          @click="move(index, 1)"
        />
        <Button
          icon="pi pi-times"
          severity="secondary"
          text
          size="small"
          :aria-label="`Remove ${item.text || 'item'}`"
          @click="items.splice(index, 1)"
        />
      </li>
    </ul>
    <div class="flex gap-2">
      <InputText
        v-model="newItem"
        :maxlength="MAX_ITEM_LENGTH"
        placeholder="Add an item and press Enter"
        aria-label="New checklist item"
        size="small"
        fluid
        @keydown.enter.prevent="add"
      />
      <Button
        icon="pi pi-plus"
        severity="secondary"
        size="small"
        aria-label="Add item"
        :disabled="!newItem.trim() || items.length >= MAX_ITEMS"
        @click="add"
      />
    </div>
  </div>
</template>
