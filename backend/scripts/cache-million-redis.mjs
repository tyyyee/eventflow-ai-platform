import net from 'node:net';

function command(values){const parts=[Buffer.from(`*${values.length}\r\n`)];for(const value of values){const bytes=Buffer.from(String(value));parts.push(Buffer.from(`$${bytes.length}\r\n`),bytes,Buffer.from('\r\n'));}return Buffer.concat(parts);}
function parse(buffer,start=0){
    if(start>=buffer.length)return null;const end=buffer.indexOf('\r\n',start);if(end<0)return null;
    const kind=String.fromCharCode(buffer[start]),line=buffer.toString('utf8',start+1,end),next=end+2;
    if(kind==='+')return {value:line,next};if(kind==='-')throw new Error('Redis: '+line);
    if(kind===':')return {value:Number(line),next};
    if(kind==='$'){const length=Number(line);if(length===-1)return {value:null,next};if(buffer.length<next+length+2)return null;return {value:buffer.toString('utf8',next,next+length),next:next+length+2};}
    if(kind==='*'){const values=[];let cursor=next;for(let n=0;n<Number(line);n++){const item=parse(buffer,cursor);if(!item)return null;values.push(item.value);cursor=item.next;}return {value:values,next:cursor};}
    throw new Error('Invalid Redis response');
}
export function redisCommand(password,...values){return new Promise((resolve,reject)=>{
    const socket=net.createConnection({host:'127.0.0.1',port:36480});let buffer=Buffer.alloc(0);
    const fail=error=>{socket.destroy();reject(error);};socket.setTimeout(5000,()=>fail(new Error('Redis request timeout')));socket.on('error',fail);
    socket.on('connect',()=>socket.write(Buffer.concat([command(['AUTH',password]),command(values)])));
    socket.on('data',value=>{buffer=Buffer.concat([buffer,value]);if(buffer.length>8388608){fail(new Error('Redis response too large'));return;}try{const auth=parse(buffer);if(!auth)return;const result=parse(buffer,auth.next);if(result){socket.destroy();resolve(result.value);}}catch(error){fail(error);}});
});}
export function infoValues(text){return Object.fromEntries(text.split('\r\n').filter(row=>row&&!row.startsWith('#')).map(row=>{const at=row.indexOf(':');return [row.slice(0,at),row.slice(at+1)];}));}
