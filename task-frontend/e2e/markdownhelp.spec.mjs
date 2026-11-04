// Formatting help under the description: every example renders as the help says.
import { test } from '@playwright/test'
import { BASE, resetMock } from './support.mjs'

test('Markdown formatting help', async ({ browser }) => {
  await resetMock()
  const page = await browser.newPage({ viewport: { width: 1400, height: 900 } })
  const errors = []
  page.on('pageerror', (e) => errors.push(e.message))
  const ensure = (cond, msg) => { if (!cond) throw new Error(msg) }
  const help = () => page.getByTestId('markdown-help')
  // The rendered side of the example whose source starts with `start`
  const result = (start) => help().getByTestId('markdown-example')
    .filter({ has: page.locator('pre', { hasText: start }) }).first().getByLabel('You get')
  const count = (start, selector) => result(start).locator(selector).count()
  const closed = () => page.waitForFunction(() => !document.querySelector('.p-dialog'))

  await page.goto(`${BASE}/boards`)
  await page.locator('[data-task-id="1"]').waitFor()

  await test.step('collapsed at first, opens below the description', async () => {
    await page.getByRole('button', { name: 'New task' }).click()
    const toggle = page.getByRole('button', { name: 'Formatting help (Markdown)' })
    await toggle.waitFor()
    ensure((await help().count()) === 0, 'open at start')
    ensure((await toggle.getAttribute('aria-expanded')) === 'false', 'aria')
    await toggle.click()
    await help().waitFor()
    ensure((await toggle.getAttribute('aria-expanded')) === 'true', 'aria open')
  })

  await test.step('every example renders as described', async () => {
    ensure(await count('**bold**', 'strong') === 1 && await count('**bold**', 'em') === 1, 'bold/italic')
    ensure(await count('~~struck~~', 'del') === 1 && await count('~~struck~~', 'code') === 1, 'struck/code')
    ensure(await count('## Heading', 'h2') === 1, 'heading')
    ensure((await result('[Job ad]').locator('a').getAttribute('target')) === '_blank', 'link target')
    ensure((await result('https://example.com').locator('a').getAttribute('href')) === 'https://example.com', 'autolink')
    ensure(await count('Line one', 'br') === 1 && await count('Line one', 'p') === 2, 'line breaks')
    ensure(await count('- Item', 'ul ul') === 1, 'nested list')
    ensure(await count('1. First', 'ol > li > ul') === 1, 'list under number')
    ensure((await help().locator('input').count()) === 0, 'checkboxes in the help')
    ensure(await count('> Quoted', 'blockquote') === 1, 'quote')
    ensure(await count('```', 'pre code') === 1, 'code block')
    ensure(await count('| Company', 'table td') === 2, 'table')
    ensure(await count('Above', 'hr') === 1 && await count('Above', 'h2') === 0, 'divider')
    ensure((await help().innerText()).includes('Images and HTML formatting are not shown.'), 'limits')
    await page.screenshot({ path: `${test.info().outputDir}/mh-1-light.png`, fullPage: true })
  })

  await test.step('the examples are only a preview', async () => {
    await result('[Job ad]').locator('a').click({ force: true })
    await page.waitForTimeout(300)
    ensure(browser.contexts().flatMap((c) => c.pages()).length === 1, 'link opened a tab')
    await page.keyboard.press('Escape')
    await closed()
  })

  await test.step('the template in the board settings has the same help', async () => {
    await page.getByRole('button', { name: 'Board settings' }).click()
    await page.getByRole('button', { name: 'Formatting help (Markdown)' }).click()
    await help().waitFor()
    ensure((await help().getByTestId('markdown-example').count()) >= 12, 'examples missing')
    await page.getByRole('button', { name: 'Back to board' }).click()
    await page.locator('[data-task-id="1"]').waitFor()
  })

  await test.step('dark mode and phone', async () => {
    await page.evaluate(() => document.documentElement.classList.add('dark'))
    await page.setViewportSize({ width: 390, height: 844 })
    await page.getByRole('button', { name: 'New task' }).click()
    await page.getByRole('button', { name: 'Formatting help (Markdown)' }).click()
    await help().scrollIntoViewIfNeeded()
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth)
    ensure(!overflow, 'page scrolls sideways')
    await page.screenshot({ path: `${test.info().outputDir}/mh-2-dark-phone.png` })
  })

  if (errors.length) throw new Error(`page errors: ${errors.join('; ')}`)
  for (const context of browser.contexts()) await context.close()
})
