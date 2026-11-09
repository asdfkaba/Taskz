// "What's due": tasks with a due date across boards, grouped, without the last column; a click opens the task.
import { test } from '@playwright/test'
import { BASE, dialogOpened, resetMock } from './support.mjs'

async function api(method, path, body) {
  const response = await fetch(`${BASE}/api${path}`, {
    method,
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  return response.json()
}

// Local wall-clock time like the app sends it.
const local = (date) => {
  const pad = (n) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}:00`
}
const inDays = (days) => {
  const d = new Date()
  d.setDate(d.getDate() + days)
  d.setHours(12, 0, 0, 0)
  return d
}

test("What's due", async ({ browser }) => {
  await resetMock()
  // Task A overdue, Task B (Jan 1) overdue but moved to Done, Task C in three days, new ones in 5, 14 and 30 days.
  await api('PUT', '/tasks/1', { title: 'Task A', description: 'first', due_date: local(inDays(-1)) })
  await api('PATCH', '/tasks/2/move', { columnId: 12, position: 0 })
  await api('PUT', '/tasks/3', { title: 'Task C', description: null, due_date: local(inDays(3)) })
  await api('POST', '/tasks', { title: 'Task D', description: null, due_date: local(inDays(30)), columnId: 10 })
  await api('POST', '/tasks', { title: 'Task E', description: null, due_date: local(inDays(5)), columnId: 10 })
  await api('POST', '/tasks', { title: 'Task F', description: null, due_date: local(inDays(14)), columnId: 10 })

  const page = await browser.newPage({ viewport: { width: 1400, height: 900 } })
  const errors = []
  page.on('pageerror', (e) => errors.push(e.message))
  const ensure = (cond, msg) => { if (!cond) throw new Error(msg) }
  const group = (key) => page.locator(`[data-group="${key}"]`)
  const titles = async (key) => group(key).getByTestId('due-task').locator('.font-medium.block').allInnerTexts()

  await page.goto(`${BASE}/boards`)
  await page.getByRole('link', { name: "What's due" }).click()
  await page.getByTestId('due').waitFor()
  await group('overdue').waitFor()

  await test.step('grouped by when they are due, done ones left out', async () => {
    ensure(JSON.stringify(await titles('overdue')) === '["Task A"]', JSON.stringify(await titles('overdue')))
    ensure(JSON.stringify(await titles('days3')) === '["Task C"]', JSON.stringify(await titles('days3')))
    ensure(JSON.stringify(await titles('week')) === '["Task E"]', JSON.stringify(await titles('week')))
    ensure(JSON.stringify(await titles('days14')) === '["Task F"]', JSON.stringify(await titles('days14')))
    ensure(JSON.stringify(await titles('later')) === '["Task D"]', JSON.stringify(await titles('later')))
    ensure((await page.getByText('Task B').count()) === 0, 'task in the last column listed')
    ensure((await group('days3').innerText()).includes('My board · In progress'), await group('days3').innerText())
    ensure((await page.title()).startsWith("What's due"), await page.title())
    await page.screenshot({ path: `${test.info().outputDir}/due-1.png` })
  })

  await test.step('a click opens the task on its board', async () => {
    await group('days3').getByTestId('due-task').click()
    await dialogOpened(page)
    await page.getByTestId('task-view').getByText('Task C').first().waitFor()
    ensure(new URL(page.url()).pathname === '/boards/my-board', page.url())
    ensure(new URL(page.url()).search === '', `query kept: ${page.url()}`)
  })

  await test.step('back on the board it was opened from, too', async () => {
    await page.keyboard.press('Escape')
    await page.waitForFunction(() => !document.querySelector('.p-dialog'))
    await page.getByRole('link', { name: "What's due" }).click()
    await group('overdue').getByTestId('due-task').click()
    await dialogOpened(page)
    await page.getByTestId('task-view').getByText('Task A').first().waitFor()
  })

  await test.step('a note reminder shows with its task and goes when done', async () => {
    await api('POST', '/tasks/3/notes', { text: 'Ask about the salary', remindAt: local(inDays(1)) })
    await page.keyboard.press('Escape')
    await page.waitForFunction(() => !document.querySelector('.p-dialog'))
    await page.getByRole('link', { name: "What's due" }).click()
    const note = group('days3').getByTestId('due-note')
    await note.waitFor()
    ensure((await note.innerText()).includes('Task C') && (await note.innerText()).includes('Ask about the salary'), await note.innerText())
    // Tomorrow's reminder comes before Task C's due date in three days.
    const rows = await group('days3').locator('li').allInnerTexts()
    ensure(rows[0].includes('Ask about the salary') && rows[1].includes('Task C') && !rows[1].includes('salary'), JSON.stringify(rows))
    await page.screenshot({ path: `${test.info().outputDir}/due-2-note.png` })
    await group('days3').getByRole('button', { name: 'Reminder done: Ask about the salary' }).click()
    await note.waitFor({ state: 'detached' })
    const saved = (await (await fetch(`${BASE}/api/__state`)).json()).notes.find((n) => n.text === 'Ask about the salary')
    ensure(saved && saved.remindAt === null, JSON.stringify(saved))
  })

  await test.step('a task that is gone says so', async () => {
    await page.goto(`${BASE}/boards/my-board?task=999`)
    await page.locator('.p-toast').getByText('Task not found').waitFor()
  })

  ensure(errors.length === 0, errors.join('\n'))
})
