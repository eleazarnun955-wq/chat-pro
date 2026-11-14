import express from "express";
import cors from "cors";
import http from "node:http";
import path from "node:path";
import fs from "node:fs";
import { Server } from "socket.io";
import multer from "multer";
import { randomUUID, scryptSync, timingSafeEqual } from "node:crypto";
import * as db from "./db.js";

const app=express(), server=http.createServer(app);
const PORT=Number(process.env.PORT||10000);
const MAX_UPLOAD_BYTES=Number(process.env.MAX_UPLOAD_BYTES||512*1024*1024);
const uploadDir=process.env.UPLOAD_DIR||path.resolve("uploads");
fs.mkdirSync(uploadDir,{recursive:true});
const clean=(s,max=80)=>typeof s==="string"?s.trim().slice(0,max):"";
const member=async (conversationId,uid)=>db.isMember(conversationId,uid);
const asyncRoute=fn=>(req,res,next)=>Promise.resolve(fn(req,res,next)).catch(next);
app.use(cors({origin:process.env.CORS_ORIGIN?.split(",")||"*"}));
app.use(express.json({limit:"2mb"}));
const auth=asyncRoute(async(req,res,next)=>{
 const token=(req.headers.authorization||"").replace(/^Bearer\\s+/i,"");
 const user=token?await db.getSessionUser(token):null;
 if(!user)return res.status(401).json({error:"Inicia sesión para continuar"});
 req.user=user;req.token=token;next();
});
app.get("/health",(_q,r)=>r.json({ok:true,service:"CHAT PRO",version:"2.1.0",database:"supabase",features:["accounts","direct-messages","groups","media","history","profiles","statuses-24h"]}));

app.post("/api/register",asyncRoute(async(q,r)=>{
 const name=clean(q.body?.name,40),username=clean(q.body?.username,24).toLowerCase(),password=typeof q.body?.password==="string"?q.body.password:"";
 if(name.length<2||!/^[a-z0-9_.-]{3,24}$/.test(username)||password.length<8)return r.status(400).json({error:"Usa nombre, usuario de 3-24 caracteres y contraseña de al menos 8"});
 if(await db.findUserByUsername(username))return r.status(409).json({error:"Ese usuario ya existe"});
 const id=randomUUID(),salt=randomUUID(),hash=scryptSync(password,salt,64).toString("hex");
 const profile=await db.registerUser({id,name,username,salt,hash});
 const token=randomUUID()+randomUUID();await db.createSession(token,id);
 r.status(201).json({token,user:db.publicUser(profile)});
}));
app.post("/api/login",asyncRoute(async(q,r)=>{
 const username=clean(q.body?.username,24).toLowerCase(),password=typeof q.body?.password==="string"?q.body.password:"";
 const u=await db.findUserByUsername(username);
 if(!u||!u.salt||!u.hash)return r.status(401).json({error:"Usuario o contraseña incorrectos"});
 const hash=scryptSync(password,u.salt,64);
 if(!timingSafeEqual(hash,Buffer.from(u.hash,"hex")))return r.status(401).json({error:"Usuario o contraseña incorrectos"});
 const token=randomUUID()+randomUUID();await db.createSession(token,u.id);
 r.json({token,user:db.publicUser(u)});
}));
app.post("/api/logout",auth,asyncRoute(async(q,r)=>{await db.deleteSession(q.token);r.json({ok:true})}));
app.get("/api/me",auth,(q,r)=>r.json({user:db.publicUser(q.user)}));
app.patch("/api/me",auth,asyncRoute(async(q,r)=>{
 const updated=await db.updateUser(q.user.id,{name:clean(q.body?.name,40),about:clean(q.body?.about,140),avatarUrl:clean(q.body?.avatarUrl,500)});
 r.json({user:db.publicUser(updated)});
}));
app.get("/api/users/search",auth,asyncRoute(async(q,r)=>{
 const term=clean(q.query.q,40).toLowerCase();if(term.length<2)return r.json({users:[]});
 r.json({users:await db.searchUsers(term,q.user.id)});
}));
app.get("/api/conversations",auth,asyncRoute(async(q,r)=>r.json({conversations:await db.listConversations(q.user.id)})));
app.post("/api/conversations",auth,asyncRoute(async(q,r)=>{
 const ids=Array.isArray(q.body?.members)?q.body.members.filter(x=>typeof x==="string"):[];
 const names=Array.isArray(q.body?.usernames)?q.body.usernames.map(x=>clean(x,24).toLowerCase()):[];
 for(const username of names){const found=await db.findUserByUsername(username);if(found)ids.push(found.id)}
 const valid=[];for(const id of new Set(ids)){if(await db.findUserById(id))valid.push(id)}
 const members=[...new Set([q.user.id,...valid])];
 if(members.length<2)return r.status(400).json({error:"Selecciona al menos otro usuario"});
 const group=members.length>2||q.body?.type==="group";
 const visibility=q.body?.visibility==="public"?"public":"private";
 const conversation=await db.createConversation({id:randomUUID(),type:group?"group":"direct",title:clean(q.body?.title,60)||(group?"Nuevo grupo":""),members,createdBy:q.user.id,visibility,description:clean(q.body?.description,500)});
 r.status(201).json({conversation});
}));
app.get("/api/conversations/:id/messages",auth,asyncRoute(async(q,r)=>{
 if(!await member(q.params.id,q.user.id))return r.sendStatus(403);
 r.json({messages:await db.listMessages(q.params.id)});
}));
app.post("/api/conversations/:id/members",auth,asyncRoute(async(q,r)=>{
 const c=await db.getConversation(q.params.id);
 if(!c||c.type!=="group"||c.created_by!==q.user.id||!await member(c.id,q.user.id))return r.sendStatus(403);
 const uid=q.body?.userId;if(!uid||!await db.findUserById(uid))return r.status(404).json({error:"Usuario no encontrado"});
 await db.addConversationMember(c.id,uid);r.json({ok:true});
}));

