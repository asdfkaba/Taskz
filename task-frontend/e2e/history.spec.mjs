// Task history in the detail view.
import { test } from '@playwright/test'
import { BASE, dialogOpened, resetMock } from './support.mjs'

test('Task history', async ({ browser }) => {
  const base = BASE
  const outDir = test.info().outputDir
  await resetMock()

  // Fixed zone: the server sends UTC and the page shows local time.
  const page = await browser.newPage({ viewport: { width: 1400, height: 900 }, timezoneId: 'Europe/Berlin' })
  const errors = []
  page.on('pageerror', (e) => errors.push(e.message))
  async function check(...args) {
    const fn = args.pop()
    await test.step(args.pop(), fn)
  }
  const expect = (c, m) => { if (!c) throw new Error(m) }
  const card = (id) => page.locator(`[data-task-id="${id}"]`)
  const dialog = () => page.locator('.p-dialog').first()
  const history = () => page.getByTestId('task-history')
  const closed = () => page.waitForFunction(() => !document.querySelector('.p-dialog'))

  async function openView(id) {
    await card(id).locator('h3').click()
    await page.getByTestId('task-view').waitFor()
  }
  async function openHistory() {
    await history().getByRole('button', { name: 'History' }).click()
    await history().locator('ol').waitFor()
  }
  async function edit(fill) {
    await dialogOpened(page)
    await dialog().getByRole('button', { name: 'Edit', exact: true }).click()
    await page.locator('#task-title').waitFor()
    await fill()
    await page.getByRole('button', { name: 'Save changes' }).click()
    await page.getByTestId('task-view').waitFor()
  }
  const entryTexts = async () => (await history().locator('li[data-event-type]').allInnerTexts()).map((t) => t.replace(/\s+/g, ' ').trim())

  await page.goto(`${base}/boards`)
  await page.getByText('Task A', { exact: true }).waitFor()

  await check('history is collapsed and empty for an untouched task', async () => {
    await openView(1)
    expect((await history().getByRole('button', { name: 'History' }).getAttribute('aria-expanded')) === 'false', 'not collapsed')
    expect((await history().locator('ol').count()) === 0, 'list shown while collapsed')
    await openHistory()
    await history().getByText('No changes recorded yet.').waitFor()
  })

  await check('saving records each changed field in words', async () => {
    await edit(async () => {
      await page.locator('#task-title').fill('Task A renamed')
      await page.locator('#task-description').fill('Line one\nLine two')
    })
    await openHistory()
    const [latest] = await entryTexts()
    for (const part of ['Renamed from “Task A” to “Task A renamed”', 'Changed the description', 'Alice · less than a minute ago']) {
      expect(latest.includes(part), `missing "${part}" in: ${latest}`)
    }
    expect(!latest.includes('due date'), 'unchanged field listed')
  })

  await check('description changes can be shown as a diff', async () => {
    await edit(async () => {
      await page.locator('#task-description').fill('Line one\nLine 2\nLine three')
    })
    await openHistory()
    const latest = history().locator('li[data-event-type]').first()
    expect((await latest.innerText()).includes('Changed the description'), 'no description line')
    await latest.getByRole('button', { name: 'Show changes' }).click()
    const diff = latest.getByTestId('description-diff')
    const types = await diff.locator('[data-line]').evaluateAll((els) => els.map((e) => `${e.dataset.line}:${e.textContent.slice(2)}`))
    expect(JSON.stringify(types) === JSON.stringify(['same:Line one', 'removed:Line two', 'added:Line 2', 'added:Line three']), JSON.stringify(types))
    await page.screenshot({ path: `${outDir}/hi-1-diff.png` })
    await latest.getByRole('button', { name: 'Hide changes' }).click()
    expect((await latest.getByTestId('description-diff').count()) === 0, 'diff still shown')
  })

  await check('history is newest first', async () => {
    const texts = await entryTexts()
    expect(texts[0].includes('Changed the description') && texts[1].includes('Renamed'), JSON.stringify(texts))
    await page.keyboard.press('Escape')
    await closed()
  })

  await check('moving to another column is recorded', async () => {
    await card(1).hover()
    await card(1).getByRole('button', { name: 'Move to In progress' }).click()
    await page.waitForTimeout(400)
    await openView(1)
    await openHistory()
    expect((await entryTexts())[0].includes('Moved from To do to In progress'), (await entryTexts())[0])
    await page.keyboard.press('Escape')
    await closed()
  })

  await check('labels and due date are recorded', async () => {
    await openView(1)
    await edit(async () => {
      await dialog().getByRole('button', { name: 'Edit labels' }).click()
      await page.getByTestId('label-picker').getByRole('button', { name: 'Create a label' }).click()
      await page.getByRole('textbox', { name: 'Label name' }).fill('Urgent')
      await page.getByTestId('label-form').getByRole('button', { name: 'Create' }).click()
      await page.getByTestId('label-form').waitFor({ state: 'detached' })
      await page.locator('#task-due').click()
      await page.locator('.p-datepicker-panel td:not(.p-datepicker-other-month) span', { hasText: /^15$/ }).first().click()
      await page.keyboard.press('Escape')
    })
    await openHistory()
    const [latest] = await entryTexts()
    expect(latest.includes('Added label Urgent'), latest)
    expect(latest.includes('Set the due date to'), latest)
    await page.screenshot({ path: `${outDir}/hi-2-history.png` })
    await page.keyboard.press('Escape')
    await closed()
  })

  await check('older task without events shows its creation date', async () => {
    await openView(3)
    await openHistory()
    const texts = await entryTexts()
    expect(texts.length === 1 && texts[0].startsWith('Created'), JSON.stringify(texts))
    // Created 2026-08-01T10:00:00Z, which is 12:00 summer time in Berlin.
    const title = await history().locator('time').first().getAttribute('title')
    expect(title === '2026-08-01 12:00', title)
    await page.keyboard.press('Escape')
    await closed()
  })

  await check('times follow the browser time zone', async () => {
    const other = await browser.newPage({ viewport: { width: 1400, height: 900 }, timezoneId: 'America/New_York' })
    await other.goto(`${base}/boards`)
    await other.locator('[data-task-id="3"] h3').click()
    await other.getByTestId('task-history').getByRole('button', { name: 'History' }).click()
    const title = await other.getByTestId('task-history').locator('time').first().getAttribute('title')
    expect(title === '2026-08-01 06:00', title)
    await other.close()
  })

  await check('old priority-only events are left out, mixed ones keep their other lines', async () => {
    const post = (body) => fetch(`${base}/api/__event`, { method: 'POST', body: JSON.stringify(body) })
    await post({ taskId: 2, type: 'updated', changes: { priority: [2, 3] } })
    await post({ taskId: 2, type: 'updated', changes: { priority: [3, 1], title: ['Task B', 'Task B2'] } })
    await openView(2)
    await openHistory()
    const texts = await entryTexts()
    expect(texts.length === 1, `entries: ${JSON.stringify(texts)}`)
    expect(texts[0].startsWith('Renamed'), texts[0])
    expect(!texts.join(' ').toLowerCase().includes('priority'), 'priority mentioned')
    await page.keyboard.press('Escape')
    await closed()
  })

  await check('shared board shows no history', async () => {
    const guest = await browser.newPage({ viewport: { width: 1400, height: 900 } })
    await guest.goto(`${base}/share/test-token`)
    await guest.locator('[data-task-id="1"] h3').click()
    await guest.getByTestId('task-view').waitFor()
    expect((await guest.getByTestId('task-history').count()) === 0, 'history on shared board')
    await guest.close()
  })

  await check('dark mode', async () => {
    await page.evaluate(() => document.documentElement.classList.add('dark'))
    await openView(1)
    await openHistory()
    await history().locator('li[data-event-type]').nth(2).getByRole('button', { name: 'Show changes' }).click()
    await page.waitForTimeout(300)
    await page.screenshot({ path: `${outDir}/hi-3-dark.png` })
  })

  if (errors.length) throw new Error(`page errors: ${errors.join('; ')}`)
  for (const context of browser.contexts()) await context.close()
})
