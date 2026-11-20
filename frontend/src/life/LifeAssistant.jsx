import React from 'react'
import {Sparkles,MapPin,Ticket,MessageCircle,ArrowUpRight,ShieldCheck,Heart} from 'lucide-react'
import TicketAssistant from '../TicketAssistant'
import Preferences from './Preferences'
import {useLife,Link} from './shared'

export default function LifeAssistant(){
  const {session,openAuth,go,sessionFailure}=useLife()
  function ask(text){window.dispatchEvent(new CustomEvent('life-assistant-question',{detail:text}))}
  return <div className="ll-assistant-workspace"><aside className="ll-assistant-sidebar"><div className="ll-assistant-intro"><span className="ll-overline">YOUR EVERYDAY COMPANION</span><span className="ll-assistant-avatar"><Sparkles size={27}/></span><h1>生活里的小问题，<br/>一起想想办法。</h1><p>从下课后的第一顿饭，到一张没用上的优惠券。</p></div><div className="ll-assistant-skills"><button onClick={()=>ask('人均50元，推荐附近适合聚餐的店')}><MapPin size={18}/><span>帮我选家店<small>预算、距离和偏好一起考虑</small></span><ArrowUpRight size={16}/></button><button onClick={()=>ask('看看我的券包')}><Ticket size={18}/><span>看看优惠券<small>查询本人可用券和适用规则</small></span><ArrowUpRight size={16}/></button><button onClick={()=>ask('看看我的抢票结果')}><MessageCircle size={18}/><span>订单与售后<small>查订单、付款和退款进度</small></span><ArrowUpRight size={16}/></button></div><Preferences compact/><div className="ll-assistant-trust"><ShieldCheck size={17}/><p>商家信息来自平台资料。涉及领券、付款或提交反馈时，交给你确认。</p></div><Link to="/account/help" className="ll-help-link">帮助中心与人工反馈 <ArrowUpRight size={15}/></Link></aside><div className="ll-assistant-main"><TicketAssistant localLife session={session} openAuth={openAuth} openActivity={()=>go('/events')} openOrders={()=>go('/events/orders')} sessionFailure={sessionFailure}/></div></div>
}
