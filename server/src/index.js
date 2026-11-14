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
const users=load("users.json",{}), sessions=load("sessions.json",{}), conversations=load("conversations.json",{}), messages=load("messages.json",{});
function save(n,obj){const p=file(n),tmp=p+".tmp";fs.writeFileSync(tmp,JSON.stringify(obj));fs.renameSync(tmp,p)}
function clean(s,max=80){return typeof s==="string"?s.trim().slice(0,max):""}
function publicUser(u){return {id:u.id,name:u.name,username:u.username,about:u.about||"",avatarUrl:u.avatarUrl||"",createdAt:u.createdAt}}
function auth(req,res,next){const token=(req.headers.authorization||"").replace(/^Bearer\s+/i,"");const uid=sessions[token];if(!uid||!users[uid])return res.status(401).json({error:"Inicia sesión para continuar"});req.user=users[uid];req.token=token;next()}
function member(conv,uid){return conv&&Array.isArray(conv.members)&&conv.members.includes(uid)}
function visibleConversations(uid){return Object.values(conversations).filter(c=>member(c,uid)).map(c=>({...c,unread:0,lastMessage:(messages[c.id]||[]).at(-1)||null}))}
app.use(cors({origin:process.env.CORS_ORIGIN?.split(",")||"*"}));app.use(express.json({limit:"2mb"}));
app.get("/health",(_q,r)=>r.json({ok:true,service:"CHAT PRO",version:"2.0.0",features:["accounts","direct-messages","groups","media","history"]}));
app.post("/api/register",(q,r)=>{const name=clean(q.body?.name,40),username=clean(q.body?.username,24).toLowerCase(),password=typeof q.body?.password==="string"?q.body.password:"";if(name.length<2||!/^[a-z0-9_.-]{3,24}$/.test(username)||password.length<8)return r.status(400).json({error:"Usa nombre, usuario de 3-24 caracteres y contraseña de al menos 8"});if(Object.values(users).some(u=>u.username===username))return r.status(409).json({error:"Ese usuario ya existe"});const id=randomUUID(),salt=randomUUID(),hash=scryptSync(password,salt,64).toString("hex");users[id]={id,name,username,salt,hash,about:"",avatarUrl:"",createdAt:new Date().toISOString()};save("users.json",users);const token=randomUUID()+randomUUID();sessions[token]=id;save("sessions.json",sessions);r.status(201).json({token,user:publicUser(users[id])})});
app.post("/api/login",(q,r)=>{const username=clean(q.body?.username,24).toLowerCase(),password=typeof q.body?.password==="string"?q.body.password:"";const u=Object.values(users).find(x=>x.username===username);if(!u)return r.status(401).json({error:"Usuario o contraseña incorrectos"});const hash=scryptSync(password,u.salt,64);if(!timingSafeEqual(hash,Buffer.from(u.hash,"hex")))return r.status(401).json({error:"Usuario o contraseña incorrectos"});const token=randomUUID()+randomUUID();sessions[token]=u.id;save("sessions.json",sessions);r.json({token,user:publicUser(u)})});
app.post("/api/logout",auth,(q,r)=>{delete sessions[q.token];save("sessions.json",sessions);r.json({ok:true})});
app.get("/api/me",auth,(q,r)=>r.json({user:publicUser(q.user)}));
app.patch("/api/me",auth,(q,r)=>{q.user.name=clean(q.body?.name,40)||q.user.name;q.user.about=clean(q.body?.about,140);q.user.avatarUrl=clean(q.body?.avatarUrl,500);save("users.json",users);r.json({user:publicUser(q.user)})});
app.get("/api/users/search",auth,(q,r)=>{const term=clean(q.query.q,40).toLowerCase();if(term.length<2)return r.json({users:[]});r.json({users:Object.values(users).filter(u=>u.id!==q.user.id&&(u.username.includes(term)||u.name.toLowerCase().includes(term))).slice(0,30).map(publicUser)})});
app.get("/api/conversations",auth,(q,r)=>r.json({conversations:visibleConversations(q.user.id)}));
app.post("/api/conversations",auth,(q,r)=>{const ids=Array.isArray(q.body?.members)?q.body.members.filter(id=>users[id]):[];const members=[...new Set([q.user.id,...ids])];if(members.length<2)return r.status(400).json({error:"Selecciona al menos otro usuario"});const group=members.length>2||q.body?.type==="group";const id=randomUUID();const c={id,type:group?"group":"direct",title:clean(q.body?.title,60)||(group?"Nuevo grupo":""),members,createdBy:q.user.id,createdAt:new Date().toISOString()};conversations[id]=c;messages[id]=[];save("conversations.json",conversations);save("messages.json",messages);r.status(201).json({conversation:c})});
app.get("/api/conversations/:id/messages",auth,(q,r)=>{const c=conversations[q.params.id];if(!member(c,q.user.id))return r.sendStatus(403);r.json({messages:(messages[c.id]||[]).slice(-500)})});
app.post("/api/conversations/:id/members",auth,(q,r)=>{const c=conversations[q.params.id];if(!member(c,q.user.id)||c.type!=="group"||c.createdBy!==q.user.id)return r.sendStatus(403);const uid=q.body?.userId;if(!users[uid])return r.status(404).json({error:"Usuario no encontrado"});if(!c.members.includes(uid))c.members.push(uid);save("conversations.json",conversations);r.json({conversation:c})});
app.post("/api/uploads",multer({storage:multer.diskStorage({destination:(_q,_f,cb)=>cb(null,uploadDir),filename:(_q,f,cb)=>cb(null,randomUUID()+path.extname(f.originalname).toLowerCase())}),limits:{fileSize:MAX_UPLOAD_BYTES,files:1},fileFilter:(_q,f,cb)=>{const ext=path.extname(f.originalname).toLowerCase();cb(([".mp3",".wav",".m4a",".flac"].includes(ext)||/^(image|video|application)\//.test(f.mimetype))?null:new Error("Tipo de archivo no permitido"),true)}}).single("file"),(q,r)=>{if(!q.file)return r.status(400).json({error:"Falta el archivo"});r.status(201).json({id:path.basename(q.file.filename,path.extname(q.file.filename)),name:q.file.originalname,size:q.file.size,mimeType:q.file.mimetype,downloadPath:"/api/uploads/"+encodeURIComponent(q.file.filename)})});
app.get("/api/uploads/:filename",(q,r)=>{const name=path.basename(q.params.filename),full=path.join(uploadDir,name);if(!fs.existsSync(full))return r.sendStatus(404);r.download(full)});
const io=new Server(server,{cors:{origin:process.env.CORS_ORIGIN?.split(",")||"*",methods:["GET","POST"]},maxHttpBufferSize:1e6});
io.use((socket,next)=>{const token=socket.handshake.auth?.token;const uid=sessions[token];if(uid&&users[uid]){socket.data.user=users[uid];return next()}if(process.env.ALLOW_LEGACY_ROOMS!=="false")return next();next(new Error("AUTH_REQUIRED"))});
io.on("connection",socket=>{
 const user=socket.data.user;
 socket.on("conversation:join",({conversationId}={})=>{const c=conversations[conversationId];if(!user||!member(c,user.id))return;for(const room of socket.rooms)if(room!==socket.id)socket.leave(room);socket.join(conversationId);socket.data.conversationId=conversationId;socket.emit("conversation:history",(messages[conversationId]||[]).slice(-500));socket.to(conversationId).emit("presence:joined",{user:publicUser(user)})});
 socket.on("conversation:send",payload=>{const id=socket.data.conversationId,c=conversations[id];if(!user||!member(c,user.id)||!payload||typeof payload!=="object")return;const text=clean(payload.text,4000);let media=null;if(payload.media&&typeof payload.media==="object"){const m=payload.media,p=clean(m.downloadPath,500);if(!p.startsWith("/api/uploads/"))return;media={name:clean(m.name,180)||"Archivo",mimeType:clean(m.mimeType,100)||"application/octet-stream",size:Number(m.size)||0,downloadPath:p}}if(!text&&!media)return;const item={id:randomUUID(),conversationId:id,user:publicUser(user),text,media,createdAt:new Date().toISOString()};messages[id]??=[];messages[id].push(item);messages[id]=messages[id].slice(-500);save("messages.json",messages);io.to(id).emit("conversation:message",item)});
 // Legacy room support for the existing test screen during migration.
 socket.on("room:join",({roomId,displayName}={})=>{if(process.env.ALLOW_LEGACY_ROOMS==="false"||!roomId)return;socket.join(roomId);socket.data.legacyRoom=roomId;socket.data.displayName=clean(displayName,40)||"Usuario";socket.emit("room:history",messages[roomId]||[])});\n socket.on("message:send",payload=>{const room=socket.data.legacyRoom;if(!room)return;const item={id:randomUUID(),roomId:room,displayName:socket.data.displayName||"Usuario",text:clean(payload?.text,4000),media:payload?.media||null,createdAt:new Date().toISOString()};messages[room]??=[];messages[room].push(item);messages[room]=messages[room].slice(-500);try{save("messages.json",messages)}catch(e){console.error(e)}io.to(room).emit("message:new",item)}); 
 socket.on("disconnect",()=>{if(socket.data.conversationId)socket.to(socket.data.conversationId).emit("presence:left",{user:user?publicUser(user):null})});
});
app.use((err,_q,r,_next)=>r.status(err instanceof multer.MulterError?413:400).json({error:err.message||"Error al procesar la solicitud"}));
server.listen(PORT,"0.0.0.0",()=>console.log("CHAT PRO 2 server listening on "+PORT));
