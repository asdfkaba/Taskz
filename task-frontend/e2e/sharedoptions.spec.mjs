// Share link options: the owner chooses whether the link also shows attachments, notes and history.
import { test } from '@playwright/test'
import { BASE, resetMock } from './support.mjs'

test('Share link shows attachments, notes and history only when the owner allows it', async ({ browser }) => {
  await resetMock()
  const outDir = test.info().outputDir
  const host = new URL(BASE).hostname
  const page = await (await browser.newContext({ viewport: { width: 1400, height: 900 } })).newPage()
  const anon = await browser.newContext({ viewport: { width: 1400, height: 900 } })
  await anon.addCookies([{ name: 'mock_anon', value: '1', domain: host, path: '/' }])
  const guest = await anon.newPage()
  const errors = []
  for (const p of [page, guest]) p.on('pageerror', (e) => errors.push(e.message))
  const ensure = (cond, msg) => { if (!cond) throw new Error(msg) }
  const api = (url, method, body) => fetch(`${BASE}/api${url}`, { method, body: body && JSON.stringify(body) })

  // Task 1 gets a file, a note with a reminder, and with them history entries.
  const form = new FormData()
  form.append('file', new Blob(['# Plan\nStep one']), 'plan.md')
  ensure((await fetch(`${BASE}/api/tasks/1/attachments`, { method: 'POST', body: form })).ok, 'upload')
  await api('/tasks/1/notes', 'POST', { text: 'Called back', remindAt: '2030-01-01T12:00:00' })
  const share = await (await api('/boards/1/share', 'POST')).json()

  const view = () => guest.getByTestId('task-view')
  const dialog = () => guest.locator('.p-dialog')
  async function openTask() {
    await guest.locator('[data-task-id="1"] h3').click()
    await view().waitFor()
  }
  async function closeTask() {
    await guest.keyboard.press('Escape')
    await guest.waitForFunction(() => !document.querySelector('.p-dialog'))
  }

  await test.step('by default the link shows none of them', async () => {
    await guest.goto(`${BASE}/share/${share.path}`)
    await openTask()
    for (const id of ['task-attachments', 'task-notes', 'task-history']) {
      ensure((await dialog().getByTestId(id).count()) === 0, `${id} shown`)
    }
    await closeTask()
  })

  await test.step('owner ticks the options in the settings', async () => {
    await page.goto(`${BASE}/boards`)
    await page.getByText('Task A', { exact: true }).waitFor()
    await page.getByRole('button', { name: 'Board settings' }).click()
    for (const name of ['Attachments', 'Notes', 'History']) {
      const box = page.getByRole('checkbox', { name })
      ensure(!(await box.isChecked()), `${name} ticked before`)
      await box.check()
    }
    await page.waitForFunction(async () => {
      const s = (await (await fetch('/api/__state')).json()).share
      return s.showAttachments && s.showNotes && s.showHistory
    })
    await page.screenshot({ path: `${outDir}/so-1-settings.png` })
  })

  await test.step('the open link picks them up live and shows them read-only', async () => {
    await openTask()
    const files = dialog().getByTestId('task-attachments')
    await files.getByText('plan.md').waitFor()
    ensure((await files.getByRole('button', { name: 'Add file' }).count()) === 0, 'upload offered')
    ensure((await files.getByRole('button', { name: /^Delete/ }).count()) === 0, 'delete offered')

    const notes = dialog().getByTestId('task-notes')
    await notes.getByText('Called back').waitFor()
    ensure((await notes.getByTestId('note-reminder').count()) === 0, 'reminder shown')
    ensure((await dialog().locator('textarea').count()) === 0, 'note form shown')

    const history = dialog().getByTestId('task-history')
    await history.getByRole('button', { name: 'History' }).click()
    await history.getByText(/plan\.md/).first().waitFor()
    await guest.screenshot({ path: `${outDir}/so-2-guest.png` })
  })

  await test.step('a Markdown file opens in the preview through the link', async () => {
    await dialog().getByTestId('task-attachments').getByRole('link', { name: 'plan.md' }).click()
    await guest.getByTestId('attachment-preview').getByText('Step one').waitFor()
    await guest.keyboard.press('Escape')
    await guest.getByTestId('attachment-preview').waitFor({ state: 'hidden' })
    await closeTask()
  })

  await test.step('unticking hides them again, and the link stays the same', async () => {
    await page.getByRole('checkbox', { name: 'Notes' }).uncheck()
    await page.waitForFunction(async () => !(await (await fetch('/api/__state')).json()).share.showNotes)
    ensure((await page.getByRole('textbox', { name: 'Share link' }).inputValue()).endsWith(share.path), 'link changed')
    await guest.waitForTimeout(500) // the live update
    await openTask()
    await dialog().getByTestId('task-attachments').getByText('plan.md').waitFor()
    ensure((await dialog().getByTestId('task-notes').count()) === 0, 'notes still shown')
    const blocked = await guest.evaluate(async (path) => (await fetch(`/api/share/${path}/tasks/1/notes`)).status, share.path)
    ensure(blocked === 404, `notes still readable: ${blocked}`)
  })

  if (errors.length) throw new Error(`page errors: ${errors.join('; ')}`)
  for (const context of browser.contexts()) await context.close()
})
