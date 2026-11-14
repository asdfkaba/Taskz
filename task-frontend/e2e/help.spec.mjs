// Help page: what Taskz can do, reachable from the account menu.
import { test } from '@playwright/test'
import { BASE, resetMock } from './support.mjs'

test('Help page', async ({ browser }) => {
  await resetMock()
  const page = await browser.newPage({ viewport: { width: 1200, height: 800 } })
  const errors = []
  page.on('pageerror', (e) => errors.push(e.message))
  const ensure = (cond, msg) => { if (!cond) throw new Error(msg) }
  const help = () => page.getByTestId('help')

  await page.goto(`${BASE}/boards`)
  await page.locator('[data-task-id="1"]').waitFor()

  await test.step('the account menu leads to it', async () => {
    await page.getByRole('button', { name: 'Account menu' }).click()
    await page.getByRole('menuitem', { name: 'Help' }).click()
    await help().getByRole('heading', { name: 'What Taskz can do' }).waitFor()
    ensure(new URL(page.url()).pathname === '/help', page.url())
    ensure((await page.title()) === 'Help · Taskz', await page.title())
    await page.screenshot({ path: `${test.info().outputDir}/help-1.png`, fullPage: true })
  })

  await test.step('a topic link jumps to its section, below the sticky header', async () => {
    await help().getByRole('link', { name: 'Archive', exact: true }).click()
    const heading = help().getByRole('heading', { name: 'Archive', exact: true })
    await page.waitForFunction(() => location.hash === '#archive')
    const top = (await heading.boundingBox()).y
    ensure(top >= 56 && top < 400, `archive heading at ${top}`)
    await help().getByRole('region', { name: 'Archive' }).getByText('⋮ menu').first().waitFor()
  })

  await test.step('phone width: nothing scrolls sideways', async () => {
    await page.setViewportSize({ width: 390, height: 844 })
    const wide = await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth)
    ensure(!wide, 'the page scrolls sideways')
    await page.screenshot({ path: `${test.info().outputDir}/help-2-phone.png` })
  })

  await test.step('the link at the end goes back to the boards', async () => {
    await help().getByRole('link', { name: 'To your boards' }).click()
    await page.locator('[data-task-id="1"]').waitFor()
  })

  if (errors.length) throw new Error(`page errors: ${errors.join('; ')}`)
  for (const context of browser.contexts()) await context.close()
})