app.get("/api/statuses",auth,asyncRoute(async(q,r)=>{
 const rows=await db.listStatuses(),feed=[];
 for(const s of rows){
  const {data,error}=await db.supabase.from("status_views").select("viewer_id").eq("status_id",s.id);if(error)throw error;
  feed.push({id:s.id,userId:s.user_id,caption:s.caption,media:s.media_url?{name:"Estado",mimeType:s.media_type==="video"?"video/mp4":"image/*",downloadPath:s.media_url}:null,createdAt:s.created_at,expiresAt:s.expires_at,user:db.publicUser(s.profiles),viewedBy:data.length,viewed:data.some(v=>v.viewer_id===q.user.id)});
 }
 r.json({statuses:feed});
}));
app.post("/api/statuses",auth,asyncRoute(async(q,r)=>{
 const caption=clean(q.body?.caption,700),media=q.body?.media&&typeof q.body.media==="object"?q.body.media:null;
 if(!caption&&!media)return r.status(400).json({error:"Agrega texto o un archivo"});
 if(media&&!clean(media.downloadPath,500).startsWith("/api/uploads/"))return r.status(400).json({error:"Archivo multimedia no válido"});
 const s=await db.createStatus({id:randomUUID(),userId:q.user.id,caption,media});
 r.status(201).json({status:{id:s.id,userId:s.user_id,caption:s.caption,media:media?{...media}:null,createdAt:s.created_at,expiresAt:s.expires_at,user:db.publicUser(q.user),viewedBy:0,viewed:false}});
}));
app.post("/api/statuses/:id/views",auth,asyncRoute(async(q,r)=>{
 const {data:s,error}=await db.supabase.from("statuses").select("id,user_id,expires_at").eq("id",q.params.id).maybeSingle();if(error)throw error;
 if(!s||new Date(s.expires_at).getTime()<=Date.now())return r.sendStatus(404);
 if(s.user_id===q.user.id)return r.status(400).json({error:"No puedes registrar tu propia visualización"});
 await db.recordStatusView(s.id,q.user.id);r.json({ok:true});
}));
app.get("/api/statuses/:id/views",auth,asyncRoute(async(q,r)=>{
 const {data:s,error}=await db.supabase.from("statuses").select("id,user_id").eq("id",q.params.id).maybeSingle();if(error)throw error;
 if(!s||s.user_id!==q.user.id)return r.sendStatus(404);
 const rows=await db.getStatusViewers(s.id),viewers=rows.map(v=>({viewedAt:v.viewed_at,user:db.publicUser(v.profiles)}));
 r.json({viewers});
}));
app.delete("/api/statuses/:id",auth,asyncRoute(async(q,r)=>{
 await db.deleteStatus(q.params.id,q.user.id);r.json({ok:true});
}));

