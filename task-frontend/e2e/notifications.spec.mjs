// The bell: notifications arrive live on an open board; opening the bell makes them seen (the count goes),
// reading is separate; they link to their task (or nothing);
// NOTIFY automations pick board members and skip whoever moved the task.
import { test } from '@playwright/test'
import { BASE, dialogOpened, resetMock } from './support.mjs'

test('Notifications', async ({ browser }) => {
  await resetMock()
  const page = await browser.newPage({ viewport: { width: 1400, height: 900 } })
  const errors = []
  page.on('pageerror', (e) => errors.push(e.message))
  const ensure = (cond, msg) => { if (!cond) throw new Error(msg) }
  const state = async () => (await fetch(`${BASE}/api/__state`)).json()
  const notify = (body) => fetch(`${BASE}/api/__notify`, { method: 'POST', body: JSON.stringify(body) })
  const bell = () => page.getByTestId('notification-bell')
  const count = () => page.getByTestId('notification-count')
  const list = () => page.getByTestId('notifications')

  await page.goto(`${BASE}/boards/my-board`)
  await page.locator('[data-task-id="1"]').waitFor()
  // The board stream is open once the page heard "presence" or nothing; give it a moment.
  await page.waitForTimeout(300)

  await test.step('a new notification shows on the open board right away', async () => {
    ensure((await count().count()) === 0, 'count without notifications')
    await bell().click()
    await list().getByText('No notifications yet.').waitFor()
    await page.keyboard.press('Escape')
    await notify({ text: 'Ready for review', boardName: 'My board', boardSlug: 'my-board', taskId: 3, taskTitle: 'Task C' })
    await count().getByText('1').waitFor()
    ensure((await bell().getAttribute('aria-label')) === 'Notifications, 1 new', await bell().getAttribute('aria-label'))
  })

  await test.step('opening the bell makes it seen but leaves it unread for later', async () => {
    await bell().click()
    await count().waitFor({ state: 'detached' })
    const item = list().getByTestId('notification').first()
    ensure((await item.getAttribute('data-read')) === 'false', 'read by opening the bell')
    const saved = (await state()).notifications[0]
    ensure(saved.seen === true && saved.read === false, JSON.stringify(saved))
    await page.keyboard.press('Escape')
    await page.reload()
    await page.locator('[data-task-id="1"]').waitFor()
    ensure((await count().count()) === 0, 'count back after a reload')
  })

  await test.step('opening one marks it read and opens its task', async () => {
    await bell().click()
    const item = list().getByTestId('notification').first()
    ensure((await item.innerText()).includes('Task C · My board'), await item.innerText())
    ensure((await item.getAttribute('data-read')) === 'false', 'not unread')
    await page.locator('.p-popover').evaluate((el) => Promise.all(el.getAnimations({ subtree: true }).map((a) => a.finished.catch(() => {}))))
    await page.screenshot({ path: `${test.info().outputDir}/bell.png` })
    await item.click()
    await dialogOpened(page)
    await page.getByTestId('task-view').getByText('Task C').first().waitFor()
    ensure((await state()).notifications[0].read === true, 'not read on the server')
    await page.keyboard.press('Escape')
    await page.waitForFunction(() => !document.querySelector('.p-dialog'))
  })

  await test.step('pages without a board stream load on returning to the tab; mark all read', async () => {
    await page.getByRole('link', { name: "What's due" }).click()
    await page.getByTestId('due').waitFor()
    await notify({ text: 'Board backup done' })
    await notify({ text: 'Second one' })
    await page.evaluate(() => document.dispatchEvent(new Event('visibilitychange')))
    await count().getByText('2').waitFor()
    await bell().click()
    await count().waitFor({ state: 'detached' })
    // Without a board or task it links nowhere: the page stays.
    await list().getByText('Board backup done').click()
    ensure(new URL(page.url()).pathname === '/due', page.url())
    await page.waitForFunction(async (base) => (await (await fetch(`${base}/api/__state`)).json()).notifications[1].read, BASE)
    await bell().click()
    ensure((await list().locator('[data-read="false"]').count()) === 1, 'unread marks')
    await list().getByRole('button', { name: 'Mark all read' }).click()
    await list().locator('[data-read="false"]').waitFor({ state: 'detached' })
    ensure((await state()).notifications.every((n) => n.read), 'not all read')
    ensure((await list().getByRole('button', { name: 'Mark all read' }).count()) === 0, 'button without unread')
    await page.keyboard.press('Escape')
  })

  await test.step('a NOTIFY automation picks members and skips whoever moved the task', async () => {
    await fetch(`${BASE}/api/__members`, { method: 'POST', body: JSON.stringify([{ userId: 2, name: 'Bob', joined: '2026-11-02T20:00:00Z' }]) })
    await page.goto(`${BASE}/boards/my-board/settings`)
    const rules = page.getByTestId('board-rules')
    await rules.getByText('No automations yet.').waitFor()
    await rules.getByRole('combobox', { name: 'Column' }).click()
    await page.getByRole('option', { name: 'In progress', exact: true }).click()
    await rules.getByRole('combobox', { name: 'Action' }).click()
    await page.getByRole('option', { name: 'Notify people', exact: true }).click()
    await rules.getByRole('textbox', { name: 'Message' }).fill(' Ready for review ')
    ensure(await rules.getByRole('button', { name: 'Add automation' }).isDisabled(), 'added without people')
    await rules.locator('.p-multiselect').click()
    await page.getByRole('option', { name: 'Alice' }).click()
    await page.getByRole('option', { name: 'Bob' }).click()
    await page.keyboard.press('Escape')
    await rules.getByRole('button', { name: 'Add automation' }).click()
    await rules.getByText('Notify Alice, Bob: “Ready for review”').waitFor()
    const rule = (await state()).rules[0]
    ensure(rule.action === 'NOTIFY' && rule.text === 'Ready for review' && JSON.stringify(rule.userIds) === '[1,2]', JSON.stringify(rule))

    await page.goto(`${BASE}/boards/my-board`)
    const card = page.locator('[data-task-id="1"]')
    await card.hover()
    await card.getByRole('button', { name: 'Move to In progress' }).click()
    await page.waitForFunction(async (base) => (await (await fetch(`${base}/api/__state`)).json()).sentToOthers.length === 1, BASE)
    const sent = (await state()).sentToOthers
    ensure(JSON.stringify(sent) === '[{"userId":2,"text":"Ready for review","taskId":1}]', JSON.stringify(sent))
  })

  ensure(errors.length === 0, errors.join('\n'))
})
