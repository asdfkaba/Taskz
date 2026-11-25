// What a share link shows of a task besides the board (attachments, notes, history), if its owner
// allows it. Works without login, so plain fetch.
export const sharedUrl = (path, rest) => `/api/share/${encodeURIComponent(path)}/${rest}`

export async function fetchShared(path, rest) {
  const response = await fetch(sharedUrl(path, rest))
  if (!response.ok) throw new Error(`Loading failed (HTTP ${response.status})`)
  return response
}
