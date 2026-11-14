import express from "express";
import cors from "cors";
import http from "node:http";
import path from "node:path";
import fs from "node:fs";
import { Server } from "socket.io";
import multer from "multer";
import { randomUUID, scryptSync, timingSafeEqual } from "node:crypto";

const app=express(), server=http.createServer(app);
const PORT=Number(process.env.PORT||10000);
const MAX_UPLOAD_BYTES=Number(process.env.MAX_UPLOAD_BYTES||512*1024*1024);
const uploadDir=process.env.UPLOAD_DIR||path.resolve("uploads");
const dataDir=process.env.DATA_DIR||path.resolve("data");
fs.mkdirSync(uploadDir,{recursive:true}); fs.mkdirSync(dataDir,{recursive:true});
const file=n=>path.join(dataDir,n);
function load(n,fallback){try{return JSON.parse(fs.readFileSync(file(n),"utf8"))}catch{return fallback}}
const users=load("users.json",{}), sessions=load("sessions.json",{}), conversations=load("conversations.json",{}), messages=load("messages.json",{}), statuses=load("statuses.json",{}), statusViews=load("status-views.json",{});
function save(n,obj){const p=file(n),tmp=p+".tmp";fs.writeFileSync(tmp,JSON.stringify(obj));fs.renameSync(tmp,p)}
function clean(s,max=80){return typeof s==="string"?s.trim().slice(0,max):""}
function publicUser(u){return {id:u.id,name:u.name,username:u.username,about:u.about||"",avatarUrl:u.avatarUrl||"",createdAt:u.createdAt}}
function auth(req,res,next){const token=(req.headers.authorization||"").replace(/^Bearer\s+/i,"");const uid=sessions[token];if(!uid||!users[uid])return res.status(401).json({error:"Inicia sesión para continuar"});req.user=users[uid];req.token=token;next()}
function member(conv,uid){return conv&&Array.isArray(conv.members)&&conv.members.includes(uid)}
function visibleConversations(uid){return Object.values(conversations).filter(c=>member(c,uid)).map(c=>({...c,unread:0,lastMessage:(messages[c.id]||[]).at(-1)||null}))}
app.use(cors({origin:process.env.CORS_ORIGIN?.split(",")||"*"}));app.use(express.json({limit:"2mb"}));
app.get("/health",(_q,r)=>r.json({ok:true,service:"CHAT PRO",version:"2.0.0",features:["accounts","direct-messages","groups","media","history","profiles","statuses-24h","status-views"]}));
app.post("/api/register",(q,r)=>{const name=clean(q.body?.name,40),username=clean(q.body?.username,24).toLowerCase(),password=typeof q.body?.password==="string"?q.body.password:"";if(name.length<2||!/^[a-z0-9_.-]{3,24}$/.test(username)||password.length<8)return r.status(400).json({error:"Usa nombre, usuario de 3-24 caracteres y contraseña de al menos 8"});if(Object.values(users).some(u=>u.username===username))return r.status(409).json({error:"Ese usuario ya existe"});const id=randomUUID(),salt=randomUUID(),hash=scryptSync(password,salt,64).toString("hex");users[id]={id,name,username,salt,hash,about:"",avatarUrl:"",createdAt:new Date().toISOString()};save("users.json",users);const token=randomUUID()+randomUUID();sessions[token]=id;save("sessions.json",sessions);r.status(201).json({token,user:publicUser(users[id])})});
app.post("/api/login",(q,r)=>{const username=clean(q.body?.username,24).toLowerCase(),password=typeof q.body?.password==="string"?q.body.password:"";const u=Object.values(users).find(x=>x.username===username);if(!u)return r.status(401).json({error:"Usuario o contraseña incorrectos"});const hash=scryptSync(password,u.salt,64);if(!timingSafeEqual(hash,Buffer.from(u.hash,"hex")))return r.status(401).json({error:"Usuario o contraseña incorrectos"});const token=randomUUID()+randomUUID();sessions[token]=u.id;save("sessions.json",sessions);r.json({token,user:publicUser(u)})});
app.post("/api/logout",auth,(q,r)=>{delete sessions[q.token];save("sessions.json",sessions);r.json({ok:true})});
app.get("/api/me",auth,(q,r)=>r.json({user:publicUser(q.user)}));
app.patch("/api/me",auth,(q,r)=>{q.user.name=clean(q.body?.name,40)||q.user.name;q.user.about=clean(q.body?.about,140);q.user.avatarUrl=clean(q.body?.avatarUrl,500);save("users.json",users);r.json({user:publicUser(q.user)})});
app.get("/api/users/search",auth,(q,r)=>{const term=clean(q.query.q,40).toLowerCase();if(term.length<2)return r.json({users:[]});r.json({users:Object.values(users).filter(u=>u.id!==q.user.id&&(u.username.includes(term)||u.name.toLowerCase().includes(term))).slice(0,30).map(publicUser)})});
app.get("/api/conversations",auth,(q,r)=>r.json({conversations:visibleConversations(q.user.id)}));
app.post("/api/conversations",auth,(q,r)=>{const ids=Array.isArray(q.body?.members)?q.body.members.filter(id=>users[id]):[];const names=Array.isArray(q.body?.usernames)?q.body.usernames.map(x=>clean(x,24).toLowerCase()):[];for(const username of names){const found=Object.values(users).find(u=>u.username===username);if(found)ids.push(found.id)}const members=[...new Set([q.user.id,...ids])];if(members.length<2)return r.status(400).json({error:"Selecciona al menos otro usuario"});const group=members.length>2||q.body?.type==="group";const id=randomUUID();const c={id,type:group?"group":"direct",title:clean(q.body?.title,60)||(group?"Nuevo grupo":""),members,createdBy:q.user.id,createdAt:new Date().toISOString()};conversations[id]=c;messages[id]=[];save("conversations.json",conversations);save("messages.json",messages);r.status(201).json({conversation:c})});
app.get("/api/conversations/:id/messages",auth,(q,r)=>{const c=conversations[q.params.id];if(!member(c,q.user.id))return r.sendStatus(403);r.json({messages:(messages[c.id]||[]).slice(-500)})});
app.post("/api/conversations/:id/members",auth,(q,r)=>{const c=conversations[q.params.id];if(!member(c,q.user.id)||c.type!=="group"||c.createdBy!==q.user.id)return r.sendStatus(403);const uid=q.body?.userId;if(!users[uid])return r.status(404).json({error:"Usuario no encontrado"});if(!c.members.includes(uid))c.members.push(uid);save("conversations.json",conversations);r.json({conversation:c})});

