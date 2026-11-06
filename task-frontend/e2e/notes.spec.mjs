// Notes on a task: add, edit, delete, plain text, not on shared boards.
import { test } from '@playwright/test'
import { BASE, resetMock } from './support.mjs'

test('Task notes', async ({ browser }) => {
  await resetMock()
  const page = await browser.newPage({ viewport: { width: 1400, height: 900 } })
  const errors = []
  page.on('pageerror', (e) => errors.push(e.message))
  const ensure = (cond, msg) => { if (!cond) throw new Error(msg) }
  const state = async () => (await fetch(`${BASE}/api/__state`)).json()
  const section = () => page.getByTestId('task-notes')
  const noteTexts = () => section().getByTestId('note').locator('p.whitespace-pre-wrap').allInnerTexts()
  const closeDialog = async () => {
    await page.keyboard.press('Escape')
    await page.waitForFunction(() => !document.querySelector('.p-dialog'))
  }
  async function openView(id) {
    await page.locator(`[data-task-id="${id}"] h3`).click()
    await page.getByTestId('task-view').waitFor()
  }

  await page.goto(`${BASE}/boards`)
  await page.locator('[data-task-id="1"]').waitFor()

  await test.step('a task starts without notes; the add button shows once there is text', async () => {
    await openView(1)
    await section().waitFor()
    ensure((await section().getByTestId('note').count()) === 0, 'notes shown')
    ensure((await section().getByRole('button', { name: 'Add note' }).count()) === 0, 'button without text')
    await section().getByRole('textbox', { name: 'New note' }).fill('Called HR')
    ensure(await section().getByRole('button', { name: 'Add note' }).isVisible(), 'button missing')
  })

  await test.step('adding keeps line breaks, shows author and time, newest first', async () => {
    await section().getByRole('button', { name: 'Add note' }).click()
    await section().getByTestId('note').first().waitFor()
    const box = section().getByRole('textbox', { name: 'New note' })
    ensure((await box.inputValue()) === '', 'draft not cleared')
    await box.fill('Second line one\nSecond line two')
    await box.press('Control+Enter')
    await page.waitForFunction(() => document.querySelectorAll('[data-testid="note"]').length === 2)
    const texts = await noteTexts()
    ensure(JSON.stringify(texts) === JSON.stringify(['Second line one\nSecond line two', 'Called HR']), JSON.stringify(texts))
    const meta = await section().getByTestId('note').first().innerText()
    ensure(meta.includes('Alice') && meta.includes('less than a minute ago'), meta)
    ensure((await section().locator('h3').innerText()).includes('(2)'), 'count missing')
    ensure((await state()).notes.length === 2, 'not saved')
  })

  await test.step('text is shown as typed, no markdown or html', async () => {
    const box = section().getByRole('textbox', { name: 'New note' })
    await box.fill('**not bold** <b>no html</b>')
    await box.press('Control+Enter')
    await page.waitForFunction(() => document.querySelectorAll('[data-testid="note"]').length === 3)
    const first = section().getByTestId('note').first()
    ensure((await first.locator('strong, b').count()) === 0, 'formatted')
    ensure((await first.locator('p').first().innerText()) === '**not bold** <b>no html</b>', 'text changed')
  })

  await test.step('editing saves the new text and marks it as edited', async () => {
    const note = section().getByTestId('note').nth(2) // "Called HR"
    await note.hover()
    await note.getByRole('button', { name: 'Edit note' }).click()
    const box = note.getByRole('textbox', { name: 'Edit note' })
    ensure((await box.inputValue()) === 'Called HR', 'edit box not filled')
    await box.fill('Called HR, interview on Friday')
    await note.getByRole('button', { name: 'Save' }).click()
    await note.getByText('Called HR, interview on Friday').waitFor()
    ensure((await note.innerText()).includes('edited'), 'no edited mark')
    ensure((await state()).notes.some((n) => n.text === 'Called HR, interview on Friday' && n.edited), 'not saved')
  })

  await test.step('Esc while editing cancels the edit, not the dialog', async () => {
    const note = section().getByTestId('note').first()
    await note.hover()
    await note.getByRole('button', { name: 'Edit note' }).click()
    await note.getByRole('textbox', { name: 'Edit note' }).fill('changed but cancelled')
    await page.keyboard.press('Escape')
    ensure(await page.getByTestId('task-view').isVisible(), 'dialog closed')
    ensure((await note.locator('p').first().innerText()) === '**not bold** <b>no html</b>', 'edit not cancelled')
  })

  await test.step('deleting asks first', async () => {
    const note = section().getByTestId('note').first()
    await note.hover()
    await note.getByRole('button', { name: 'Delete note' }).click()
    const confirm = page.locator('.p-confirmdialog')
    await confirm.getByRole('button', { name: 'Delete note' }).click()
    await page.waitForFunction(() => document.querySelectorAll('[data-testid="note"]').length === 2)
    ensure((await state()).notes.length === 2, 'not deleted on server')
    await page.screenshot({ path: test.info().outputPath('notes.png') })
    await closeDialog()
  })

  await test.step('notes belong to their task and are there after reopening', async () => {
    await openView(2)
    await section().waitFor()
    await page.waitForTimeout(200)
    ensure((await section().getByTestId('note').count()) === 0, 'notes of task 1 shown on task 2')
    await closeDialog()
    await openView(1)
    await page.waitForFunction(() => document.querySelectorAll('[data-testid="note"]').length === 2)
    await closeDialog()
  })

  await test.step('a note can remind; the first pick of a day means noon; done keeps the note', async () => {
    await openView(1)
    await section().getByRole('textbox', { name: 'New note' }).fill('Call back')
    await section().locator('#note-remind').click()
    await page.locator('.p-datepicker-panel').getByRole('button', { name: 'Today' }).click()
    // With a time the panel stays open; a click elsewhere closes it.
    await page.locator('.p-dialog-header').click({ position: { x: 5, y: 5 } })
    await page.locator('.p-datepicker-panel').waitFor({ state: 'hidden' })
    await section().getByRole('button', { name: 'Add note' }).click()
    const reminder = section().getByTestId('note-reminder')
    await reminder.getByText('Reminder Today, 12:00').waitFor()
    const saved = (await state()).notes.find((n) => n.text === 'Call back')
    const d = new Date()
    const today = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
    ensure(saved.remindAt === `${today}T12:00:00`, saved.remindAt)

    await page.screenshot({ path: test.info().outputPath('notes-reminder.png') })
    await reminder.getByRole('button', { name: 'Done' }).click()
    await reminder.waitFor({ state: 'detached' })
    ensure((await state()).notes.find((n) => n.text === 'Call back').remindAt === null, 'reminder kept')
    ensure((await noteTexts()).includes('Call back'), 'note gone')
    await closeDialog()
  })

  await test.step('notes are not in the editor and not on shared boards', async () => {
    await openView(1)
    await page.locator('.p-dialog').getByRole('button', { name: 'Edit', exact: true }).click()
    await page.locator('#task-title').waitFor()
    ensure((await section().count()) === 0, 'notes in edit mode')
    await closeDialog()
    const guest = await browser.newPage({ viewport: { width: 1400, height: 900 } })
    await guest.goto(`${BASE}/share/test-token`)
    await guest.locator('[data-task-id="1"] h3').click()
    await guest.getByTestId('task-view').waitFor()
    ensure((await guest.getByTestId('task-notes').count()) === 0, 'notes on shared board')
    ensure(!(await guest.content()).includes('interview on Friday'), 'note text leaked')
    await guest.close()
  })

  await test.step('phone and dark mode', async () => {
    const phone = await browser.newPage({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true, colorScheme: 'dark' })
    await phone.goto(`${BASE}/boards`)
    await phone.locator('[data-task-id="1"] h3').tap()
    await phone.getByTestId('task-notes').getByTestId('note').first().waitFor()
    // Touch screens have no hover: edit and delete must be visible.
    const edit = phone.getByTestId('note').first().getByRole('button', { name: 'Edit note' })
    ensure((await edit.evaluate((el) => getComputedStyle(el.parentElement).opacity)) === '1', 'buttons hidden on touch')
    ensure(!(await phone.evaluate(() => document.documentElement.scrollWidth > window.innerWidth)), 'sideways scroll')
    await phone.screenshot({ path: test.info().outputPath('notes-phone-dark.png') })
    await phone.close()
  })

  ensure(errors.length === 0, `page errors: ${errors.join('; ')}`)
  for (const context of browser.contexts()) await context.close()
})
