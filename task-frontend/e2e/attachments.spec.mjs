// Attachments on a task: upload by picker and drag & drop, refused types, download, delete,
// history, the paperclip on the card, not on shared boards.
import { test } from '@playwright/test'
import { BASE, resetMock } from './support.mjs'

// A 1×1 PNG.
const PNG = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==',
  'base64',
)
const PDF = Buffer.from('%PDF-1.4\n%%EOF\n')

test('Task attachments', async ({ browser }) => {
  await resetMock()
  const page = await browser.newPage({ viewport: { width: 1400, height: 900 } })
  const errors = []
  page.on('pageerror', (e) => errors.push(e.message))
  const ensure = (cond, msg) => { if (!cond) throw new Error(msg) }
  const out = (name) => `${test.info().outputDir}/${name}`
  const section = () => page.getByTestId('task-attachments')
  const names = () => section().getByTestId('attachment').locator('a.truncate').allInnerTexts()
  const count = () => page.locator('[data-task-id="1"]').getByTestId('attachment-count')
  const picker = () => section().locator('input[type="file"]')
  const closeDialog = async () => {
    await page.keyboard.press('Escape')
    await page.waitForFunction(() => !document.querySelector('.p-dialog'))
  }
  const openTask = async () => {
    await page.locator('[data-task-id="1"] h3').click()
    await page.getByTestId('task-view').waitFor()
    await section().waitFor()
  }

  await page.goto(`${BASE}/boards`)
  await page.locator('[data-task-id="1"]').waitFor()

  await test.step('a task starts without attachments and the card has no paperclip', async () => {
    ensure((await count().count()) === 0, 'paperclip without attachments')
    await openTask()
    await section().getByText('Drop files here').waitFor()
  })

  await test.step('files from the picker are listed newest first, images with a preview', async () => {
    await picker().setInputFiles([
      { name: 'Lebenslauf.pdf', mimeType: 'application/pdf', buffer: PDF },
      { name: 'screenshot.png', mimeType: 'image/png', buffer: PNG },
    ])
    await section().getByTestId('attachment').nth(1).waitFor()
    await page.waitForFunction(() => !document.querySelector('[data-testid="uploading"]'))
    const listed = await names()
    ensure(JSON.stringify(listed) === JSON.stringify(['screenshot.png', 'Lebenslauf.pdf']), listed.join())
    const preview = section().getByTestId('attachment').first().locator('img')
    await preview.waitFor()
    await page.waitForFunction((img) => img.complete && img.naturalWidth === 1, await preview.elementHandle())
    ensure((await section().getByTestId('attachment').nth(1).locator('img').count()) === 0, 'preview for a PDF')
    await page.screenshot({ path: out('att-1-list.png') })
  })

  await test.step('dropping a file attaches it', async () => {
    const dataTransfer = await page.evaluateHandle(() => {
      const dt = new DataTransfer()
      dt.items.add(new File(['# Interview\n- [ ] Questions'], 'prep.md', { type: 'text/markdown' }))
      return dt
    })
    const isDragging = () => section().evaluate((el) => el.className.includes('outline-dashed'))
    await section().dispatchEvent('dragover', { dataTransfer })
    ensure(await isDragging(), 'no drop highlight')
    // Leaving the window over a child (relatedTarget null) ends the highlight.
    await section().locator('h3').dispatchEvent('dragleave', { dataTransfer })
    ensure(!(await isDragging()), 'highlight stays after leaving')
    await section().dispatchEvent('dragover', { dataTransfer })
    await page.screenshot({ path: out('att-2-dragging.png') })
    await section().dispatchEvent('drop', { dataTransfer })
    await page.waitForFunction(() => document.querySelectorAll('[data-testid="attachment"]').length === 3)
    ensure((await names())[0] === 'prep.md', 'dropped file missing')
  })

  await test.step('files of other types, or not what their name says, are refused with the reason', async () => {
    await picker().setInputFiles({ name: 'setup.exe', mimeType: 'application/octet-stream', buffer: Buffer.from('MZ\x90\x00') })
    await page.locator('.p-toast-message').getByText('Could not attach setup.exe').waitFor()
    await page.locator('.p-toast-message').getByText(/Only PNG, JPEG/).first().waitFor()
    await picker().setInputFiles({ name: 'photo.png', mimeType: 'image/png', buffer: Buffer.from('not an image') })
    await page.locator('.p-toast-message').getByText('Could not attach photo.png').waitFor()
    await page.waitForFunction(() => !document.querySelector('[data-testid="uploading"]'))
    ensure((await names()).length === 3, 'refused file listed')
    await page.screenshot({ path: out('att-3-refused.png') })
  })

  await test.step('the link serves the file', async () => {
    const href = await section().getByRole('link', { name: 'Lebenslauf.pdf' }).getAttribute('href')
    const response = await page.request.get(`${BASE}${href}`)
    ensure(response.ok(), `HTTP ${response.status()}`)
    ensure((await response.body()).equals(PDF), 'wrong content')
  })

  await test.step('the history lists the attachments', async () => {
    await page.getByTestId('task-history').getByRole('button', { name: /History/ }).click()
    await page.getByTestId('task-history').getByText('Attached “screenshot.png”').waitFor()
    await page.getByTestId('task-history').getByText('Attached “prep.md”').waitFor()
  })

  await test.step('deleting asks first', async () => {
    const row = section().getByTestId('attachment').filter({ hasText: 'screenshot.png' })
    await row.hover()
    await row.getByRole('button', { name: 'Delete screenshot.png' }).click()
    await page.getByRole('button', { name: 'Delete attachment' }).click()
    await page.waitForFunction(() => document.querySelectorAll('[data-testid="attachment"]').length === 2)
    ensure(!(await names()).includes('screenshot.png'), 'still listed')
    await page.getByTestId('task-history').getByText('Removed attachment “screenshot.png”').waitFor()
  })

  await test.step('the card shows how many files are attached, also after a reload', async () => {
    await closeDialog()
    ensure((await count().innerText()).trim().startsWith('2'), `count: ${await count().innerText()}`)
    await page.reload()
    await count().waitFor()
    ensure((await count().innerText()).trim().startsWith('2'), 'count lost on reload')
    await page.screenshot({ path: out('att-4-card.png') })
  })

  await test.step('dark mode', async () => {
    await page.evaluate(() => document.documentElement.classList.add('dark'))
    await openTask()
    await page.waitForTimeout(300)
    await page.screenshot({ path: out('att-5-dark.png') })
    await page.evaluate(() => document.documentElement.classList.remove('dark'))
    await closeDialog()
  })

  await test.step('shared boards show neither the count nor the files', async () => {
    const share = await (await fetch(`${BASE}/api/boards/1/share`, { method: 'POST' })).json()
    await page.goto(`${BASE}/share/${share.path}`)
    await page.locator('[data-task-id="1"]').waitFor()
    ensure((await count().count()) === 0, 'paperclip on a shared board')
    await page.locator('[data-task-id="1"] h3').click()
    await page.getByTestId('task-view').waitFor()
    ensure((await page.getByTestId('task-attachments').count()) === 0, 'attachments on a shared board')
  })

  if (errors.length) throw new Error(`page errors: ${errors.join('; ')}`)
  await page.close()
})
