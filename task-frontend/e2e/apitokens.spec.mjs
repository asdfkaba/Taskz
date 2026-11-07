// API tokens in the account settings: create one for a board, see it once, revoke it.
import { test } from '@playwright/test'
import { BASE, resetMock } from './support.mjs'

test('API tokens', async ({ browser }) => {
  await resetMock()
  const page = await browser.newPage({ viewport: { width: 1400, height: 900 } })
  const errors = []
  page.on('pageerror', (e) => errors.push(e.message))
  const ensure = (cond, msg) => { if (!cond) throw new Error(msg) }
  const state = async () => (await fetch(`${BASE}/api/__state`)).json()
  const tokens = () => page.getByTestId('api-tokens')

  await test.step('a new token is shown once, with its board and rights', async () => {
    await page.goto(`${BASE}/account`)
    const create = tokens().getByRole('button', { name: 'Create token' })
    await create.waitFor()
    ensure(await create.isDisabled(), 'can create without board and name')
    await tokens().getByRole('combobox', { name: 'Board' }).click()
    await page.getByRole('option', { name: 'My board' }).click()
    await tokens().getByRole('textbox', { name: 'Token name' }).fill('Bewerbungen')
    await page.locator('label[for="token-write"]').click()
    await create.click()
    const value = await tokens().getByRole('textbox', { name: 'New API token' }).inputValue()
    ensure(/^taskz_[A-Za-z0-9_-]{44}$/.test(value), value)
    await tokens().getByText('shown only once').waitFor()
    await tokens().getByTestId('api-token').getByText('My board · read + write').waitFor()
    const [stored] = (await state()).apiTokens
    ensure(stored.boardId === 1 && stored.read && stored.write && stored.name === 'Bewerbungen', JSON.stringify(stored))
    await page.screenshot({ path: `${test.info().outputDir}/api-token-created.png` })
  })

  await test.step('after a reload only the name is left; revoking removes it', async () => {
    await page.reload()
    await tokens().getByTestId('api-token').waitFor()
    ensure((await tokens().getByRole('textbox', { name: 'New API token' }).count()) === 0, 'token shown again')
    await tokens().getByRole('button', { name: 'Revoke Bewerbungen' }).click()
    await page.getByRole('button', { name: 'Revoke', exact: true }).click()
    await page.waitForFunction(async (base) => (await (await fetch(`${base}/api/__state`)).json()).apiTokens.length === 0, BASE)
    ensure((await tokens().getByTestId('api-token').count()) === 0, 'still listed')
  })

  ensure(errors.length === 0, errors.join('\n'))
})
