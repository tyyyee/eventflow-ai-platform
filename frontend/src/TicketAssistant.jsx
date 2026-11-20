import React, { useEffect, useRef, useState } from 'react'
import { ArrowRight, Clock3, MessageCircle, Plus, Send, Sparkles, Ticket, Trash2 } from 'lucide-react'
import { api, explain } from './api'
import './assistant.css'
import AssistantCards from './life/AssistantResults'

const active = status => ['QUEUED', 'RUNNING'].includes(status)
const money = value => (value / 100).toFixed(2).replace(/\.00$/, '')
const when = value => new Date(value).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false })
const sale = { AVAILABLE: '查询时有余票', SOLD_OUT: '已售罄', NOT_STARTED: '尚未开售', ENDED: '抢票已结束' }
const examples = ['明天预算100元，推荐音乐活动', '换成主校区的，其他条件不变', '第一个还有票吗？', '购票有什么规则？', '看看我的抢票结果']
const intentKey = (user, session) => `qupiao.assistant.pending.${user}.${session}`
function readIntent(user, session) {
  try {
    const value = JSON.parse(sessionStorage.getItem(intentKey(user, session)) || 'null')
    return value && typeof value.message === 'string' && value.message.length <= 1000 && /^[A-Za-z0-9_-]{8,64}$/.test(value.requestKey) ? value : null
  } catch { return null }
}

