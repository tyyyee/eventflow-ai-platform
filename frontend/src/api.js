export class ApiError extends Error {
  constructor(status, code) { super(code); this.status = status; this.code = code }
}
let expectedAccount = null
export function bindAccount(user) { expectedAccount = user || null }

export async function api(path, { method = 'GET', body, csrf, signal } = {}) {
  const response = await fetch(`/v2${path}`, {
    method, credentials: 'same-origin',
    headers: { ...(body ? { 'Content-Type': 'application/json' } : {}), ...(csrf ? { 'X-CSRF-Token': csrf } : {}), ...(expectedAccount && !path.startsWith('/auth/') ? { 'X-Account-ID': expectedAccount } : {}) },
    body: body ? JSON.stringify(body) : undefined,
    signal: signal ? AbortSignal.any([signal, AbortSignal.timeout(10000)]) : AbortSignal.timeout(10000),
  })
  if (response.status === 204) {if(path==='/auth/logout')window.dispatchEvent(new CustomEvent('life-session',{detail:null}));return null}
  let data
  try { data = await response.json() } catch(error) { if(signal?.aborted || ['AbortError','TimeoutError'].includes(error.name))throw error;throw new ApiError(response.status>=400?response.status:502,'INVALID_RESPONSE') }
  if (!response.ok) {if(['UNAUTHORIZED','SESSION_CHANGED'].includes(data.error))window.dispatchEvent(new CustomEvent('life-session',{detail:null}));throw new ApiError(response.status, data.error || 'SERVICE_UNAVAILABLE')}
  if(['/auth/login','/auth/register'].includes(path))window.dispatchEvent(new CustomEvent('life-session',{detail:data}));
  return data
}

