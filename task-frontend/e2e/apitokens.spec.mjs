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

  await test.step('a new token is shown once, with its board, scopes and expiry', async () => {
    await page.goto(`${BASE}/account`)
    const create = tokens().getByRole('button', { name: 'Create token' })
    await create.waitFor()
    ensure(await create.isDisabled(), 'can create without board and name')
    await tokens().getByRole('combobox', { name: 'Board' }).click()
    await page.getByRole('option', { name: 'My board' }).click()
    await tokens().getByRole('textbox', { name: 'Token name' }).fill('Bewerbungen')
    // Without any scope there is nothing to create.
    await page.locator('label[for="token-scope-tasks-read"]').click()
    ensure(await create.isDisabled(), 'can create without scopes')
    await page.locator('label[for="token-scope-tasks-create"]').click()
    await page.locator('label[for="token-scope-attachments-write"]').click()
    await create.click()
    const value = await tokens().getByRole('textbox', { name: 'New API token' }).inputValue()
    ensure(/^taskz_[A-Za-z0-9_-]{44}$/.test(value), value)
    await tokens().getByText('shown only once').waitFor()
    const listed = tokens().getByTestId('api-token')
    await listed.getByText('tasks:create, attachments:write').waitFor()
    ensure(/expires \w{3} \d+.* · never used/.test(await listed.innerText()), await listed.innerText())
    const [stored] = (await state()).apiTokens
    ensure(stored.boardId === 1 && stored.name === 'Bewerbungen', JSON.stringify(stored))
    ensure(JSON.stringify(stored.scopes) === '["tasks:create","attachments:write"]', JSON.stringify(stored))
    const days = (new Date(stored.expiresAt) - Date.now()) / 86400000
    ensure(days > 89 && days <= 90, JSON.stringify(stored))
    await page.screenshot({ path: `${test.info().outputDir}/api-token-created.png` })
  })

  await test.step('a token can be made to never expire', async () => {
    await tokens().getByRole('textbox', { name: 'Token name' }).fill('Forever')
    await tokens().getByRole('combobox', { name: 'Expiry' }).click()
    await page.getByRole('option', { name: 'Never expires' }).click()
    await tokens().getByRole('button', { name: 'Create token' }).click()
    await tokens().getByTestId('api-token').nth(1).getByText('never expires').waitFor()
    ensure((await state()).apiTokens[1].expiresAt === null, JSON.stringify((await state()).apiTokens[1]))
  })

  await test.step('after a reload only the name is left; revoking removes it', async () => {
    await page.reload()
    await tokens().getByTestId('api-token').first().waitFor()
    ensure((await tokens().getByRole('textbox', { name: 'New API token' }).count()) === 0, 'token shown again')
    await tokens().getByRole('button', { name: 'Revoke Bewerbungen' }).click()
    await page.getByRole('button', { name: 'Revoke', exact: true }).click()
    await page.waitForFunction(async (base) => (await (await fetch(`${base}/api/__state`)).json()).apiTokens.length === 1, BASE)
    ensure((await tokens().getByTestId('api-token').count()) === 1, 'still listed')
  })

  ensure(errors.length === 0, errors.join('\n'))
})
