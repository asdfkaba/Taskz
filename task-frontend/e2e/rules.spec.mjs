// Automations in the board settings, and what they do when a task enters the column.
import { test } from '@playwright/test'
import { BASE, resetMock } from './support.mjs'

test('Board automations', async ({ browser }) => {
  await resetMock()
  const page = await browser.newPage({ viewport: { width: 1400, height: 900 } })
  const errors = []
  page.on('pageerror', (e) => errors.push(e.message))
  const ensure = (cond, msg) => { if (!cond) throw new Error(msg) }
  const state = async () => (await fetch(`${BASE}/api/__state`)).json()
  const rules = () => page.getByTestId('board-rules')
  const card = (id) => page.locator(`[data-task-id="${id}"]`)
  async function choose(label, option) {
    await rules().getByRole('combobox', { name: label, exact: true }).click()
    // last: the list of the select before may still be fading out.
    await page.getByRole('option', { name: option, exact: true }).last().click()
  }

  await page.goto(`${BASE}/boards/my-board/settings`)
  await rules().getByText('No automations yet.').waitFor()

  await test.step('a checklist and a note reminder rule are added', async () => {
    await choose('Column', 'In progress')
    await rules().getByRole('textbox', { name: 'Checklist items' }).fill('Prepare\n\n  Thank-you mail ')
    await rules().getByRole('button', { name: 'Add automation' }).click()
    await rules().getByText('Add checklist items: Prepare, Thank-you mail').waitFor()

    await choose('Action', 'Add a note with a reminder')
    ensure(await rules().getByRole('button', { name: 'Add automation' }).isDisabled(), 'added without a note')
    await rules().getByRole('textbox', { name: 'Note', exact: true }).fill('  Follow up ')
    await rules().getByRole('textbox', { name: 'Days from now' }).fill('14')
    await rules().getByRole('button', { name: 'Add automation' }).click()
    await rules().getByText('Add the note “Follow up” with a reminder in 14 days').waitFor()
    ensure((await rules().getByText('When a task enters In progress').count()) === 1, 'not grouped by column')

    const saved = (await state()).rules
    ensure(saved.length === 2 && saved.every((r) => r.columnId === 11), JSON.stringify(saved))
    ensure(JSON.stringify(saved[0].items) === '["Prepare","Thank-you mail"]', JSON.stringify(saved[0]))
    ensure(saved[1].action === 'ADD_NOTE_REMINDER' && saved[1].text === 'Follow up' && saved[1].days === 14, JSON.stringify(saved[1]))
  })

  await test.step('a note rule is edited; switched off it does nothing; it can be deleted', async () => {
    await rules().getByRole('button', { name: /^Edit automation: Add the note/ }).click()
    await rules().getByRole('textbox', { name: 'Edit note' }).fill('Call back')
    await rules().getByRole('textbox', { name: 'Edit days from now' }).fill('3')
    await rules().getByTestId('rule').getByRole('checkbox', { name: 'Remove the note when the task leaves the column' }).click()
    await rules().getByRole('button', { name: 'Save' }).click()
    await rules().getByText('Add the note “Call back” with a reminder in 3 days, removed when the task leaves').waitFor()
    ensure((await state()).rules[1].removeOnLeave === true, 'option not saved')

    // On: entering the column adds Alice's note with a reminder in 3 days; leaving removes it again.
    const move = (columnId) => fetch(`${BASE}/api/tasks/2/move`, { method: 'PATCH', body: JSON.stringify({ columnId, position: 0 }) })
    await move(11)
    const note = (await state()).notes.find((n) => n.taskId === 2)
    ensure(note?.text === 'Call back', JSON.stringify((await state()).notes))
    const days = (new Date(note.remindAt) - Date.now()) / 86400000
    ensure(days > 2.9 && days <= 3, `reminder in ${days} days`)
    await move(10)
    ensure(!(await state()).notes.some((n) => n.taskId === 2), 'note not removed on leaving')

    await rules().getByRole('checkbox').nth(1).click()
    await page.waitForFunction(async (base) => (await (await fetch(`${base}/api/__state`)).json()).rules[1].enabled === false, BASE)
    await rules().getByRole('button', { name: /^Delete automation: Add the note/ }).click()
    await rules().getByTestId('rule').nth(1).waitFor({ state: 'detached' })
    ensure((await state()).rules.length === 1, 'not deleted')
  })

  await test.step('a rule is edited in place; cancel keeps it as it was', async () => {
    await rules().getByRole('button', { name: /^Edit automation: Add checklist/ }).click()
    await rules().getByRole('textbox', { name: 'Edit checklist items' }).fill('Nothing')
    await rules().getByRole('button', { name: 'Cancel' }).click()
    await rules().getByText('Add checklist items: Prepare, Thank-you mail').waitFor()

    await rules().getByRole('button', { name: /^Edit automation: Add checklist/ }).click()
    const field = rules().getByRole('textbox', { name: 'Edit checklist items' })
    ensure((await field.inputValue()) === 'Prepare\nThank-you mail', await field.inputValue())
    await field.fill('')
    ensure(await rules().getByRole('button', { name: 'Save' }).isDisabled(), 'saving an empty list allowed')
    await field.fill('Research\nPrepare\nThank-you mail')
    await rules().getByRole('button', { name: 'Save' }).click()
    await rules().getByText('Add checklist items: Research, Prepare, Thank-you mail').waitFor()
    ensure(JSON.stringify((await state()).rules[0].items) === '["Research","Prepare","Thank-you mail"]', 'not saved')
  })

  await test.step('moving a task into the column adds the checklist', async () => {
    await page.goto(`${BASE}/boards/my-board`)
    await card(1).hover()
    await card(1).getByRole('button', { name: 'Move to In progress' }).click()
    await card(1).getByTestId('checklist-progress').getByText('0/3').waitFor()
    const task = (await state()).tasks.find((t) => t.id === 1)
    ensure(task.checklist.map((i) => i.text).join() === 'Research,Prepare,Thank-you mail', JSON.stringify(task.checklist))
    ensure(task.description === 'first', 'description changed')
  })

  await test.step('a replacing rule swaps the checklist when the task enters Done', async () => {
    await page.goto(`${BASE}/boards/my-board/settings`)
    await choose('Column', 'Done')
    await choose('Action', 'Replace the checklist')
    await rules().getByRole('textbox', { name: 'Checklist items' }).fill('Thank-you mail\nFollow up')
    await rules().getByRole('button', { name: 'Add automation' }).click()
    await rules().getByText('Replace the checklist with: Thank-you mail, Follow up').waitFor()
    ensure((await state()).rules.at(-1).action === 'REPLACE_CHECKLIST', 'wrong action saved')

    await page.goto(`${BASE}/boards/my-board`)
    await card(1).hover()
    await card(1).getByRole('button', { name: 'Move to Done' }).click()
    await card(1).getByTestId('checklist-progress').getByText('0/2').waitFor()
    const task = (await state()).tasks.find((t) => t.id === 1)
    ensure(task.checklist.map((i) => i.text).join() === 'Thank-you mail,Follow up', JSON.stringify(task.checklist))
  })

  await test.step('the history names the automation', async () => {
    await card(1).locator('h3').click()
    await page.getByTestId('task-view').waitFor()
    const history = page.getByTestId('task-history')
    await history.getByRole('button', { name: 'History' }).click()
    const latest = history.locator('li').first()
    await latest.getByText('Removed checklist items').waitFor()
    ensure((await latest.getByTestId('event-user').innerText()).includes('Automation'), await latest.innerText())
  })

  await test.step('a rule for tasks that stay in a column takes days and can be edited', async () => {
    await page.goto(`${BASE}/boards/my-board/settings`)
    await rules().getByTestId('rule').first().waitFor()
    const before = (await state()).rules.length
    await choose('Trigger', 'stays in')
    await choose('Column', 'Done')
    // Only what goes with the trigger is offered.
    await rules().getByRole('combobox', { name: 'Action', exact: true }).click()
    ensure((await page.getByRole('option', { name: 'Add checklist items' }).count()) === 0, 'checklist action offered for a timed rule')
    await page.getByRole('option', { name: 'Archive it', exact: true }).click()
    await rules().getByRole('textbox', { name: 'Days in the column' }).fill('0')
    ensure(await rules().getByRole('button', { name: 'Add automation' }).isDisabled(), 'added with 0 days')
    await rules().getByRole('textbox', { name: 'Days in the column' }).fill('30')
    await rules().getByRole('button', { name: 'Add automation' }).click()
    await rules().getByText('After 30 days: archive it').waitFor()
    ensure((await rules().getByText('When a task stays in Done').count()) === 1, 'no heading for the trigger')
    const added = (await state()).rules.at(-1)
    ensure((await state()).rules.length === before + 1 && added.trigger === 'IN_COLUMN_FOR' && added.action === 'ARCHIVE' && added.days === 30 && added.columnId === 12, JSON.stringify(added))

    await rules().getByRole('button', { name: 'Edit automation: After 30 days: archive it' }).click()
    await rules().getByRole('textbox', { name: 'Edit days in the column' }).fill('1')
    await rules().getByRole('button', { name: 'Save' }).click()
    await rules().getByText('After 1 day: archive it').waitFor()
  })

  await test.step('completing the checklist moves the task, and the history names the automation', async () => {
    await choose('Trigger', 'completes its checklist in')
    await choose('Column', 'Done')
    // Archiving doesn't go with this trigger, so the action switched to the first that does.
    ensure((await rules().getByRole('combobox', { name: 'Action', exact: true }).innerText()).includes('Move it'), 'action not switched')
    ensure(await rules().getByRole('button', { name: 'Add automation' }).isDisabled(), 'added without a target')
    await choose('Target column', 'To do')
    await rules().getByRole('button', { name: 'Add automation' }).click()
    await rules().getByText('Move it to To do').waitFor()
    ensure((await rules().getByText('When a task completes its checklist in Done').count()) === 1, 'no heading for the trigger')
    await page.screenshot({ path: `${test.info().outputDir}/ru-triggers.png` })

    // Task 1 is in Done with two open items.
    await page.goto(`${BASE}/boards/my-board`)
    await card(1).locator('h3').click()
    await page.getByTestId('task-view').waitFor()
    await page.getByTestId('task-checklist').getByText('Thank-you mail').click()
    await page.waitForFunction(async () => (await (await fetch('/api/__state')).json()).tasks.find((t) => t.id === 1).checklist[0].checked)
    ensure((await state()).tasks.find((t) => t.id === 1).columnId === 12, 'moved before the checklist was complete')
    await page.getByTestId('task-checklist').getByText('Follow up').click()
    await page.locator('[data-column-id="10"] [data-task-id="1"]').waitFor()
    const task = (await state()).tasks.find((t) => t.id === 1)
    ensure(task.columnId === 10 && task.position === 1, JSON.stringify([task.columnId, task.position]))
    const history = page.getByTestId('task-history')
    await history.getByRole('button', { name: 'History' }).click()
    const moved = history.locator('li').filter({ hasText: 'Moved from Done to To do' })
    await moved.waitFor()
    ensure((await moved.getByTestId('event-user').innerText()).includes('Automation'), await moved.innerText())
  })

  ensure(errors.length === 0, errors.join('\n'))
})
