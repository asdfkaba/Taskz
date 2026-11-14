// Add column button next to the filter.
import { test } from '@playwright/test'
import { BASE, resetMock } from './support.mjs'

test('Add column button next to the filter', async ({ browser }) => {
  const base = BASE
  const outDir = test.info().outputDir
  await resetMock()

  const errors = []
  async function check(...args) {
    const fn = args.pop()
    await test.step(args.pop(), fn)
  }
  const expect = (c, m) => { if (!c) throw new Error(m) }
  async function addColumn(page, open, name) {
    await open()
    await page.locator('#column-name').fill(name)
    await page.locator('.p-dialog').getByRole('button', { name: 'Add column' }).click()
    await page.waitForFunction(() => !document.querySelector('.p-dialog'))
    await page.waitForTimeout(700) // smooth scroll
  }
  const inView = (page, name) => page.locator('[data-column-id]', { has: page.locator('h2', { hasText: name }) })
    .evaluate((el) => { const r = el.getBoundingClientRect(); return r.left >= 0 && r.right <= window.innerWidth })

  const desk = await browser.newPage({ viewport: { width: 1200, height: 800 } })
  desk.on('pageerror', (e) => errors.push(e.message))
  await desk.goto(`${base}/boards`)
  await desk.getByText('Task A', { exact: true }).waitFor()
  const headerButton = () => desk.getByRole('button', { name: 'Add column' })

  await check(desk, 'Add column and New task end the filter row, the header ends with Settings', async () => {
    const names = async (row) => (await row.getByRole('button').allInnerTexts()).map((t) => t.trim()).filter(Boolean)
    const header = await names(desk.locator('h1').locator('xpath=ancestor::div[contains(@class,"justify-between")][1]'))
    expect(header.at(-1) === 'Settings', JSON.stringify(header))
    const row = await names(desk.getByRole('search').locator('xpath=..'))
    expect(JSON.stringify(row.slice(-2)) === '["Add column","New task"]', JSON.stringify(row))
    expect((await headerButton().count()) === 1, 'more than one Add column button')
    await desk.screenshot({ path: `${outDir}/ac-1-desktop.png` })
  })

  await check(desk, 'new columns are created and scrolled into view', async () => {
    for (const n of ['Review', 'QA', 'Blocked', 'Later']) await addColumn(desk, () => headerButton().click(), n)
    const names = (await (await fetch(`${base}/api/__state`)).json()).columns.map((c) => c.name)
    expect(names.at(-1) === 'Later' && names.length === 7, JSON.stringify(names))
    expect(await inView(desk, 'Later'), 'new column off screen')
    // Scrolled to the end, the last column keeps the 16px gutter to the right edge.
    const gap = await desk.evaluate(() => {
      const scroller = document.querySelector('[data-column-id]').closest('.overflow-x-auto')
      scroller.scrollLeft = scroller.scrollWidth
      const last = [...document.querySelectorAll('[data-column-id]')].at(-1)
      return scroller.getBoundingClientRect().right - last.getBoundingClientRect().right
    })
    expect(Math.round(gap) === 16, `gap at the end ${gap}`)
    await desk.screenshot({ path: `${outDir}/ac-2-desktop-after.png` })
  })

  await check(desk, 'editing a column does not scroll', async () => {
    await desk.locator('[data-column-id]').first().evaluate((el) => el.scrollIntoView({ inline: 'start' }))
    await desk.locator('[data-column-id]').first().getByRole('button', { name: 'Column actions' }).click()
    await desk.getByRole('menuitem', { name: 'Edit' }).click()
    await desk.locator('#column-name').fill('To do!')
    await desk.locator('.p-dialog').getByRole('button', { name: 'Save' }).click()
    await desk.waitForFunction(() => !document.querySelector('.p-dialog'))
    await desk.waitForTimeout(700)
    expect(await inView(desk, 'To do!'), 'scrolled away from edited column')
  })

  const phone = await (await browser.newContext({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true })).newPage()
  phone.on('pageerror', (e) => errors.push(e.message))
  await phone.goto(`${base}/boards`)
  await phone.getByText('Task A', { exact: true }).waitFor()

  await check(phone, 'phone: no header button, tab + adds and shows the column', async () => {
    const tabs = phone.locator('nav[aria-label="Columns"]')
    expect((await phone.getByRole('button', { name: 'Add column' }).count()) === 1, 'expected only the tab +')
    await addColumn(phone, () => tabs.getByRole('button', { name: 'Add column' }).tap(), 'Phone col')
    expect(await inView(phone, 'Phone col'), 'not in view')
    expect((await tabs.locator('[aria-current="true"]').innerText()).startsWith('Phone col'), 'tab not active')
    expect(!(await phone.evaluate(() => document.documentElement.scrollWidth > window.innerWidth)), 'page scrolls sideways')
    await phone.screenshot({ path: `${outDir}/ac-3-phone.png` })
  })

  if (errors.length) throw new Error(`page errors: ${errors.join('; ')}`)
  for (const context of browser.contexts()) await context.close()
})