const storage=multer.diskStorage({destination:(_q,_f,cb)=>cb(null,uploadDir),filename:(_q,f,cb)=>cb(null,randomUUID()+path.extname(f.originalname).toLowerCase())});
app.post("/api/uploads",auth,multer({storage,limits:{fileSize:MAX_UPLOAD_BYTES,files:1},fileFilter:(_q,f,cb)=>{
 const ext=path.extname(f.originalname).toLowerCase();
 cb(([".mp3",".wav",".m4a",".flac"].includes(ext)||/^(image|video|application)\\//.test(f.mimetype))?null:new Error("Tipo de archivo no permitido"));
}}).single("file"),async(q,r)=>{
 try{
  if(!q.file)return r.status(400).json({error:"Falta el archivo"});
  const objectName=randomUUID()+path.extname(q.file.originalname).toLowerCase();
  const buffer=fs.readFileSync(q.file.path);
  await db.uploadMedia(objectName,buffer,q.file.mimetype);
  fs.unlinkSync(q.file.path);
  r.status(201).json({id:path.basename(objectName,path.extname(objectName)),name:q.file.originalname,size:q.file.size,mimeType:q.file.mimetype,downloadPath:"/api/uploads/"+encodeURIComponent(objectName)});
 }catch(e){if(q.file?.path&&fs.existsSync(q.file.path))fs.unlinkSync(q.file.path);console.error(e);r.status(500).json({error:"No se pudo guardar el archivo en Supabase Storage"})}
});
async function canReadUpload(uid,name){
 const plain="/api/uploads/"+name,encoded="/api/uploads/"+encodeURIComponent(name);
 const {data:profile,error:pe}=await db.supabase.from("profiles").select("id").or("avatar_url.ilike.%"+plain+",avatar_url.ilike.%"+encoded).limit(1);if(pe)throw pe;if(profile?.length)return true;
 const {data:rows,error}=await db.supabase.from("messages").select("conversation_id,media");if(error)throw error;
 for(const m of rows||[]){const p=m.media?.downloadPath;if((p===plain||p===encoded)&&await member(m.conversation_id,uid))return true}
 const {data:statuses,error:se}=await db.supabase.from("statuses").select("user_id,media_url,expires_at");if(se)throw se;
 return (statuses||[]).some(s=>new Date(s.expires_at).getTime()>Date.now()&&(s.media_url===plain||s.media_url===encoded));
}
app.get("/api/uploads/:filename",auth,asyncRoute(async(q,r)=>{
 const name=path.basename(q.params.filename);
 if(!await canReadUpload(q.user.id,name))return r.sendStatus(403);
 const blob=await db.downloadMedia(name);
 r.setHeader("Content-Type",blob.type||"application/octet-stream");
 r.setHeader("Content-Disposition","attachment; filename="+JSON.stringify(name));
 r.send(Buffer.from(await blob.arrayBuffer()));
}));

const io=new Server(server,{cors:{origin:process.env.CORS_ORIGIN?.split(",")||"*",methods:["GET","POST"]},maxHttpBufferSize:1e6});
io.use(async(socket,next)=>{
 try{const token=socket.handshake.auth?.token,user=token?await db.getSessionUser(token):null;
  if(!user){if(process.env.ALLOW_LEGACY_ROOMS!=="false")return next();return next(new Error("AUTH_REQUIRED"))}
  socket.data.user=user;next();
 }catch(e){next(e)}
});
io.on("connection",socket=>{
 const user=socket.data.user;
 socket.on("conversation:join",async({conversationId}={})=>{
  try{if(!user||!await member(conversationId,user.id))return;
   for(const room of socket.rooms)if(room!==socket.id)socket.leave(room);
   socket.join(conversationId);socket.data.conversationId=conversationId;
   socket.emit("conversation:history",await db.listMessages(conversationId));
   socket.to(conversationId).emit("presence:joined",{user:db.publicUser(user)});
  }catch(e){socket.emit("chat:error",{message:"No se pudo cargar la conversación"})}
 });
 socket.on("conversation:send",async payload=>{
  try{const id=socket.data.conversationId;if(!user||!id||!await member(id,user.id)||!payload||typeof payload!=="object")return;
   const text=clean(payload.text,4000);let media=null;
   if(payload.media&&typeof payload.media==="object"){const m=payload.media,p=clean(m.downloadPath,500);if(!p.startsWith("/api/uploads/"))return;media={name:clean(m.name,180)||"Archivo",mimeType:clean(m.mimeType,100)||"application/octet-stream",size:Number(m.size)||0,downloadPath:p}}
   if(!text&&!media)return;
   const item=await db.createMessage({id:randomUUID(),conversationId:id,senderId:user.id,text,media});
   io.to(id).emit("conversation:message",item);
  }catch(e){socket.emit("chat:error",{message:"No se pudo enviar el mensaje"})}
 });
 socket.on("room:join",({roomId}={})=>{if(process.env.ALLOW_LEGACY_ROOMS!=="false"||!roomId)return;});
 socket.on("disconnect",()=>{if(socket.data.conversationId)socket.to(socket.data.conversationId).emit("presence:left",{user:user?db.publicUser(user):null})});
});
app.use((err,_q,r,_next)=>{console.error(err);r.status(err instanceof multer.MulterError?413:400).json({error:err.message||"Error al procesar la solicitud"})});
server.listen(PORT,"0.0.0.0",()=>console.log("CHAT PRO 2 server listening on "+PORT));