export default function TicketAssistant({ localLife=false, session, openAuth, openActivity, openOrders, sessionFailure }) {
  const user = session?.user.id
  const samples=localLife?['主校区预算50元，推荐咖啡店','换成东校区的，其他条件不变','对比这几家店','第一个有什么优惠券？','看看我的券包','优惠券过期后还能用吗？']:examples
  const [sessions, setSessions] = useState([]), [id, setId] = useState(null), [data, setData] = useState(null)
  const [message, setMessage] = useState(''), [error, setError] = useState(''), [busy, setBusy] = useState(false), [loading, setLoading] = useState(true)
  const [retry, setRetry] = useState(null), [reload, setReload] = useState(0),[serviceMode,setServiceMode]=useState(null)
  const lock = useRef(false), bottom = useRef(null), version = useRef(0), alive = useRef(true)
  useEffect(() => { alive.current = true; return () => { alive.current = false } }, [])
  useEffect(()=>{const ask=e=>{setMessage(e.detail);document.querySelector('#ticket-assistant-message')?.focus()};window.addEventListener('life-assistant-question',ask);return()=>window.removeEventListener('life-assistant-question',ask)},[])
  const running = Boolean(data?.activeRun)
  useEffect(()=>{if(!user||!localLife)return;const c=new AbortController();api('/assistant/status',{signal:c.signal}).then(x=>setServiceMode(x.mode)).catch(()=>{});return()=>c.abort()},[user,localLife])
  useEffect(() => {
    if (!user) { setLoading(false); return }
    const controller = new AbortController()
    api('/assistant/sessions', { signal: controller.signal }).then(result => {
      setSessions(result); setId(current => current || result[0]?.id || null)
    }).catch(e => { if (!controller.signal.aborted) setError(sessionFailure(e)) }).finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => controller.abort()
  }, [user, reload])
  useEffect(() => {
    if (!id || !user) { setData(null); return }
    const controller = new AbortController(), generation = ++version.current
    setData(null); setError(''); setRetry(readIntent(user, id))
    api(`/assistant/sessions/${id}`, { signal: controller.signal }).then(result => {
      if (generation === version.current) setData(result)
    }).catch(e => { if (!controller.signal.aborted) setError(sessionFailure(e)) })
    return () => { controller.abort(); version.current++ }
  }, [id, user])
  useEffect(() => {
    if (!id || !data?.activeRun) return
    const controller = new AbortController(), run = data.activeRun
    let inFlight = false
    async function poll() {
      if (inFlight || controller.signal.aborted) return
      inFlight = true
      try {
        const result = await api(`/assistant/sessions/${id}/turns/${run}`, { signal: controller.signal })
        if (controller.signal.aborted) return
        if (active(result.status)) {
          setData(previous => !previous || previous.id !== id ? previous : { ...previous, turns: previous.turns.map(turn => turn.id === result.id ? result : turn) })
        } else {
          const complete = await api(`/assistant/sessions/${id}`, { signal: controller.signal })
          if (!controller.signal.aborted) { setData(complete); setError(''); setReload(n => n + 1) }
        }
      } catch (e) { if (!controller.signal.aborted) setError(sessionFailure(e)) }
      finally { inFlight = false }
    }
    poll(); const timer = setInterval(poll, 900)
    return () => { controller.abort(); clearInterval(timer) }
  }, [id, data?.activeRun])
  useEffect(() => { const conversation = bottom.current?.parentElement; if (conversation) conversation.scrollTop = conversation.scrollHeight }, [data?.turns.length, running])
  async function create(activate = true) {
    const result = await api('/assistant/sessions', { method: 'POST', csrf: session.csrfToken })
    if (!alive.current) return result
    setSessions(previous => [{ id: result.id, updatedAt: new Date().toISOString() }, ...previous])
    if (activate) { setId(result.id); setData(result) }
    return result
  }
  async function newSession() {
    if (!user) { openAuth(); return }
    if (lock.current) return
    lock.current = true; setBusy(true); setError('')
    try { await create(); setMessage(''); setRetry(null) }
    catch (e) { setError(sessionFailure(e)) }
    finally { lock.current = false; setBusy(false) }
  }
  async function removeSession() {
    if (!id || running || lock.current) return
    lock.current = true; setBusy(true); setError('')
    try {
      await api(`/assistant/sessions/${id}`, { method: 'DELETE', csrf: session.csrfToken })
      try { sessionStorage.removeItem(intentKey(user, id)) } catch { /* Local cleanup does not change server deletion. */ }
      const remaining = sessions.filter(item => item.id !== id)
      setSessions(remaining); setId(remaining[0]?.id || null); setData(null); setRetry(null)
    } catch (e) { setError(sessionFailure(e)) }
    finally { lock.current = false; setBusy(false) }
  }
  async function send(event, previousIntent) {
    event?.preventDefault()
    if (!user) { openAuth(); return }
    const input = (previousIntent?.message || message).trim()
    if (!input || lock.current || running) return
    lock.current = true; setBusy(true); setError('')
    let currentId = id, intent, created
    try {
      if (!currentId) { created = await create(false); currentId = created.id }
      if (!alive.current) return
      const stored = previousIntent || readIntent(user, currentId)
      if (stored && stored.message !== input) { setRetry(stored); setError('上一条发送结果尚未确认，请先用原问题重试。'); return }
      intent = stored || { requestKey: crypto.randomUUID(), message: input }
      // Save before sending, so response loss never silently creates another turn.
      sessionStorage.setItem(intentKey(user, currentId), JSON.stringify(intent))
      const result = await api(`/assistant/sessions/${currentId}/turns`, { method: 'POST', csrf: session.csrfToken, body: intent })
      if (!alive.current) return
      setId(currentId)
      if (active(result.status)) setData(old => { const base = old?.id === currentId ? old : created; return base ? { ...base, activeRun: result.id, turns: [...base.turns.filter(turn => turn.id !== result.id), result] } : old })
      else setData(await api(`/assistant/sessions/${currentId}`))
      try { sessionStorage.removeItem(intentKey(user, currentId)) } catch { /* A repeated original key remains safe. */ }
      setRetry(null); setMessage('')
    } catch (e) {
      if (!alive.current) return
      if (created) setId(currentId)
      setError(e instanceof Error && !e.code && !intent ? '无法保存对话请求，请检查浏览器会话存储后重试。本次尚未发送问题。' : sessionFailure(e))
      if (intent) {
        if (e.status >= 400 && e.status < 500) { try { sessionStorage.removeItem(intentKey(user, currentId)) } catch {} setRetry(null) }
        else setRetry(intent)
      }
    } finally { lock.current = false; setBusy(false) }
  }
  const criteria = data?.state?.criteria
  const shopFilters=data?.state?.shopFilters||{}
  const chips = localLife?Object.entries(shopFilters).map(([key,value])=>({campus:value,category:value,q:value,maxPriceCents:`人均 ≤ ¥${money(value)}`,maxDistanceMeters:`${Number(value)/1000} 公里内`,people:`${value} 人`,totalBudgetCents:`总预算 ¥${money(value)}`,openOnly:value==='true'?'营业中':null,hasCoupon:value==='true'?'有优惠':null}[key])).filter(Boolean):criteria ? [criteria.dateFrom && `${criteria.dateFrom}${criteria.dateTo && criteria.dateTo !== criteria.dateFrom ? ` 至 ${criteria.dateTo}` : ''}`, criteria.hourFrom != null && `${criteria.hourFrom}:00–${criteria.hourTo ?? 24}:00`, criteria.maxPriceCents != null && `预算 ≤ ¥${money(criteria.maxPriceCents)}`, criteria.campus, criteria.category, criteria.query].filter(Boolean) : []
  return <section className={`assistant-page ${localLife?'ll-chat':''}`} aria-label={localLife?"生活选店与客服助手":"智能票务助手"}>
    <div className="heading"><div><p className="eyebrow">{localLife?"QULIFE · AI ASSISTANT":"TICKET ASSISTANT"}</p><h1><MessageCircle size={28}/>{localLife?"生活选店与客服助手":"智能票务助手"}</h1><p className="muted">{localLife?"说说预算和喜好，或告诉我遇到的问题。":"说说你的时间和预算，一起找到合适的活动。"}</p></div>{user && <div className="assistant-actions"><button className="outline" disabled={busy || running} onClick={newSession}><Plus size={16}/>新对话</button>{id && <button className="icon" aria-label="删除当前对话" disabled={busy || running} onClick={removeSession}><Trash2 size={18}/></button>}</div>}</div>
    {!user ? <div className="empty assistant-signin"><Sparkles size={34}/><h2>{localLife?"登录后，开始发现好去处":"登录后，开始选活动"}</h2><p className="muted">支持连续调整预算和校区，比较小店，查看本人的优惠券与订单。</p><button className="primary" onClick={openAuth}>登录 / 注册</button></div> : <>
      <div className="assistant-toolbar"><label>对话<select aria-label="选择票务对话" value={id || ''} disabled={busy || running} onChange={e => setId(e.target.value || null)}><option value="">开始新对话</option>{sessions.map((item, index) => <option key={item.id} value={item.id}>对话 {sessions.length - index} · {when(item.updatedAt)}</option>)}</select></label>{(serviceMode||data?.mode)==='stub'&&<span className="assistant-demo">本地演示</span>}{(serviceMode||data?.mode)==='deepseek'&&<span className="assistant-demo ll-deepseek-mode">DeepSeek</span>}<span className="muted small">{localLife?"偏好可记住，交易由你确认。":"查询提供建议，购票请在活动详情确认。"}</span></div>
      {(serviceMode||data?.mode)==='stub' && <p className="assistant-mode-note">当前为本地体验模式。接通 DeepSeek 后支持更自由的表达；推荐和订单信息始终查询平台数据。</p>}
      <div className="assistant-examples" aria-label="示例问题">{samples.map(text => <button key={text} disabled={busy || running} onClick={() => setMessage(text)}>{text}</button>)}</div>
      {chips.length > 0 && <div className="assistant-criteria" aria-label={localLife?"当前找店条件":"当前活动筛选条件"}><strong>当前条件</strong>{chips.map(chip => <span key={chip}>{chip}</span>)}</div>}
      <div className="assistant-conversation" aria-label={localLife?"生活助手对话记录":"票务对话记录"} aria-busy={running}>
        {loading ? <p className="empty">正在读取对话…</p> : !data?.turns.length && <div className="assistant-welcome"><Sparkles size={28}/><h2>今天，想去哪儿？</h2><p>{localLife?"例如：找一家适合自习的咖啡店，人均50元以内。":"例如：明天预算100元，想看音乐活动。"}</p><p className="muted small">可以继续说“换到东校区”“再便宜一点”或“比较这几家”。</p></div>}
        {data?.turns.map(turn => <div className="assistant-turn" key={turn.id}>
          <div className="assistant-user"><span>我</span><p>{turn.message}</p></div>
          <div className="assistant-answer"><div className="assistant-answer-title"><Sparkles size={16}/><strong>{localLife?"趣生活助手":"趣票助手"}</strong></div>
            {active(turn.status) ? <p className="assistant-working" role="status"><Clock3 size={15}/>正在查询，请稍候…</p> : <>
              <p className={turn.reply?.errorCode ? 'assistant-failed' : ''}>{turn.reply?.text || '本次查询未完成，请重试或打开活动详情核实。'}</p>
              {turn.reply?.local && <AssistantCards result={turn.reply.local}/>}
              {turn.reply?.activities.length > 0 && <div className="assistant-card-grid">{turn.reply.activities.map((activity, index) => <article className="assistant-event" key={activity.activityId}>
                <div className="assistant-event-head"><span className="assistant-number">{index + 1}</span><span className={`assistant-sale ${activity.saleStatus.toLowerCase()}`}>{sale[activity.saleStatus]}</span></div>
                <h3>{activity.title}</h3><p>{activity.sessionTitle}</p><p className="muted small">{activity.campus} · {when(activity.startsAt)}–{when(activity.endsAt)}</p>
                <div className="assistant-event-price"><strong>¥{money(activity.priceCents)}</strong><span>查询时余 {activity.available} 张</span></div>
                {activity.saleStatus === 'NOT_STARTED' && <p className="muted small">{when(activity.saleStartsAt)} 开售</p>}
                <button className="outline" onClick={() => openActivity(activity)}>查看活动与购票<ArrowRight size={15}/></button>
              </article>)}</div>}
              {turn.reply?.rules.map(rule => <article className="assistant-rule" key={`${rule.activityId || 'common'}-${rule.title}`}><strong>{rule.title}</strong><p>{rule.terms}</p>{rule.notice && <p className="muted small">{rule.notice}</p>}</article>)}
              {turn.reply?.orders.map(order => <article className="assistant-order" key={order.requestId}><h3><Ticket size={16}/>{order.title}</h3><p>{order.description}</p>{order.confirmUntil && <p className="muted small">支付截止：{when(order.confirmUntil)}</p>}<small className="muted">请求编号：{order.requestId}</small></article>)}
              {(turn.reply?.kind === 'ORDERS' || turn.reply?.orders.length > 0) && <button className="outline" onClick={openOrders}>打开我的票夹<ArrowRight size={15}/></button>}
              {!turn.reply?.local && turn.reply?.sources.length > 0 && <p className="assistant-source">依据：{turn.reply.activities.length ? '已发布活动与交易余票查询' : turn.reply.rules.length ? '平台及活动已发布规则' : '本人请求与订单记录'} · {when(turn.reply.checkedAt)} 查询</p>}
            </>}
          </div>
        </div>)}
        <div ref={bottom}/>
      </div>
      {error && <p className="error" role="alert">{error}</p>}
      {retry && !running && <div className="assistant-retry"><span>待确认问题：{retry.message}</span><button className="outline" disabled={busy} onClick={e => send(e, retry)}>原问题重试</button><button className="text-button" disabled={busy} onClick={() => { setReload(n => n + 1); if (id) api(`/assistant/sessions/${id}`).then(setData).catch(e => setError(explain(e))) }}>刷新对话</button></div>}
      <form className="assistant-composer" onSubmit={send}><label htmlFor="ticket-assistant-message">你的问题</label><div><textarea id="ticket-assistant-message" aria-label={localLife?"向生活助手提问":"向票务助手提问"} value={message} maxLength={1000} rows={2} disabled={busy || running || Boolean(id && !data)} onKeyDown={e=>{if(e.key==='Enter'&&!e.shiftKey&&!e.nativeEvent.isComposing){e.preventDefault();send(e)}}} onChange={e => setMessage(e.target.value)} placeholder={localLife?"说说想吃什么、预算多少，或你遇到的问题…":"例如：明天预算100元，推荐音乐活动"}/><button className="primary" disabled={busy || running || Boolean(id && !data) || !message.trim()}><Send size={17}/>{running ? '查询中' : busy ? '发送中' : '发送'}</button></div><small className="muted">{localLife?"Enter 发送 · Shift + Enter 换行 · 不要发送密码或核销码":"余票和价格可能变化，以提交抢票时的校验结果为准。"}</small></form>
    </>}
  </section>
}
