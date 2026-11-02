// FNV-1a, a small stable string hash, mixed at the end (MurmurHash3's finalizer): ids in a row
// differ only in their last character and would otherwise get nearly the same color.
function hash(text) {
  let h = 0x811c9dc5
  for (const char of text) {
    h ^= char.codePointAt(0)
    h = Math.imul(h, 0x01000193)
  }
  h ^= h >>> 16
  h = Math.imul(h, 0x85ebca6b)
  h ^= h >>> 13
  h = Math.imul(h, 0xc2b2ae35)
  h ^= h >>> 16
  return h >>> 0
}

/**
 * A GitHub-style identicon for a user: a 5×5 pattern, mirrored left to right, in a color of its
 * own. Both come from the seed (the user id), so the same person always gets the same icon and
 * nothing is stored.
 * Returns { cells: [[x, y], ...], color, background }.
 */
export function identicon(seed) {
  const text = `taskz:${seed}`
  // Ids step around the color wheel by the golden angle, so any few people get clearly different
  // colors; random hues often fall close together.
  const hue = Number.isInteger(seed) ? Math.round((seed * 137.508) % 360) : hash(text) % 360
  const bits = hash(`${text}:cells`)
  const cells = []
  for (let x = 0; x < 3; x++) {
    for (let y = 0; y < 5; y++) {
      if (bits & (1 << (x * 5 + y))) {
        cells.push([x, y])
        if (x < 2) cells.push([4 - x, y]) // mirror
      }
    }
  }
  return { cells, color: `hsl(${hue} 65% 42%)`, background: `hsl(${hue} 65% 92%)` }
}
