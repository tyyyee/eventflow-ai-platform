import React, { createContext, useContext, useEffect, useRef, useState } from 'react'
import { X, ArrowRight, MapPin, Star, Heart, RefreshCw } from 'lucide-react'
import { api, explain, ApiError } from '../api'

export const LifeContext = createContext(null)
export const useLife = () => useContext(LifeContext)
export const money = n => n == null ? '暂无' : (Number(n) / 100).toFixed(2).replace(/\.00$/, '')
export const when = n => n ? new Date(n).toLocaleString('zh-CN', { timeZone:'Asia/Shanghai', month:'numeric', day:'numeric', hour:'2-digit', minute:'2-digit', hour12:false }) : '暂无'
export const statuses = {AVAILABLE:'可使用',USED:'已使用',EXPIRED:'已过期',VOID:'已作废',PUBLISHED:'已发布',DRAFT:'草稿',PAUSED:'暂停发放',ENDED:'已结束',CLOSED:'已下架',HIDDEN:'已隐藏',OPEN:'待处理',IN_PROGRESS:'处理中',RESOLVED:'已解决',REJECTED:'未采纳'}
export function useLoad(path, revision=0) {
  const [data,setData]=useState(null),[error,setError]=useState(''),[reload,setReload]=useState(0)
  useEffect(()=>{setData(null);setError(''); if(!path)return;const c=new AbortController();api(path,{signal:c.signal}).then(result=>{if(!c.signal.aborted)setData(result)}).catch(e=>{if(!c.signal.aborted)setError(explain(e))});return()=>c.abort()},[path,revision,reload])
  return {data,error,setData,retry:()=>setReload(n=>n+1)}
}
export function Load({value,children}) { return value.error?<div className="ll-empty"><p role="alert">{value.error}</p><button onClick={value.retry}><RefreshCw size={16}/>重新加载</button></div>:value.data==null?<div className="ll-skeleton" aria-label="正在加载"><i/><i/><i/></div>:children(value.data) }
export function Link({to,children,...rest}) { const {go}=useLife();return <a href={to} {...rest} onClick={e=>{if(e.button||e.metaKey||e.ctrlKey||e.shiftKey)return;e.preventDefault();go(to)}}>{children}</a> }
export function Modal({title,close,children,wide=false}) { const ref=useRef();useEffect(()=>{const active=document.activeElement;ref.current.showModal();const old=document.body.style.overflow;document.body.style.overflow='hidden';return()=>{document.body.style.overflow=old;active?.focus()}},[]);return <dialog className={`ll-modal ${wide?'wide':''}`} ref={ref} aria-label={title} onCancel={e=>{e.preventDefault();close()}} onClick={e=>{if(e.target===ref.current)close()}}><header><h2>{title}</h2><button className="ll-icon" onClick={close} aria-label="关闭"><X/></button></header>{children}</dialog> }
export function Empty({title='这里暂时还没有内容',text,children}) {return <div className="ll-empty"><span className="ll-empty-art">✳</span><h3>{title}</h3><p>{text}</p>{children}</div>}
export function Pager({data,change}) {return data.total>data.pageSize&&<div className="ll-pager"><button disabled={data.page<=1} onClick={()=>change(data.page-1)}>上一页</button><span>{data.page} / {Math.ceil(data.total/data.pageSize)}</span><button disabled={data.page*data.pageSize>=data.total} onClick={()=>change(data.page+1)}>下一页</button></div>}
export function Heading({eyebrow,title,text,children}) {return <div className="ll-heading"><div>{eyebrow&&<small>{eyebrow}</small>}<h1>{title}</h1>{text&&<p>{text}</p>}</div>{children}</div>}
export function ShopCard({shop,refresh}) {
  const {write,session}=useLife();const [busy,setBusy]=useState(false),[saved,setSaved]=useState(shop.favorite)
  async function favorite(){if(busy)return;setBusy(true);try{const x=await write(`/account/favorites/SHOP/${shop.id}`,saved?'DELETE':'PUT');if(x){setSaved(x.favorite);refresh?.()}}finally{setBusy(false)}}
  return <article className="ll-shop-card"><Link to={`/shops/${shop.id}`} className="ll-card-photo"><img src={shop.coverPath} alt={`${shop.name} · 示意图`} loading="lazy"/><span className={shop.open?'ll-open':'ll-closed'}>{shop.open?'营业中':'休息中'}</span></Link><button className={`ll-save ${saved&&session?'active':''}`} onClick={favorite} disabled={busy} aria-label={`${saved?'取消收藏':'收藏'}${shop.name}`} aria-pressed={!!saved&&!!session}><Heart size={18} fill={saved&&session?'currentColor':'none'}/></button><div className="ll-card-content"><div className="ll-eyebrow">{shop.category} · {shop.campus}</div><Link to={`/shops/${shop.id}`}><h3>{shop.name}</h3></Link><div className="ll-rating"><Star size={14} fill="currentColor"/><b>{shop.rating??'暂无评分'}</b><span>{shop.reviewCount} 条评价</span><span className="ll-price">人均 ¥{money(shop.avgPriceCents)}</span></div><p className="ll-desc">{shop.description}</p><div className="ll-card-tags">{shop.tags?.slice(0,3).map(t=><span key={t}>{t}</span>)}</div><div className="ll-card-bottom"><span><MapPin size={12}/>{shop.area} · {shop.distanceMeters>=1000?`${(shop.distanceMeters/1000).toFixed(1)}km`:`${shop.distanceMeters??'—'}m`}</span><ArrowRight size={16}/></div></div></article>
}
export async function upload(file,purpose,session) {
  if(file.size>5*1024*1024)throw new Error('图片不能超过 5 MB。')
  if(!['image/jpeg','image/png','image/webp'].includes(file.type))throw new Error('请选择 JPG、PNG 或 WebP 图片。')
  const bitmap=await createImageBitmap(file)
  if(bitmap.width>8000||bitmap.height>8000||bitmap.width*bitmap.height>20000000){bitmap.close();throw new Error('图片尺寸过大，请选择 8000 像素以内、2000 万像素以下的图片。')}
  // Browsers decode WebP; normalize it to PNG for the Java image reader.
  if(file.type==='image/webp'){const canvas=document.createElement('canvas');canvas.width=bitmap.width;canvas.height=bitmap.height;canvas.getContext('2d').drawImage(bitmap,0,0);file=await new Promise(resolve=>canvas.toBlob(resolve,'image/png'))}bitmap.close()
  const form=new FormData();form.append('file',file,'photo');form.append('purpose',purpose)
  const r=await fetch('/v2/media',{method:'POST',credentials:'same-origin',headers:{'X-CSRF-Token':session.csrfToken,'X-Account-ID':session.user.id},body:form,signal:AbortSignal.timeout(20000)});const value=await r.json();if(!r.ok)throw new ApiError(r.status,value.error);return value
}
export function ImagePicker({purpose='REVIEW',ids,onChange,max=6}) {const {session,notice}=useLife();const [busy,setBusy]=useState(false);async function choose(e){const files=[...e.target.files];e.target.value='';if(ids.length+files.length>max){notice(`最多选择 ${max} 张图片`);return}setBusy(true);const added=[];try{for(const f of files)added.push((await upload(f,purpose,session)).id)}catch(e){notice(e instanceof ApiError?explain(e):e.message)}finally{onChange([...ids,...added]);setBusy(false)}}return <div className="ll-image-picker"><div className="ll-thumbnails">{ids.map(id=><span key={id}><img src={`/v2/media/${id}?thumbnail=true`} alt="已选择的图片"/><button type="button" aria-label="移除图片" onClick={()=>onChange(ids.filter(x=>x!==id))}>×</button></span>)}</div>{ids.length<max&&<label className="ll-upload">{busy?'上传中…':'+ 添加图片'}<input type="file" multiple={max>1} accept="image/jpeg,image/png,image/webp" disabled={busy} onChange={choose}/></label>}<small>JPG / PNG / WebP，每张不超过 5 MB。发布前仅自己可见。</small></div>}
