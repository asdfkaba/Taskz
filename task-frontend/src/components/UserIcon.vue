<script setup>
import { computed } from 'vue'
import { identicon } from '@/utils/identicon'

// A user's identicon (see utils/identicon) in a circle. Without an id (the user is gone) it
// shows nothing.
const props = defineProps({
  seed: { type: [Number, String], default: null }, // the user id
  name: { type: String, default: '' }, // for the tooltip
  size: { type: String, default: 'md' }, // 'xs' (in small text) | 'md' | 'lg' (header) | 'xl'
})

const SIZES = { xs: 'h-4 w-4', md: 'h-7 w-7', lg: 'h-8 w-8', xl: 'h-14 w-14' }
const icon = computed(() => (props.seed == null ? null : identicon(props.seed)))
</script>

<template>
  <span
    v-if="icon"
    class="inline-flex shrink-0 overflow-hidden rounded-full align-middle"
    :class="SIZES[size]"
    :style="{ background: icon.background }"
    :title="name"
  >
    <!-- The 5×5 grid with a margin, so the circle doesn't cut its corners. -->
    <svg viewBox="-1.5 -1.5 8 8" class="h-full w-full" aria-hidden="true" shape-rendering="crispEdges">
      <rect v-for="[x, y] in icon.cells" :key="`${x},${y}`" :x="x" :y="y" width="1" height="1" :fill="icon.color" />
    </svg>
  </span>
</template>
