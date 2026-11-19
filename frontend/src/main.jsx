import React, { useEffect, useState } from 'react'
import { createRoot } from 'react-dom/client'
import { api } from './api'
import LegacyTickets from './LegacyTickets'
import LifeApp from './life/LifeApp'
function Boot(){ const [mode,setMode]=useState(null),[error,setError]=useState(false); useEffect(()=>{api('/capabilities').then(x=>setMode(x.localLife)).catch(()=>setError(true))},[]);return error?<div style={{padding:60}}>服务暂时无法连接。<button onClick={()=>location.reload()}>重试</button></div>:mode===null?<div style={{padding:60}}>正在打开校园生活…</div>:mode?<LifeApp/>:<LegacyTickets/> }
createRoot(document.getElementById('root')).render(<Boot/>)
