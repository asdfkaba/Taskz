// Searching tasks by text and labels. Ignores case and accents: "uber" finds "Über", "muller" finds "Müller".

// Stands for "tasks without labels" in a label filter; real label ids start at 1.
export const NO_LABEL = 0

// One character in search form; may become two ("ß" -> "ss") or none (a lone combining accent).
function foldChar(char) {
  return char
    .normalize('NFD')
    .replace(/[̀-ͯ]/g, '')
    .toLowerCase()
    .replace('ß', 'ss')
}

export function fold(text) {
  return [...(text ?? '')].map(foldChar).join('')
}

// The query split into words, in search form.
export function searchTerms(query) {
  return fold(query).split(/\s+/).filter(Boolean)
}

/**
 * Does the task match? Every term must appear in the title, the description (with the checklist)
 * or a label name.
 * labelIds (if any): the task needs one of these labels, or no label at all if NO_LABEL is among them.
 * Returns { matches, inTitle, inDescription } so the card can say where the text was found.
 */
export function matchTask(task, taskLabels, terms, labelIds = []) {
  const taskLabelIds = task.labelIds ?? []
  const labelMatch =
    taskLabelIds.some((id) => labelIds.includes(id)) || (taskLabelIds.length === 0 && labelIds.includes(NO_LABEL))
  if (labelIds.length && !labelMatch) {
    return { matches: false, inTitle: false, inDescription: false }
  }
  if (!terms.length) return { matches: true, inTitle: false, inDescription: false }

  const title = fold(task.title)
  // The checklist counts as part of the description, as it did when it was written there.
  const description = fold([task.description, ...(task.checklist ?? []).map((i) => i.text)].filter(Boolean).join('\n'))
  const labels = taskLabels.map((l) => fold(l.name))
  const matches = terms.every((t) => title.includes(t) || description.includes(t) || labels.some((l) => l.includes(t)))
  return {
    matches,
    inTitle: matches && terms.some((t) => title.includes(t)),
    inDescription: matches && terms.some((t) => description.includes(t)),
  }
}

/**
 * The text cut into pieces for highlighting: [{ text, hit }]. Works on the original text,
 * so accents and case stay as written while matching ignores them.
 */
export function highlight(text, terms) {
  const chars = [...(text ?? '')]
  if (!terms.length || !chars.length) return [{ text: text ?? '', hit: false }]

  // Folded text with, for every folded character, the original character it came from.
  let folded = ''
  const origin = []
  chars.forEach((char, i) => {
    for (const f of foldChar(char)) {
      folded += f
      origin.push(i)
    }
  })

  const hit = new Array(chars.length).fill(false)
  for (const term of terms) {
    for (let at = folded.indexOf(term); at !== -1; at = folded.indexOf(term, at + 1)) {
      for (let k = at; k < at + term.length; k++) hit[origin[k]] = true
    }
  }

  const pieces = []
  chars.forEach((char, i) => {
    const last = pieces[pieces.length - 1]
    if (last && last.hit === hit[i]) last.text += char
    else pieces.push({ text: char, hit: hit[i] })
  })
  return pieces
}