// Estados de usuario: texto o archivo multimedia previamente subido.
// Cada estado caduca a las 24 horas y registra las visualizaciones.
app.get("/api/statuses",auth,(q,r)=>{
 const now=Date.now();
 const active=Object.values(statuses).filter(s=>new Date(s.expiresAt).getTime()>now);
 const feed=active.map(s=>({...s,user:users[s.userId]?publicUser(users[s.userId]):null,
   viewedBy:(statusViews[s.id]||[]).length,
   viewed:(statusViews[s.id]||[]).some(v=>v.userId===q.user.id)}))
   .filter(s=>s.user).sort((a,b)=>new Date(a.createdAt)-new Date(b.createdAt));
 r.json({statuses:feed});
});
app.post("/api/statuses",auth,(q,r)=>{
 const caption=clean(q.body?.caption,700), media=q.body?.media&&typeof q.body.media==="object"?q.body.media:null;
 if(!caption&&!media)return r.status(400).json({error:"Agrega texto o un archivo"});
 if(media&&!clean(media.downloadPath,500).startsWith("/api/uploads/"))return r.status(400).json({error:"Archivo multimedia no válido"});
 const now=new Date(),id=randomUUID();
 const item={id,userId:q.user.id,caption,media:media?{name:clean(media.name,180)||"Archivo",mimeType:clean(media.mimeType,100),size:Number(media.size)||0,downloadPath:clean(media.downloadPath,500)}:null,createdAt:now.toISOString(),expiresAt:new Date(now.getTime()+24*60*60*1000).toISOString()};
 statuses[id]=item;save("statuses.json",statuses);r.status(201).json({status:{...item,user:publicUser(q.user),viewedBy:0,viewed:false}});
});
app.post("/api/statuses/:id/views",auth,(q,r)=>{
 const s=statuses[q.params.id];if(!s||new Date(s.expiresAt).getTime()<=Date.now())return r.sendStatus(404);
 if(s.userId===q.user.id)return r.status(400).json({error:"No puedes registrar tu propia visualización"});
 statusViews[s.id]??=[];
 if(!statusViews[s.id].some(v=>v.userId===q.user.id)){statusViews[s.id].push({userId:q.user.id,viewedAt:new Date().toISOString()});save("status-views.json",statusViews)}
 r.json({ok:true});
});
app.get("/api/statuses/:id/views",auth,(q,r)=>{
 const s=statuses[q.params.id];if(!s||s.userId!==q.user.id)return r.sendStatus(404);
 const viewers=(statusViews[s.id]||[]).map(v=>({...v,user:users[v.userId]?publicUser(users[v.userId]):null})).filter(v=>v.user);
 r.json({viewers});
});
app.delete("/api/statuses/:id",auth,(q,r)=>{
 const s=statuses[q.params.id];if(!s||s.userId!==q.user.id)return r.sendStatus(404);
 delete statuses[s.id];delete statusViews[s.id];save("statuses.json",statuses);save("status-views.json",statusViews);r.json({ok:true});
});

