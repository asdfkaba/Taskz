// A user's icon (identicon from their id) is the same everywhere: header, who is online,
// notes, history and the member list.
import { test } from '@playwright/test'
import { BASE, resetMock } from './support.mjs'

test('User icons', async ({ browser }) => {
  await resetMock()
  const page = await browser.newPage({ viewport: { width: 1400, height: 900 } })
  const errors = []
  page.on('pageerror', (e) => errors.push(e.message))
  const ensure = (cond, msg) => { if (!cond) throw new Error(msg) }
  const out = (name) => `${test.info().outputDir}/${name}`
  const api = (url, method, body) => fetch(`${BASE}/api${url}`, { method, body: body && JSON.stringify(body) })
  const svgOf = (locator) => locator.locator('svg').first().evaluate((svg) => svg.outerHTML)

  await page.goto(`${BASE}/boards/my-board`)
  await page.locator('[data-task-id="1"]').waitFor()
  const header = page.getByTestId('header-user')
  const mine = await svgOf(header)

  await test.step('the header shows your icon instead of the email', async () => {
    ensure((await header.getAttribute('title')) === 'Alice · alice@example.com', await header.getAttribute('title'))
    ensure((await page.locator('header').first().getByText('alice@example.com').count()) === 0, 'email still shown')
  })

  await test.step('the same icon among who is online', async () => {
    await api('/__presence', 'POST', [{ id: 2, name: 'Bob' }])
    await page.getByTestId('presence-me').waitFor()
    ensure((await svgOf(page.getByTestId('presence-me'))) === mine, 'presence differs from header')
    ensure((await svgOf(page.getByTestId('presence-other'))) !== mine, "Bob has Alice's icon")
    await page.locator('header').first().screenshot({ path: out('ui-1-header.png') })
  })

  await test.step('next to notes and history entries', async () => {
    await page.locator('[data-task-id="1"] h3').click()
    await page.getByTestId('task-view').waitFor()
    const notes = page.getByTestId('task-notes')
    await notes.getByRole('textbox', { name: 'New note' }).fill('Called them')
    await notes.getByRole('button', { name: /Add note/ }).click()
    await notes.getByTestId('note-user').first().waitFor()
    ensure((await svgOf(notes.getByTestId('note-user').first())) === mine, 'note icon differs')

    await api('/tasks/1', 'PUT', { title: 'Task A2', description: 'first' })
    await page.getByTestId('task-history').getByRole('button', { name: /History/ }).click()
    const entry = page.getByTestId('task-history').getByTestId('event-user').first()
    await entry.waitFor()
    ensure((await svgOf(entry)) === mine, 'history icon differs')
    await page.screenshot({ path: out('ui-2-task.png') })
    await page.keyboard.press('Escape')
  })

  await test.step('next to who uploaded a file', async () => {
    await page.locator('[data-task-id="1"] h3').click()
    await page.getByTestId('task-view').waitFor()
    await page.getByTestId('task-attachments').locator('input[type="file"]').setInputFiles({ name: 'notes.txt', mimeType: 'text/plain', buffer: Buffer.from('hello') })
    const uploader = page.getByTestId('task-attachments').getByTestId('attachment-user').first()
    await uploader.waitFor()
    ensure((await svgOf(uploader)) === mine, 'uploader icon differs')
    await page.keyboard.press('Escape')
    await page.waitForFunction(() => !document.querySelector('.p-dialog'))
  })

  await test.step("the inviting owner's icon on the invite and on the joined board", async () => {
    const bobOnline = await svgOf(page.getByTestId('presence-other')) // Bob, id 2, from the step above
    await page.goto(`${BASE}/join/team-invite`)
    const inviter = page.getByTestId('invite-owner')
    await inviter.waitFor()
    ensure((await svgOf(inviter)) === bobOnline, 'invite shows another icon than Bob online')
    await page.screenshot({ path: out('ui-3-invite.png') })
    await page.getByRole('button', { name: 'Join board' }).click()
    await page.waitForURL(/\/boards\/team-board$/)
    const owner = page.getByRole('navigation', { name: 'Boards' }).first().getByTestId('board-owner')
    await owner.waitFor()
    ensure((await svgOf(owner)) === bobOnline, 'sidebar shows another icon than Bob online')
    await page.goto(`${BASE}/boards/my-board`)
  })

  await test.step('in the member list', async () => {
    await page.goto(`${BASE}/boards/my-board/settings`)
    const owner = page.getByTestId('board-members').getByTestId('member').first()
    await owner.getByText('Owner').waitFor()
    ensure((await svgOf(owner)) === mine, 'member icon differs')
  })

  if (errors.length) throw new Error(`page errors: ${errors.join('; ')}`)
  await page.close()
})
