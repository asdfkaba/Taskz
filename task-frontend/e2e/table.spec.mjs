// Table view: the board's tasks as a sortable table with a status select.
import { test } from '@playwright/test'
import { BASE, resetMock } from './support.mjs'

async function api(method, path, body) {
  const response = await fetch(`${BASE}/api${path}`, {
    method,
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  return response.json()
}

test('Table view', async ({ browser }) => {
  await resetMock()
  const remote = await api('POST', '/boards/1/labels', { name: 'Remote', color: '#3b82f6' })
  const task = await api('PUT', '/tasks/1', {
    title: 'Task A',
    description: null,
    due_date: null,
    labelIds: [remote.id],
    checklist: [{ text: 'Cover letter' }, { text: 'CV' }],
  })
  await api('PATCH', `/tasks/1/checklist/${task.checklist[0].id}`, { checked: true })

  const page = await browser.newPage({ viewport: { width: 1400, height: 900 } })
  const errors = []
  page.on('pageerror', (e) => errors.push(e.message))
  const ensure = (cond, msg) => { if (!cond) throw new Error(msg) }
  const state = async () => (await fetch(`${BASE}/api/__state`)).json()
  const table = () => page.getByTestId('task-table')
  const rows = () => table().locator('tbody tr')
  const titles = async () => (await table().locator('tbody tr td:first-child').allInnerTexts()).map((t) => t.trim())
  const header = (name) => table().locator('thead th').filter({ hasText: name })
  const out = (name) => `${test.info().outputDir}/${name}`

  await page.goto(`${BASE}/boards`)
  await page.locator('[data-task-id="1"]').waitFor()

  await test.step('switching to the table shows all tasks in board order', async () => {
    // The switch stays where it is: nothing in the header moves.
    const switchBox = async () => page.getByRole('group', { name: 'View' }).boundingBox()
    const boardBox = await switchBox()
    await page.getByRole('button', { name: 'Table view' }).click()
    await table().waitFor()
    ensure((await page.getByRole('button', { name: 'Table view' }).getAttribute('aria-pressed')) === 'true', 'not pressed')
    ensure((await page.locator('[data-task-id]').count()) === 0, 'cards still shown')
    ensure(JSON.stringify(await switchBox()) === JSON.stringify(boardBox), `switch moved: ${JSON.stringify([boardBox, await switchBox()])}`)
    ensure((await page.getByRole('button', { name: 'Compact board view' }).getAttribute('aria-pressed')) === 'false', 'compact pressed in the table')
    ensure(JSON.stringify(await titles()) === '["Task A","Task B","Task C"]', JSON.stringify(await titles()))
    const first = rows().first()
    ensure((await first.getByTestId('label-dots').getByRole('img').getAttribute('title')) === 'Remote', 'label dot')
    ensure((await first.innerText()).includes('1/2'), 'checklist')
    ensure((await rows().nth(1).innerText()).includes('Jan 1, 12:00'), 'due date')
    await page.screenshot({ path: out('tv-1-table.png') })
  })

  await test.step('sorting by title, by status and back to board order', async () => {
    await header('Title').click()
    await header('Title').click()
    ensure(JSON.stringify(await titles()) === '["Task C","Task B","Task A"]', `title desc ${JSON.stringify(await titles())}`)
    await header('Title').click()
    ensure(JSON.stringify(await titles()) === '["Task A","Task B","Task C"]', `unsorted ${JSON.stringify(await titles())}`)
    await header('Status').click()
    await header('Status').click()
    ensure((await titles())[0] === 'Task C', `status desc ${JSON.stringify(await titles())}`)
    await header('Status').click()
  })

  await test.step('tasks with a due date come first when sorting by due date', async () => {
    await header('Due').click()
    ensure((await titles())[0] === 'Task B', `due ${JSON.stringify(await titles())}`)
    await header('Due').click()
    await header('Due').click()
  })

  await test.step('a click on a row opens the task', async () => {
    await rows().nth(2).locator('td').nth(3).click()
    await page.getByTestId('task-view').waitFor()
    ensure((await page.locator('.p-dialog').innerText()).includes('Task C'), 'wrong task')
    await page.keyboard.press('Escape')
    await page.waitForFunction(() => !document.querySelector('.p-dialog'))
  })

  await test.step('the status select moves the task without opening it', async () => {
    await page.getByRole('combobox', { name: 'Status of Task A' }).click()
    await page.getByRole('option', { name: 'Done' }).click()
    await page.waitForFunction(async () => {
      const s = await (await fetch('/api/__state')).json()
      return s.tasks.find((t) => t.id === 1).columnId === 12
    })
    ensure((await page.locator('.p-dialog').count()) === 0, 'dialog opened')
    ensure((await page.getByRole('combobox', { name: 'Status of Task A' }).innerText()).includes('Done'), 'select not updated')
    ensure(JSON.stringify(await titles()) === '["Task B","Task C","Task A"]', `order after move ${JSON.stringify(await titles())}`)
    ensure((await state()).tasks.find((t) => t.id === 1).position === 0, 'position')
  })

  await test.step('search and empty result', async () => {
    await page.getByRole('textbox', { name: 'Search tasks' }).fill('Task B')
    await page.waitForFunction(() => document.querySelectorAll('[data-testid="task-table"] tbody tr').length === 1)
    await page.getByRole('textbox', { name: 'Search tasks' }).fill('nothing like this')
    await table().getByText('No tasks match the filter.').waitFor()
    await page.getByRole('textbox', { name: 'Search tasks' }).fill('')
  })

  await test.step('the view survives a reload', async () => {
    await page.reload()
    await table().waitFor()
    ensure((await rows().count()) === 3, 'rows')
  })

  await test.step('columns can be hidden; the choice survives a reload and a hidden column stops sorting', async () => {
    await header('Due').click()
    ensure((await titles())[0] === 'Task B', `due ${JSON.stringify(await titles())}`)
    await table().locator('.p-multiselect').click() // its combobox is a visually hidden input
    await page.getByRole('option', { name: 'Due' }).click()
    await page.getByRole('option', { name: 'Created' }).click()
    await page.keyboard.press('Escape')
    ensure((await header('Due').count()) === 0, 'due column still shown')
    ensure((await header('Created').count()) === 0, 'created column still shown')
    ensure(await header('Status').isVisible(), 'status column hidden')
    ensure(JSON.stringify(await titles()) === '["Task B","Task C","Task A"]', `still sorted ${JSON.stringify(await titles())}`)
    await page.reload()
    await table().waitFor()
    ensure((await header('Due').count()) === 0, 'due column back after reload')
    await table().locator('.p-multiselect').click() // its combobox is a visually hidden input
    await page.getByRole('option', { name: 'Due' }).click()
    await page.getByRole('option', { name: 'Created' }).click()
    await page.keyboard.press('Escape')
    ensure(await header('Due').isVisible(), 'due column not back')
  })

  await test.step('dark mode', async () => {
    await page.evaluate(() => document.documentElement.classList.add('dark'))
    await page.waitForTimeout(200)
    await page.screenshot({ path: out('tv-2-dark.png') })
    await page.evaluate(() => document.documentElement.classList.remove('dark'))
  })

  await test.step('phone width hides the extra columns', async () => {
    await page.setViewportSize({ width: 390, height: 844 })
    await page.waitForTimeout(300)
    ensure(!(await header('Labels').isVisible()), 'labels column visible')
    ensure(!(await header('Created').isVisible()), 'created column visible')
    ensure(await header('Status').isVisible(), 'status column hidden')
    ensure(!(await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth)), 'page scrolls sideways')
    await page.screenshot({ path: out('tv-3-phone.png') })
    await page.setViewportSize({ width: 1400, height: 900 })
  })

  await test.step('back to the board', async () => {
    await page.getByRole('button', { name: 'Board view', exact: true }).click()
    await page.locator('[data-task-id="1"]').waitFor()
    ensure((await table().count()) === 0, 'table still shown')
  })

  if (errors.length) throw new Error(`page errors: ${errors.join('; ')}`)
  for (const context of browser.contexts()) await context.close()
})