app.post("/api/uploads",auth,multer({storage:multer.diskStorage({destination:(_q,_f,cb)=>cb(null,uploadDir),filename:(_q,f,cb)=>cb(null,randomUUID()+path.extname(f.originalname).toLowerCase())}),limits:{fileSize:MAX_UPLOAD_BYTES,files:1},fileFilter:(_q,f,cb)=>{const ext=path.extname(f.originalname).toLowerCase();cb(([".mp3",".wav",".m4a",".flac"].includes(ext)||/^(image|video|application)\//.test(f.mimetype))?null:new Error("Tipo de archivo no permitido"),true)}}).single("file"),(q,r)=>{if(!q.file)return r.status(400).json({error:"Falta el archivo"});r.status(201).json({id:path.basename(q.file.filename,path.extname(q.file.filename)),name:q.file.originalname,size:q.file.size,mimeType:q.file.mimetype,downloadPath:"/api/uploads/"+encodeURIComponent(q.file.filename)})});
function canReadUpload(uid,name){
 const encoded=encodeURIComponent(name),plain="/api/uploads/"+name,escaped="/api/uploads/"+encoded;
 // Profile photos are visible only to authenticated app users.
 if(Object.values(users).some(u=>typeof u.avatarUrl==="string"&&(u.avatarUrl.endsWith(plain)||u.avatarUrl.endsWith(escaped))))return true;
 // Conversation attachments are visible only to members of that conversation.
 for(const conv of Object.values(conversations)){
  if(!member(conv,uid))continue;
  for(const msg of messages[conv.id]||[]){
   const p=msg.media?.downloadPath;
   if(p===plain||p===escaped)return true;
  }
 }
 // Active statuses are shown in the authenticated status feed.
 const now=Date.now();
 for(const s of Object.values(statuses)){
  if(new Date(s.expiresAt).getTime()<=now)continue;
  const p=s.media?.downloadPath;
  if(p===plain||p===escaped)return true;
 }
 return false;
}
app.get("/api/uploads/:filename",auth,(q,r)=>{
 const name=path.basename(q.params.filename),full=path.join(uploadDir,name);
 if(!fs.existsSync(full))return r.sendStatus(404);
 if(!canReadUpload(q.user.id,name))return r.sendStatus(403);
 r.download(full);
});
const io=new Server(server,{cors:{origin:process.env.CORS_ORIGIN?.split(",")||"*",methods:["GET","POST"]},maxHttpBufferSize:1e6});
io.use((socket,next)=>{const token=socket.handshake.auth?.token;const uid=sessions[token];if(uid&&users[uid]){socket.data.user=users[uid];return next()}if(process.env.ALLOW_LEGACY_ROOMS!=="false")return next();next(new Error("AUTH_REQUIRED"))});
io.on("connection",socket=>{
 const user=socket.data.user;
 socket.on("conversation:join",({conversationId}={})=>{const c=conversations[conversationId];if(!user||!member(c,user.id))return;for(const room of socket.rooms)if(room!==socket.id)socket.leave(room);socket.join(conversationId);socket.data.conversationId=conversationId;socket.emit("conversation:history",(messages[conversationId]||[]).slice(-500));socket.to(conversationId).emit("presence:joined",{user:publicUser(user)})});
 socket.on("conversation:send",payload=>{const id=socket.data.conversationId,c=conversations[id];if(!user||!member(c,user.id)||!payload||typeof payload!=="object")return;const text=clean(payload.text,4000);let media=null;if(payload.media&&typeof payload.media==="object"){const m=payload.media,p=clean(m.downloadPath,500);if(!p.startsWith("/api/uploads/"))return;media={name:clean(m.name,180)||"Archivo",mimeType:clean(m.mimeType,100)||"application/octet-stream",size:Number(m.size)||0,downloadPath:p}}if(!text&&!media)return;const item={id:randomUUID(),conversationId:id,user:publicUser(user),text,media,createdAt:new Date().toISOString()};messages[id]??=[];messages[id].push(item);messages[id]=messages[id].slice(-500);save("messages.json",messages);io.to(id).emit("conversation:message",item)});
 // Legacy room support for the existing test screen during migration.
 socket.on("room:join",({roomId,displayName}={})=>{if(process.env.ALLOW_LEGACY_ROOMS==="false"||!roomId)return;socket.join(roomId);socket.data.legacyRoom=roomId;socket.data.displayName=clean(displayName,40)||"Usuario";socket.emit("room:history",messages[roomId]||[])});
 socket.on("message:send",payload=>{const room=socket.data.legacyRoom;if(!room)return;const item={id:randomUUID(),roomId:room,displayName:socket.data.displayName||"Usuario",text:clean(payload?.text,4000),media:payload?.media||null,createdAt:new Date().toISOString()};messages[room]??=[];messages[room].push(item);messages[room]=messages[room].slice(-500);try{save("messages.json",messages)}catch(e){console.error(e)}io.to(room).emit("message:new",item)}); 
 socket.on("disconnect",()=>{if(socket.data.conversationId)socket.to(socket.data.conversationId).emit("presence:left",{user:user?publicUser(user):null})});
});
app.use((err,_q,r,_next)=>r.status(err instanceof multer.MulterError?413:400).json({error:err.message||"Error al procesar la solicitud"}));
server.listen(PORT,"0.0.0.0",()=>console.log("CHAT PRO 2 server listening on "+PORT));
