// The checklist of a task: progress on the card, ticking in the view, editing in the dialog,
// history, shared boards. "- [ ]" in descriptions is plain text.
import { test } from '@playwright/test'
import { BASE, resetMock } from './support.mjs'

test('Task checklists', async ({ browser }) => {
  await resetMock()
  // Like the app: PUT replaces the editable fields. silent: open pages don't hear of it (yet).
  const put = (body, { silent = false } = {}) =>
    fetch(`${BASE}/api/tasks/1`, {
      method: 'PUT',
      headers: silent ? { 'x-mock-silent': '1' } : {},
      body: JSON.stringify({ title: 'Task A', description: 'first', due_date: null, ...body }),
    })
  const state = async () => (await fetch(`${BASE}/api/__state`)).json()
  const task1 = async () => (await state()).tasks.find((t) => t.id === 1)
  await put({ checklist: [{ text: 'Cover letter' }, { text: 'CV' }, { text: 'Research' }] })
  const research = (await task1()).checklist[2]
  await fetch(`${BASE}/api/tasks/1/checklist/${research.id}`, { method: 'PATCH', body: JSON.stringify({ checked: true }) })

  const page = await browser.newPage({ viewport: { width: 1400, height: 900 } })
  const errors = []
  page.on('pageerror', (e) => errors.push(e.message))
  const ensure = (cond, msg) => { if (!cond) throw new Error(msg) }
  const progress = () => page.locator('[data-task-id="1"]').getByTestId('checklist-progress')
  const boxes = () => page.getByTestId('task-checklist').getByRole('checkbox')
  const checked = async () => boxes().evaluateAll((els) => els.map((e) => e.checked))
  const ticks = async (n) => page.waitForFunction((count) => document.querySelectorAll('[data-testid="task-checklist"] input:checked').length === count, n)
  const historyFirst = () => page.getByTestId('task-history').locator('li[data-event-type]').first()
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

  await test.step('the card shows the progress', async () => {
    ensure((await progress().innerText()).trim() === '1/3', await progress().innerText())
    ensure((await progress().getAttribute('aria-label')) === '1 of 3 checklist items done', 'label')
    ensure((await page.locator('[data-task-id="2"]').getByTestId('checklist-progress').count()) === 0, 'progress without items')
  })

  await test.step('ticking in the view is saved right away', async () => {
    await openView(1)
    ensure(JSON.stringify(await checked()) === '[false,false,true]', JSON.stringify(await checked()))
    await page.getByTestId('task-checklist').getByText('CV').click()
    await ticks(2)
    ensure((await task1()).checklist.map((i) => i.checked).join() === 'false,true,true', 'not saved')
    ensure((await progress().innerText()).trim() === '2/3', 'card not updated')
    ensure((await page.getByTestId('task-view').count()) === 1, 'left the view')
  })

  await test.step('the history says what was ticked', async () => {
    await page.getByTestId('task-history').getByRole('button', { name: 'History' }).click()
    await historyFirst().waitFor()
    ensure((await historyFirst().getAttribute('data-event-type')) === 'checked', await historyFirst().getAttribute('data-event-type'))
    ensure((await historyFirst().innerText()).includes('Checked “CV”'), await historyFirst().innerText())
  })

  await test.step('unticking with the keyboard; history updates while open', async () => {
    await boxes().nth(2).focus()
    await page.keyboard.press('Space')
    await ticks(1)
    await page.waitForFunction(() => document.querySelector('[data-testid="task-history"] li[data-event-type]')?.dataset.eventType === 'unchecked')
    ensure((await historyFirst().innerText()).includes('Unchecked “Research”'), await historyFirst().innerText())
  })

  await test.step('all done turns the progress green', async () => {
    await boxes().nth(0).click()
    await ticks(2)
    await boxes().nth(2).click()
    await ticks(3)
    await closeDialog()
    ensure((await progress().innerText()).trim() === '3/3', await progress().innerText())
    ensure((await progress().getAttribute('class')).includes('text-green-700'), 'not green')
  })

  await test.step('editing: rename, reorder, remove, add; ticks stay with their items', async () => {
    await openView(1)
    await page.getByRole('button', { name: 'Edit', exact: true }).click()
    const editor = page.getByTestId('checklist-editor')
    await editor.waitFor()
    // The dialog moves focus to the title after its animation; type only after that.
    await page.locator('.p-dialog').evaluate((el) => Promise.all(el.getAnimations({ subtree: true }).map((a) => a.finished)))
    await page.locator('#task-title:focus').waitFor()
    await editor.getByRole('textbox', { name: 'Checklist item 1' }).fill('Cover letter v2')
    await editor.getByRole('button', { name: 'Move Research up' }).click()
    await editor.getByRole('button', { name: 'Move Research up' }).click()
    await editor.getByRole('button', { name: 'Remove CV' }).click()
    const newItem = editor.getByRole('textbox', { name: 'New checklist item' })
    await newItem.fill('- [ ] Photo')
    await newItem.press('Enter')
    ensure((await newItem.inputValue()) === '', 'new item field not cleared')
    ensure((await page.getByTestId('task-view').count()) === 0, 'Enter submitted the form')
    await page.getByRole('button', { name: 'Save changes' }).click()
    await page.getByTestId('task-view').waitFor()

    const items = (await task1()).checklist
    ensure(items.map((i) => `${i.checked ? 'x:' : ''}${i.text}`).join() === 'x:Research,x:Cover letter v2,Photo', JSON.stringify(items))
    ensure((await progress().innerText()).trim() === '2/3', await progress().innerText())
    await page.getByTestId('task-history').getByRole('button', { name: 'History' }).click()
    const text = await historyFirst().innerText()
    ensure(text.includes('Added checklist items “Cover letter v2”, “Photo”'), text)
    ensure(text.includes('Removed checklist items “Cover letter”, “CV”'), text)
    await page.screenshot({ path: `${test.info().outputDir}/cl-1-view.png` })
    await closeDialog()
  })

  // Before the live update of the other change arrives; otherwise the view would already show it.
  await test.step('ticking an item someone else removed reloads the task', async () => {
    await openView(1)
    await put({ checklist: [{ text: 'Changed elsewhere' }] }, { silent: true })
    await page.getByTestId('task-checklist').getByText('Photo').click()
    await page.locator('.p-toast').getByText('The checklist was changed elsewhere').waitFor()
    await page.getByTestId('task-checklist').getByText('Changed elsewhere').waitFor()
    ensure((await task1()).checklist.length === 1, 'changed anyway')
    await closeDialog()
  })

  await test.step('"- [ ]" in the description is plain text', async () => {
    await put({ description: '- [ ] Not a box\n- [x] Neither', checklist: null })
    await page.reload()
    await openView(1)
    const description = page.getByTestId('task-description')
    await description.getByText('[ ] Not a box').waitFor()
    ensure((await description.locator('input').count()) === 0, 'checkbox rendered')
    ensure((await progress().innerText()).trim() === '0/1', 'description boxes counted')
    await closeDialog()
  })

  await test.step('shared board: progress yes, ticking no', async () => {
    const guest = await browser.newPage({ viewport: { width: 1400, height: 900 } })
    await guest.goto(`${BASE}/share/test-token`)
    const card = guest.locator('[data-task-id="1"]')
    await card.waitFor()
    ensure((await card.getByTestId('checklist-progress').innerText()).trim() === '0/1', 'shared progress')
    await card.locator('h3').click()
    const box = guest.getByTestId('task-checklist').getByRole('checkbox').first()
    await box.waitFor()
    ensure(await box.isDisabled(), 'checkbox enabled on shared board')
    const requests = async () => (await state()).log.filter((e) => e.url.includes('/checklist/')).length
    const before = await requests()
    await box.click({ force: true })
    await guest.waitForTimeout(300)
    ensure((await requests()) === before, 'request sent')
    ensure(!(await box.isChecked()), 'ticked anyway')
    await guest.close()
  })

  if (errors.length) throw new Error(`page errors: ${errors.join('; ')}`)
  for (const context of browser.contexts()) await context.close()
})
