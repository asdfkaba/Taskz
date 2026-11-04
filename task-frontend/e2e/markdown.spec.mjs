// Markdown descriptions: rendering, sanitizing, link clicks, preview.
import { test } from '@playwright/test'
import { BASE, dialogOpened, resetMock } from './support.mjs'

test('Markdown descriptions', async ({ browser }) => {
  const base = BASE
  const outDir = test.info().outputDir
  await resetMock()

  const context = await browser.newContext({ viewport: { width: 1400, height: 900 } })
  const page = await context.newPage()
  const errors = []
  const dialogs = []
  page.on('pageerror', (e) => errors.push(e.message))
  page.on('dialog', (d) => { dialogs.push(d.message()); d.dismiss() })
  // External links must not leave the sandbox.
  await context.route(/example\.com/, (r) => r.fulfill({ body: 'external' }))

  async function check(...args) {
    const fn = args.pop()
    await test.step(args.pop(), fn)
  }
  function expect(cond, msg) {
    if (!cond) throw new Error(msg)
  }
  const card = (id) => page.locator(`[data-task-id="${id}"]`)
  const desc = (id) => card(id).locator('.markdown')
  // A click on a card opens the view; the editor is one more click away.
  async function openEditor(id) {
    await card(id).click()
    await dialogOpened(page)
    await page.getByRole('button', { name: 'Edit', exact: true }).click()
    await page.locator('#task-description').waitFor()
  }

  const markdown = [
    '# Big heading',
    'Some **bold** and *italic* and `code`.',
    'Line one',
    'line two',
    '',
    '- [ ] open item',
    '- [x] done item',
    '',
    '[a link](https://example.com/page)',
    '[bad](javascript:alert(1))',
    '<img src="x" onerror="alert(2)">',
    '<script>alert(3)</script>',
    '<a href="https://example.com" onclick="alert(4)" style="color:red">raw</a>',
    '<input type="text" value="raw input">',
  ].join('\n')

  await page.goto(`${base}/boards`)
  await page.getByText('Task A', { exact: true }).waitFor()

  await check('plain old description still shows', async () => {
    expect((await desc(1).innerText()).trim() === 'first', await desc(1).innerText())
  })

  await check('preview renders while typing', async () => {
    await openEditor(1)
    await page.locator('#task-description').fill(markdown)
    await page.getByRole('button', { name: 'preview' }).click()
    const preview = page.getByTestId('description-preview')
    await preview.locator('strong', { hasText: 'bold' }).waitFor()
    expect(!(await page.locator('#task-description').isVisible()), 'textarea still visible in preview')
    await page.screenshot({ path: `${outDir}/md-1-preview.png` })
    await page.getByRole('button', { name: 'write' }).click()
    expect(await page.locator('#task-description').isVisible(), 'textarea not back')
    await page.getByRole('button', { name: 'Save changes' }).click()
    // Opened from the view, saving returns to the view.
    await page.getByTestId('task-view').waitFor()
    await page.keyboard.press('Escape')
    await page.waitForFunction(() => !document.querySelector('.p-dialog'))
  })

  await check('card renders markdown', async () => {
    const d = desc(1)
    expect((await d.locator('strong').innerText()) === 'bold', 'no bold')
    expect((await d.locator('em').innerText()) === 'italic', 'no italic')
    expect((await d.locator('code').innerText()) === 'code', 'no code')
    expect((await d.locator('h1').count()) === 1, 'no heading')
    const size = await d.locator('h1').evaluate((el) => getComputedStyle(el).fontSize)
    expect(size === '14px', `heading size ${size}`)
    expect((await d.innerHTML()).includes('Line one<br>'), 'single newline is not a line break')
  })

  // Checklists have their own list (task.checklist); in descriptions the marks are text.
  await check('task list marks are plain text', async () => {
    expect((await desc(1).locator('input').count()) === 0, 'checkbox rendered')
    const items = await desc(1).locator('li').allInnerTexts()
    expect(items.includes('[ ] open item') && items.includes('[x] done item'), JSON.stringify(items))
  })

  await check('dangerous html is removed', async () => {
    const html = await desc(1).innerHTML()
    for (const bad of ['<img', '<script', 'onerror', 'onclick', 'javascript:', 'style=', 'raw input']) {
      expect(!html.includes(bad), `found ${bad}`)
    }
    expect(dialogs.length === 0, `alert fired: ${dialogs}`)
  })

  await check('links open in a new tab', async () => {
    const link = desc(1).locator('a', { hasText: 'a link' })
    expect((await link.getAttribute('target')) === '_blank', 'no target')
    expect((await link.getAttribute('rel')).includes('noopener'), 'no rel')
  })

  await check('collapsed card shows only three lines', async () => {
    const h = await desc(1).evaluate((el) => el.getBoundingClientRect().height)
    expect(h < 70, `description height ${h}`)
    await card(1).screenshot({ path: `${outDir}/md-2-card.png` })
  })

  await check('clicking a link opens it, not the editor', async () => {
    // Move a link into the visible lines first.
    await openEditor(1)
    await page.locator('#task-description').fill('see [docs](https://example.com/docs)')
    await page.getByRole('button', { name: 'Save changes' }).click()
    // Opened from the view, saving returns to the view.
    await page.getByTestId('task-view').waitFor()
    await page.keyboard.press('Escape')
    await page.waitForFunction(() => !document.querySelector('.p-dialog'))
    const [popup] = await Promise.all([context.waitForEvent('page'), desc(1).locator('a').click()])
    expect(popup.url().startsWith('https://example.com/docs'), popup.url())
    await popup.close()
    await page.waitForTimeout(300)
    expect((await page.locator('.p-dialog').count()) === 0, 'editor opened')
  })

  await check('clicking elsewhere on the card opens the task', async () => {
    await card(1).getByText('Task A', { exact: true }).click()
    await page.getByTestId('task-view').waitFor()
    await page.getByRole('button', { name: 'Edit', exact: true }).click()
    expect((await page.locator('#task-description').inputValue()) === 'see [docs](https://example.com/docs)', 'raw text lost')
    await page.keyboard.press('Escape')
    await page.waitForFunction(() => !document.querySelector('.p-dialog'))
  })

  await check('enter on the card opens the task, enter on an arrow does not', async () => {
    await card(1).focus()
    await page.keyboard.press('Enter')
    await page.getByTestId('task-view').waitFor()
    await page.keyboard.press('Escape')
    await page.waitForFunction(() => !document.querySelector('.p-dialog'))
    await card(3).hover()
    await card(3).getByRole('button', { name: 'Move to Done' }).focus()
    await page.keyboard.press('Enter')
    await page.waitForTimeout(400)
    expect((await page.locator('.p-dialog').count()) === 0, 'editor opened from arrow')
  })

  await check('too long description is capped in the textarea', async () => {
    await openEditor(2)
    await page.locator('#task-description').fill('x'.repeat(10050))
    expect((await page.locator('#task-description').inputValue()).length === 10000, 'not capped')
    await page.keyboard.press('Escape')
    await page.waitForFunction(() => !document.querySelector('.p-dialog'))
  })

  await check('shared board: card is clamped, dialog shows the full description', async () => {
    await fetch(`${base}/api/tasks/1`, {
      method: 'PUT',
      body: JSON.stringify({ title: 'Task A', description: markdown }),
    })
    const guest = await context.newPage()
    guest.on('dialog', (d) => { dialogs.push(d.message()); d.dismiss() })
    await guest.goto(`${base}/share/test-token`)
    const cardDesc = guest.locator('[data-task-id="1"] .markdown')
    await cardDesc.locator('strong').waitFor()
    expect((await cardDesc.getAttribute('class')).includes('line-clamp'), 'not clamped on shared card')
    await guest.locator('[data-task-id="1"] h3').click()
    const d = guest.getByTestId('task-view').locator('.markdown')
    await d.locator('strong').waitFor()
    expect((await d.locator('a', { hasText: 'a link' }).count()) === 1, 'last lines missing in dialog')
    expect(!(await d.innerHTML()).includes('<img'), 'img on shared board')
    await guest.waitForTimeout(300)
    expect(dialogs.length === 0, `alert fired: ${dialogs}`)
    await guest.screenshot({ path: `${outDir}/md-3-shared.png` })
    await guest.close()
  })

  if (errors.length) throw new Error(`page errors: ${errors.join('; ')}`)
  for (const context of browser.contexts()) await context.close()
})