const messages = {
  ASSISTANT_SENSITIVE_INPUT:'请勿向助手发送密码、恢复码、核销码或密钥。删除敏感内容后重新提问。', LOCAL_INVALID_INPUT:'请检查填写的内容、时间和金额。', LOCAL_VERSION_CONFLICT:'内容已被更新，请刷新后再操作。', LOCAL_NOT_FOUND:'这条内容不存在或已下架。', LOCAL_LIFE_DISABLED:'生活服务暂未开放。', OPERATOR_REQUIRED:'此操作需要运营权限。',
  REVIEW_EXISTS:'你已评价过这家店，请在我的评价中编辑原评价。', SELF_LIKE_FORBIDDEN:'不能给自己的评价点赞。', COUPON_NOT_CLAIMABLE:'这张券暂时不可领取，请查看领取时间和发放状态。', COUPON_SOLD_OUT:'这张券已经领完。', COUPON_NOT_USABLE:'这张券当前不可使用，请核对状态和有效期。', COUPON_MINIMUM_NOT_MET:'消费金额未达到使用门槛。', COUPON_RULES_FROZEN:'已发布的规则不能修改，请新建优惠券。', LOCAL_KEY_CONFLICT:'这次操作与原请求内容不同，请先确认原操作结果。', RECOVERY_FAILED:'用户名或恢复码不正确。', LOCAL_INVALID_IMAGE:'图片不可使用，请重新选择 JPG、PNG 或 WebP。', MEDIA_TOO_LARGE:'图片超过大小或像素限制。', MEDIA_QUOTA:'今天的上传次数或存储空间已达到上限。', MEDIA_ALREADY_USED:'这张图片已用于其他内容，请重新上传。', FAVORITE_LIMIT:'收藏数量已达到上限。',

  ASSISTANT_DISABLED: '票务助手暂未开启，请先使用活动广场。',
  ASSISTANT_BUSY: '上一条问题仍在处理，请等待结果或刷新对话。',
  ASSISTANT_QUEUE_FULL: '助手当前较忙，请稍后再试。',
  ASSISTANT_RATE_LIMIT: '提问比较频繁，请稍等一分钟再试。',
  ASSISTANT_SESSION_LIMIT: '已达到对话数量上限，请删除不需要的旧对话。',
  ASSISTANT_TURN_LIMIT: '当前对话已较长，请新建对话后继续。',
  ASSISTANT_INVALID_REQUEST: '问题或请求标识不正确，请输入1000字以内的问题。',
  ASSISTANT_SESSION_NOT_FOUND: '这段对话不存在，请刷新后重新选择。',
  ASSISTANT_RUN_NOT_FOUND: '未找到这条问题，请刷新对话。',
  ASSISTANT_KEY_CONFLICT: '原问题与重试内容不一致，请先刷新对话核实。',
  SANDBOX_PAYMENTS_DISABLED: '当前环境未开启模拟支付，请联系演示环境维护者。',
  ORDER_NOT_PAYABLE: '订单已关闭或超过支付时间，请刷新订单查看最新状态。',
  PAYMENT_REQUIRED: '这笔订单已进入支付流程，请通过模拟支付完成。',
  PAYMENT_NOT_FOUND: '未找到付款记录，请刷新重试。',
  PAYMENT_AMOUNT_MISMATCH: '付款金额与订单不一致，请刷新订单。',
  BAD_CREDENTIALS: '账号或密码不正确。', ACCOUNT_UNAVAILABLE: '这个用户名已被使用。',
  INVALID_USERNAME: '用户名需为 4–24 位小写字母、数字或下划线，以字母开头。',
  INVALID_PASSWORD: '密码至少 12 个字符，UTF-8 编码不超过 72 字节。',
  INVALID_DISPLAY_NAME: '昵称需为 1–24 个字符。',
  AUTH_RATE_LIMITED: '登录尝试过于频繁，请稍后再试。',
  UNAUTHORIZED: '登录已过期，请重新登录。', CSRF_REJECTED: '会话已更新，请刷新页面后重试。',
  SESSION_CHANGED: '其他页面已切换账号，请重新确认登录。',
  ALREADY_PURCHASED: '此活动每人限购一次，取消或过期后也不能再次购买。',
  SOLD_OUT: '本场活动已售罄。', ACTIVITY_CLOSED: '本场活动已结束。',
  STILL_AVAILABLE: '当前仍有余票，可以直接抢票。', WAITLIST_NOT_FOUND: '未找到这条候补记录。',
  WAITLIST_TERMINAL: '这条候补记录已结束，无法再次操作。',
  ACTIVITY_NOT_FOUND: '活动不存在或已下架。', INVALID_REQUEST: '请求参数不正确。',
  PROCESS_DEADLINE: '处理已超时，本次请求不会再生成订单。',
  REQUEST_NOT_FOUND: '暂未查到这笔请求，可使用原请求重试。',
  INVALID_PLAN: '规划条件不正确，请检查时间、预算和人数。',
  PLANNING_DISABLED: '智能规划尚未配置模型服务。', PLAN_KEY_CONFLICT: '规划请求标识已被其他内容使用。',
  TOO_MANY_PLANS: '已有规划任务正在执行，请稍后再试。', PLANNING_QUEUE_FULL: '规划任务较多，请稍后再试。',
  PLAN_NOT_FOUND: '规划任务不存在或已过期。', PLAN_TERMINAL: '规划任务已经结束。',
}
export function explain(error) {
  return messages[error.code] || (error.status === 429 ? '请求较多，请稍后再试。' : error.status >= 500 ? '服务暂时繁忙，请稍后重试。' : '连接未完成，请检查网络后重试。')
}

const storageKey = user => `life.pending.v1.${user}`
export function readPending(user) {
  if (!user) return []
  try {
    const data = JSON.parse(localStorage.getItem(storageKey(user)) || '[]')
    return Array.isArray(data) ? data.filter(x => typeof x.key === 'string' && /^[a-zA-Z0-9-]{8,80}$/.test(x.key) && Number.isSafeInteger(x.activityId)).slice(0, 30) : []
  } catch { return [] }
}
// Persist before sending: an ambiguous response must never generate a second intent.
export function remember(user, intent) {
  const values = readPending(user).filter(x => x.key !== intent.key)
  if (values.length >= 30) throw new Error('请先处理待确认的请求。')
  localStorage.setItem(storageKey(user), JSON.stringify([...values, intent]))
}
export function forget(user, key) {
  localStorage.setItem(storageKey(user), JSON.stringify(readPending(user).filter(x => x.key !== key)))
}
