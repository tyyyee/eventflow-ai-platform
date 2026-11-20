import assert from 'node:assert/strict'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'

const { chromium } = await import(process.env.PLAYWRIGHT_MODULE || 'playwright')
const origin = process.env.LIFE_URL || 'http://127.0.0.1:4286'
const output = resolve(process.env.LIFE_EVIDENCE || 'test-output/assistant')
await mkdir(output, { recursive: true })
const browser = await chromium.launch({ headless: true, channel: process.env.LIFE_BROWSER_CHANNEL || 'chrome' })
const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } })
const page = await context.newPage(), checks = [], errors = []
page.on('pageerror', error => errors.push(error.message))
page.setDefaultTimeout(20000)
async function check(name, action) { await action(); checks.push(name); console.log(`PASS ${name}`) }
async function send(text) {
  await page.getByRole('textbox', { name: '向票务助手提问' }).fill(text)
  const before = await page.locator('.assistant-turn').count()
  await page.getByRole('button', { name: '发送', exact: true }).click()
  await page.waitForFunction(count => document.querySelectorAll('.assistant-turn').length === count + 1 && !document.querySelector('.assistant-working'), before)
  await page.getByRole('textbox', { name: '向票务助手提问' }).waitFor({ state: 'visible' })
  const last = page.locator('.assistant-turn').last()
  assert.equal(await last.locator('.assistant-failed').count(), 0, await last.innerText())
  return last
}
async function layoutShot(name) {
  assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), 'horizontal page overflow')
  const overflow = await page.locator('.assistant-page button').evaluateAll(items => items.filter(item => item.checkVisibility() && item.scrollWidth > item.clientWidth + 2).map(item => item.textContent))
  assert.deepEqual(overflow, [], 'assistant button overflows')
  if (page.viewportSize().width <= 620) {
    const tops = await page.locator('.topbar nav button').evaluateAll(items => items.map(item => item.getBoundingClientRect().top))
    assert(Math.max(...tops) - Math.min(...tops) <= 2, 'mobile navigation wraps onto another row')
  }
  await page.screenshot({ path: resolve(output, `${name}.png`), fullPage: false })
}
async function showLatestAnswer() {
  await page.locator('.assistant-conversation').evaluate(container => {
    const last = container.querySelector('.assistant-turn:last-of-type') || [...container.querySelectorAll('.assistant-turn')].at(-1)
    if (last) container.scrollTop += last.getBoundingClientRect().top - container.getBoundingClientRect().top - 16
  })
}
try {
  await page.goto(origin)
  await check('anonymous assistant requires login', async () => {
    await page.getByRole('button', { name: '智能票务助手', exact: true }).click()
    await page.getByRole('heading', { name: '登录后，开始选活动' }).waitFor()
    assert.equal(await page.getByRole('textbox', { name: '向票务助手提问' }).count(), 0)
  })
  const username = `agent_${Date.now().toString(36)}`
  const registered = await context.request.post(`${origin}/v2/auth/register`, { data: { username, password: 'local-agent-browser-passphrase', displayName: '助手验收' } })
  assert.equal(registered.status(), 201)
  const login = await registered.json()
  await page.reload()
  await page.getByRole('button', { name: '智能票务助手', exact: true }).click()
  await check('first message creates a persistent conversation and real activity cards', async () => {
    const last = await send('明天预算100元，推荐音乐活动')
    assert(await last.locator('.assistant-event').count() > 0)
    const sessions = await (await context.request.get(`${origin}/v2/assistant/sessions`)).json()
    assert.equal(sessions.length, 1)
    const detail = await (await context.request.get(`${origin}/v2/assistant/sessions/${sessions[0].id}`)).json()
    assert.equal(detail.mode, 'stub'); assert.equal(detail.turns[0].modelCalls, 0)
    for (const activity of detail.turns[0].reply.activities) {
      const catalog = await (await context.request.get(`${origin}${activity.sourcePath}`)).json()
      const offer = catalog.offers.find(item => String(item.id) === activity.activityId)
      assert(offer); assert.equal(offer.priceCents, activity.priceCents); assert.equal(offer.available, activity.available)
    }
  })
  await check('campus refinement retains budget category and event date', async () => {
    const last = await send('换成主校区的，其他条件不变')
    assert(await last.locator('.assistant-event').count() > 0)
    for (const text of await last.locator('.assistant-event').allTextContents()) assert(text.includes('主校区'))
    const conditions = await page.getByLabel('当前活动筛选条件').innerText()
    assert(conditions.includes('100')); assert(conditions.includes('音乐演出')); assert(conditions.includes('主校区'))
    await page.evaluate(() => window.scrollTo(0, 0))
    await showLatestAnswer()
    await layoutShot('assistant-activities-desktop')
    await page.setViewportSize({ width: 390, height: 1000 })
    await showLatestAnswer()
    await layoutShot('assistant-activities-mobile')
    await page.setViewportSize({ width: 1440, height: 1000 })
  })
  await check('ordinal refresh opens exact purchasable activity in existing detail', async () => {
    const last = await send('第一个还有票吗？')
    assert.equal(await last.locator('.assistant-event').count(), 1)
    await last.getByRole('button', { name: '查看活动与购票' }).click()
    await page.getByRole('dialog').waitFor()
    await page.waitForFunction(() => document.activeElement?.id.startsWith('ticket-offer-'))
    const selected = page.locator('dialog .offer:focus')
    assert.equal(await selected.count(), 1)
    await selected.getByRole('button', { name: '立即抢票', exact: false }).click()
    await page.getByRole('heading', { name: '我的票夹', exact: true }).waitFor()
    await page.locator('.order').filter({ hasText: '待支付' }).first().waitFor()
  })
  await page.getByRole('button', { name: '智能票务助手', exact: true }).click()
  await check('assistant reads actual RocketMQ-created order without creating another order', async () => {
    await page.waitForFunction(() => document.querySelectorAll('.assistant-turn').length >= 3)
    const last = await send('看看我的抢票结果')
    assert.equal(await last.locator('.assistant-order').count(), 1)
    assert((await last.innerText()).includes('订单已创建'))
    assert.equal(await last.getByRole('button', { name: '打开我的票夹' }).count(), 1)
  })
  await check('published rules are shown and retained after reload', async () => {
    const last = await send('购票有什么规则？')
    assert((await last.innerText()).includes('5分钟'))
    assert((await last.innerText()).includes('未列出的'))
    await page.reload(); await page.getByRole('button', { name: '智能票务助手', exact: true }).click()
    await page.waitForFunction(() => document.querySelectorAll('.assistant-turn').length === 5)
    assert((await page.locator('.assistant-turn').last().innerText()).includes('5分钟'))
  })
  await check('lost response retries the same turn key once', async () => {
    const keys = [], count = await page.locator('.assistant-turn').count()
    await page.route('**/v2/assistant/sessions/*/turns', async route => {
      if (route.request().method() !== 'POST') return route.continue()
      keys.push(route.request().postDataJSON().requestKey)
      const result = await route.fetch()
      if (keys.length === 1) await route.abort('failed')
      else await route.fulfill({ response: result })
    })
    await page.getByRole('textbox', { name: '向票务助手提问' }).fill('看看我的抢票结果')
    await page.getByRole('button', { name: '发送', exact: true }).click()
    const retry = page.getByRole('button', { name: '原问题重试', exact: true })
    await retry.waitFor(); await page.waitForFunction(() => !document.querySelector('.assistant-working'))
    await retry.click()
    await page.waitForFunction(() => !document.querySelector('.assistant-retry'))
    await page.waitForFunction(() => !document.querySelector('.assistant-working'))
    assert.equal(keys.length, 2); assert.equal(keys[0], keys[1])
    assert.equal(await page.locator('.assistant-turn').count(), count + 1)
    assert.equal(await page.locator('.assistant-turn').last().locator('.assistant-order').count(), 1)
    await page.unroute('**/v2/assistant/sessions/*/turns')
  })
  await check('desktop and mobile assistant layouts', async () => {
    for (const width of [1440, 768, 390, 320]) {
      await page.setViewportSize({ width, height: 1000 })
      await page.locator('.assistant-conversation').evaluate(element => { element.scrollTop = element.scrollHeight })
      await layoutShot(`assistant-${width}`)
    }
  })
  await check('new conversation clears preference context', async () => {
    await page.setViewportSize({ width: 1440, height: 1000 })
    await page.getByRole('button', { name: '新对话', exact: true }).click()
    await page.getByRole('heading', { name: '从一个具体需求开始' }).waitFor()
    assert.equal(await page.getByLabel('当前活动筛选条件').count(), 0)
    const result = await send('购票有什么规则？')
    assert.equal(await result.locator('.assistant-rule').count(), 1)
  })
  assert.deepEqual(errors, [])
  await writeFile(resolve(output, 'results.json'), JSON.stringify({ passed: true, mode: 'stub', checks, errors }, null, 2))
} catch (error) {
  await page.screenshot({ path: resolve(output, 'failure.png'), fullPage: false }).catch(() => {})
  await writeFile(resolve(output, 'results.json'), JSON.stringify({ passed: false, checks, errors, failure: error.stack }, null, 2))
  throw error
} finally { await browser.close() }
