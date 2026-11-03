// Checkboxes in descriptions: progress on the card, ticking in the task view, history, shared boards.
import { test } from '@playwright/test'
import { BASE, resetMock } from './support.mjs'

const DESCRIPTION = [
  'Steps:',
  '- [ ] Cover letter',
  '- [ ] CV',
  '```',
  '- [ ] CV',
  '```',
  '- [x] Research',
].join('\n')

test('Checklists in descriptions', async ({ browser }) => {
  await resetMock()
  // Like the app: PUT replaces the editable fields. silent: open pages don't hear of it (yet).
  const setDescription = (description, { silent = false } = {}) =>
    fetch(`${BASE}/api/tasks/1`, {
      method: 'PUT',
      headers: silent ? { 'x-mock-silent': '1' } : {},
      body: JSON.stringify({ title: 'Task A', description, due_date: null }),
    })
  await setDescription(DESCRIPTION)

  const page = await browser.newPage({ viewport: { width: 1400, height: 900 } })
  const errors = []
  page.on('pageerror', (e) => errors.push(e.message))
  const ensure = (cond, msg) => { if (!cond) throw new Error(msg) }
  const state = async () => (await fetch(`${BASE}/api/__state`)).json()
  const task1 = async () => (await state()).tasks.find((t) => t.id === 1)
  const progress = () => page.locator('[data-task-id="1"]').getByTestId('checklist-progress')
  const boxes = () => page.getByTestId('task-description').locator('input[type="checkbox"]')
  const checked = async () => boxes().evaluateAll((els) => els.map((e) => e.checked))
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

  await test.step('the card shows the progress; the code block does not count', async () => {
    ensure((await progress().innerText()).trim() === '1/3', await progress().innerText())
    ensure((await progress().getAttribute('aria-label')) === '1 of 3 checklist items done', 'label')
    ensure((await page.locator('[data-task-id="2"]').getByTestId('checklist-progress').count()) === 0, 'progress without boxes')
  })

  await test.step('ticking in the view changes only that line and is saved', async () => {
    await openView(1)
    ensure(JSON.stringify(await checked()) === '[false,false,true]', JSON.stringify(await checked()))
    await boxes().nth(1).click()
    await page.waitForFunction(() => document.querySelectorAll('[data-testid="task-description"] input:checked').length === 2)
    const expected = DESCRIPTION.replace('- [ ] CV\n```', '- [x] CV\n```')
    ensure((await task1()).description === expected, JSON.stringify((await task1()).description))
    ensure((await progress().innerText()).trim() === '2/3', 'card not updated')
    ensure((await page.getByTestId('task-view').count()) === 1, 'left the view')
  })

  await test.step('the history says what was ticked, not "changed the description"', async () => {
    await page.getByTestId('task-history').getByRole('button', { name: 'History' }).click()
    const first = page.getByTestId('task-history').locator('li[data-event-type]').first()
    await first.waitFor()
    ensure((await first.getAttribute('data-event-type')) === 'checked', await first.getAttribute('data-event-type'))
    ensure((await first.innerText()).includes('Checked “CV”'), await first.innerText())
  })

  await test.step('unticking with the keyboard; history updates while open', async () => {
    await boxes().nth(2).focus()
    await page.keyboard.press('Space')
    await page.waitForFunction(() => document.querySelectorAll('[data-testid="task-description"] input:checked').length === 1)
    ensure((await task1()).description.endsWith('- [ ] Research'), 'not saved')
    const first = page.getByTestId('task-history').locator('li[data-event-type]').first()
    await page.waitForFunction(() => document.querySelector('[data-testid="task-history"] li[data-event-type]')?.dataset.eventType === 'unchecked')
    ensure((await first.innerText()).includes('Unchecked “Research”'), await first.innerText())
  })

  await test.step('all done turns the progress green', async () => {
    await boxes().nth(0).click()
    await page.waitForFunction(() => document.querySelectorAll('[data-testid="task-description"] input:checked').length === 2)
    await boxes().nth(2).click()
    await page.waitForFunction(() => document.querySelectorAll('[data-testid="task-description"] input:checked').length === 3)
    await closeDialog()
    ensure((await progress().innerText()).trim() === '3/3', await progress().innerText())
    ensure((await progress().getAttribute('class')).includes('text-green-700'), 'not green')
    await page.screenshot({ path: `${test.info().outputDir}/cl-1-board.png` })
  })

  await test.step('nested items are indented under their parent', async () => {
    await setDescription('- [ ] Parent\n  - [ ] Child\n    - [ ] Grandchild\n  - Plain bullet\n- [ ] Next')
    await page.reload()
    await openView(1)
    // Measure after the dialog's opening animation (it scales the content).
    await page.locator('.p-dialog').evaluate((el) => Promise.all(el.getAnimations({ subtree: true }).map((a) => a.finished)))
    const x = (i) => boxes().nth(i).evaluate((e) => e.getBoundingClientRect().left)
    const [parent, child, grandchild, next] = [await x(0), await x(1), await x(2), await x(3)]
    ensure(child - parent >= 16 && grandchild - child >= 16, `not indented: ${parent} ${child} ${grandchild}`)
    ensure(Math.abs(next - parent) < 1, `siblings differ: ${parent} ${next}`)
    // The child's box lines up with the parent's text.
    const parentText = await page.getByTestId('task-description').locator('li').first().evaluate((li) => {
      const range = document.createRange()
      range.selectNodeContents(li.childNodes[1])
      return range.getBoundingClientRect().left
    })
    ensure(Math.abs(child - parentText) < 3, `child ${child} vs parent text ${parentText}`)
    await page.screenshot({ path: `${test.info().outputDir}/cl-2-nested.png` })
    await closeDialog()
  })

  await test.step('nested: the last sub-item completes its parents, a parent takes its sub-items along', async () => {
    // Parent > Child > Grandchild, a plain bullet under Parent, then Next
    await openView(1)
    const historyFirst = () => page.getByTestId('task-history').locator('li[data-event-type]').first()
    await page.getByTestId('task-history').getByRole('button', { name: 'History' }).click()
    await boxes().nth(2).click()
    await page.waitForFunction(() => document.querySelectorAll('[data-testid="task-description"] input:checked').length === 3)
    ensure(JSON.stringify(await checked()) === '[true,true,true,false]', JSON.stringify(await checked()))
    ensure((await task1()).description === '- [x] Parent\n  - [x] Child\n    - [x] Grandchild\n  - Plain bullet\n- [ ] Next', 'saved')
    await page.getByTestId('task-history').getByText('Checked “Grandchild”, completing “Parent”, “Child”').waitFor()

    await boxes().nth(0).click()
    await page.waitForFunction(() => document.querySelectorAll('[data-testid="task-description"] input:checked').length === 0)
    await page.getByTestId('task-history').getByText('Unchecked “Parent” and 2 sub-items').waitFor()
    ensure((await historyFirst().getAttribute('data-event-type')) === 'unchecked', 'type')

    await boxes().nth(0).click()
    await page.waitForFunction(() => document.querySelectorAll('[data-testid="task-description"] input:checked').length === 3)
    await boxes().nth(1).click()
    await page.waitForFunction(() => document.querySelectorAll('[data-testid="task-description"] input:checked').length === 0)
    await page.getByTestId('task-history').getByText('Unchecked “Child” and 1 sub-item, reopening “Parent”').waitFor()
    await closeDialog()
  })

  // Before the live update of the other change arrives; otherwise the view would already show it.
  await test.step('a description changed elsewhere is not overwritten', async () => {
    await openView(1)
    await setDescription('- [ ] Changed elsewhere\n- [ ] Second', { silent: true })
    await boxes().nth(0).click()
    await page.locator('.p-toast').getByText('The task was changed elsewhere').waitFor()
    // Reloaded: the view shows the other version, unticked.
    await page.getByTestId('task-description').getByText('Changed elsewhere').waitFor()
    ensure((await task1()).description === '- [ ] Changed elsewhere\n- [ ] Second', 'overwritten')
    ensure(JSON.stringify(await checked()) === '[false,false]', JSON.stringify(await checked()))
    await closeDialog()
  })

  await test.step('shared board: progress yes, ticking no', async () => {
    const guest = await browser.newPage({ viewport: { width: 1400, height: 900 } })
    await guest.goto(`${BASE}/share/test-token`)
    const card = guest.locator('[data-task-id="1"]')
    await card.waitFor()
    ensure((await card.getByTestId('checklist-progress').innerText()).trim() === '0/2', 'shared progress')
    await card.locator('h3').click()
    const box = guest.getByTestId('task-view').locator('input[type="checkbox"]').first()
    await box.waitFor()
    ensure(await box.isDisabled(), 'checkbox enabled on shared board')
    const checkboxRequests = async () => (await state()).log.filter((e) => e.url.includes('/checkbox')).length
    const before = await checkboxRequests()
    await box.click({ force: true })
    await guest.waitForTimeout(300)
    ensure((await checkboxRequests()) === before, 'request sent')
    ensure(!(await box.isChecked()), 'ticked anyway')
    await guest.close()
  })

  if (errors.length) throw new Error(`page errors: ${errors.join('; ')}`)
  for (const context of browser.contexts()) await context.close()
})
