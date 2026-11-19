import express from "express";
import cors from "cors";
import http from "node:http";
import path from "node:path";
import fs from "node:fs";
import { Server } from "socket.io";
import multer from "multer";
import { resolveUploadExtension, isSupportedUpload, uploadMimeForExtension } from "./upload-policy.js";
import { randomUUID, scryptSync, timingSafeEqual } from "node:crypto";
import * as db from "./db.js";

const app=express(), server=http.createServer(app);
const PORT=Number(process.env.PORT||10000);
const MAX_UPLOAD_BYTES=Number(process.env.MAX_UPLOAD_BYTES||100*1024*1024);
const uploadDir=process.env.UPLOAD_DIR||path.resolve("uploads");
fs.mkdirSync(uploadDir,{recursive:true});
const clean=(s,max=80)=>typeof s==="string"?s.trim().slice(0,max):"";
const member=async (conversationId,uid)=>db.isMember(conversationId,uid);
const memberRole=async (conversationId,uid)=>{
 const {data,error}=await db.supabase.from("conversation_members").select("role")
  .eq("conversation_id",conversationId).eq("user_id",uid).maybeSingle();
 if(error)throw error;
 return data?.role||null;
};
const isGroupManager=role=>["owner","admin","moderator"].includes(role);
const asyncRoute=fn=>(req,res,next)=>Promise.resolve(fn(req,res,next)).catch(next);
// Per-process authentication throttling. Production multi-instance deployments should
// additionally enforce a shared limit at the edge or in a shared store.
const authAttempts=new Map();
function authRateLimit(bucket,limit=8,windowMs=15*60*1000){
 return (req,res,next)=>{
  const key=bucket+":"+String(req.ip||req.socket.remoteAddress||"unknown");
  const now=Date.now(),entry=authAttempts.get(key);
  if(!entry||now-entry.start>=windowMs){authAttempts.set(key,{start:now,count:1});return next();}
  if(entry.count>=limit)return res.status(429).json({error:"Demasiados intentos. Espera 15 minutos e inténtalo de nuevo."});
  entry.count++;next();
 };
}
app.use(cors({origin:process.env.CORS_ORIGIN?.split(",")||"*"}));
app.use(express.json({limit:"2mb"}));
const auth=asyncRoute(async(req,res,next)=>{
 const token=(req.headers.authorization||"").replace(/^Bearer\s+/i,"");
 const user=token?await db.getSessionUser(token):null;
 if(!user)return res.status(401).json({error:"Inicia sesión para continuar"});
 req.user=user;req.token=token;next();
});
app.get("/health",(_q,r)=>r.json({ok:true,service:"CHAT PRO",version:"2.2.0",database:"supabase",features:["accounts","direct-messages","groups","media","history","profiles","statuses-24h"]}));

app.post("/api/register",authRateLimit("register"),asyncRoute(async(q,r)=>{
 const name=clean(q.body?.name,40),email=clean(q.body?.email,254).toLowerCase(),password=typeof q.body?.password==="string"?q.body.password:"";
 if(name.length<2||!/^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/.test(email)||password.length<8||password.length>128)return r.status(400).json({error:"Ingresa un nombre, un correo válido y una contraseña de 8 a 128 caracteres"});
 if(await db.findUserByEmail(email))return r.status(409).json({error:"Ese correo ya está registrado"});
 const base=(email.split("@")[0].toLowerCase().replace(/[^a-z0-9_.-]/g,"").slice(0,15)||"usuario");
 const username=(base+"_"+randomUUID().replace(/-/g,"").slice(0,8)).slice(0,24);
 const id=randomUUID(),salt=randomUUID(),hash=scryptSync(password,salt,64).toString("hex");
 const profile=await db.registerUser({id,name,username,email,salt,hash});
 const token=randomUUID()+randomUUID();await db.createSession(token,id);
 r.status(201).json({token,user:db.publicUser(profile)});
}));
app.post("/api/login",authRateLimit("login"),asyncRoute(async(q,r)=>{
 const email=clean(q.body?.email,254).toLowerCase(),password=typeof q.body?.password==="string"?q.body.password:"";
 const u=await db.findUserByEmail(email);
 if(!u||!u.salt||!u.hash)return r.status(401).json({error:"Correo o contraseña incorrectos"});
 const hash=scryptSync(password,u.salt,64);
 if(!timingSafeEqual(hash,Buffer.from(u.hash,"hex")))return r.status(401).json({error:"Correo o contraseña incorrectos"});
 const token=randomUUID()+randomUUID();await db.createSession(token,u.id);
 r.json({token,user:db.publicUser(u)});
}));
app.post("/api/logout",auth,asyncRoute(async(q,r)=>{await db.deleteSession(q.token);r.json({ok:true})}));
app.post("/api/me/email",auth,asyncRoute(async(q,r)=>{
 const email=clean(q.body?.email,254).toLowerCase(),current=typeof q.body?.currentPassword==="string"?q.body.currentPassword:"";
 if(!/^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/.test(email))return r.status(400).json({error:"Ingresa un correo electrónico válido"});
 const stored=await db.findUserById(q.user.id);
 if(!stored?.salt||!stored?.hash)return r.status(400).json({error:"No se pudo validar la contraseña actual"});
 const candidate=scryptSync(current,stored.salt,64);
 if(!timingSafeEqual(candidate,Buffer.from(stored.hash,"hex")))return r.status(401).json({error:"La contraseña actual no es correcta"});
 try{await db.setUserEmail(q.user.id,email)}catch(error){if(error.status===409)return r.status(409).json({error:error.message});throw error}
 r.json({ok:true,message:"Correo vinculado. Ya puedes iniciar sesión con él."});
}));
app.post("/api/me/password",auth,asyncRoute(async(q,r)=>{
 const current=typeof q.body?.currentPassword==="string"?q.body.currentPassword:"";
 const next=typeof q.body?.newPassword==="string"?q.body.newPassword:"";
 if(next.length<8)return r.status(400).json({error:"La nueva contraseña debe tener al menos 8 caracteres"});
 const stored=await db.findUserById(q.user.id);
 if(!stored?.salt||!stored?.hash)return r.status(400).json({error:"No se pudo validar la contraseña actual"});
 const candidate=scryptSync(current,stored.salt,64);
 if(!timingSafeEqual(candidate,Buffer.from(stored.hash,"hex")))return r.status(401).json({error:"La contraseña actual no es correcta"});
 const salt=randomUUID(),hash=scryptSync(next,salt,64).toString("hex");
 await db.updatePassword(q.user.id,salt,hash,q.token);
 r.json({ok:true,message:"Contraseña actualizada. Las demás sesiones se cerraron."});
}));
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
app.post("/api/conversations/:id/clear-history",auth,asyncRoute(async(q,r)=>{
 if(!await member(q.params.id,q.user.id))return r.sendStatus(403);
 const hiddenCount=await db.clearConversationHistory(q.params.id,q.user.id);
 r.json({ok:true,hiddenCount,scope:"current-user"});
}));
app.post("/api/conversations",auth,asyncRoute(async(q,r)=>{
 const ids=Array.isArray(q.body?.members)?q.body.members.filter(x=>typeof x==="string"):[];
 const names=Array.isArray(q.body?.usernames)?q.body.usernames.map(x=>clean(x,24).toLowerCase()):[];
 for(const username of names){const found=await db.findUserByUsername(username);if(found)ids.push(found.id)}
 const valid=[];for(const id of new Set(ids)){if(await db.findUserById(id))valid.push(id)}
 const members=[...new Set([q.user.id,...valid])];
 if(members.length<(q.body?.type==="room"?1:2))return r.status(400).json({error:"Selecciona al menos otro usuario"});
 const room=q.body?.type==="room";
 const group=room||members.length>2||q.body?.type==="group";
 const visibility=room&&q.body?.visibility==="public"?"public":"private";
 if(!group && members.length===2){
  const other=members.find(x=>x!==q.user.id);
  const {data:blocked,error:blockError}=await db.supabase.from("user_blocks").select("blocker_id,blocked_id").or(`and(blocker_id.eq.${q.user.id},blocked_id.eq.${other}),and(blocker_id.eq.${other},blocked_id.eq.${q.user.id})`);
  if(blockError)throw blockError;
  if((blocked||[]).length)return r.status(403).json({error:"No se puede iniciar esta conversación por la configuración de bloqueo"});
 }
 const conversation=await db.createConversation({id:randomUUID(),type:room?"room":group?"group":"direct",title:clean(q.body?.title,60)||(group?"Nuevo grupo":"Chat privado"),members,createdBy:q.user.id,visibility,description:clean(q.body?.description,500)});
 r.status(201).json({conversation});
}));
app.get("/api/rooms",auth,asyncRoute(async(q,r)=>r.json({rooms:await db.listPublicRooms(q.user.id)})));
app.post("/api/rooms/:id/join",auth,asyncRoute(async(q,r)=>{
 const joined=await db.joinPublicRoom(q.params.id,q.user.id);
 if(!joined)return r.status(404).json({error:"La sala no existe o no es pública"});
 r.json({ok:true});
}));
app.patch("/api/messages/:id",auth,asyncRoute(async(q,r)=>{
 const message=await db.getMessage(q.params.id);
 if(!message||!await member(message.conversation_id,q.user.id))return r.sendStatus(404);
 if(message.sender_id!==q.user.id)return r.sendStatus(403);
 if(message.deleted_at)return r.status(409).json({error:"El mensaje ya fue eliminado"});
 if(Date.now()-new Date(message.created_at).getTime()>48*60*60*1000)return r.status(403).json({error:"Ya pasó el plazo de 48 horas para editarlo"});
 const body=typeof q.body?.text==="string"?q.body.text.trim():"";
 if(!body||body.length>5000)return r.status(400).json({error:"El mensaje debe tener entre 1 y 5000 caracteres"});
 const updated=await db.editMessage(message.id,q.user.id,body);
 io.to(message.conversation_id).emit("message:edited",{messageId:message.id,text:updated.body});
 r.json({ok:true,message:{id:updated.id,text:updated.body}});
}));
app.post("/api/messages/:id/delete",auth,asyncRoute(async(q,r)=>{
 const message=await db.getMessage(q.params.id);
 if(!message||!await member(message.conversation_id,q.user.id))return r.sendStatus(404);
 const mode=q.body?.mode;
 if(mode==="me"){
  await db.deleteMessageForUser(message.id,q.user.id);
  io.to(message.conversation_id).emit("message:hidden",{messageId:message.id,userId:q.user.id});
  return r.json({ok:true,mode});
 }
 if(mode==="everyone"){
  if(message.sender_id!==q.user.id)return r.status(403).json({error:"Solo quien envió el mensaje puede eliminarlo para todos"});
  if(message.deleted_at)return r.status(409).json({error:"El mensaje ya fue eliminado"});
  if(Date.now()-new Date(message.created_at).getTime()>48*60*60*1000)return r.status(403).json({error:"Ya pasó el plazo de 48 horas para eliminarlo para todos"});
  await db.deleteMessageForEveryone(message.id,q.user.id);
  io.to(message.conversation_id).emit("message:deleted",{messageId:message.id,deletedBy:q.user.id,deletedAt:new Date().toISOString()});
  return r.json({ok:true,mode});
 }
 return r.status(400).json({error:"Selecciona dónde eliminar el mensaje"});
}));
app.post("/api/messages/:id/reactions",auth,asyncRoute(async(q,r)=>{
 const message=await db.getMessage(q.params.id);
 if(!message||!await member(message.conversation_id,q.user.id))return r.sendStatus(404);
 const allowed=["👍","❤️","😂","🔥","👏","😮","😢","🎉"];
 const reaction=typeof q.body?.reaction==="string"?q.body.reaction:"";
 if(!allowed.includes(reaction))return r.status(400).json({error:"Reacción no permitida"});
 const reactions=await db.toggleMessageReaction(message.id,q.user.id,reaction);
 io.to(message.conversation_id).emit("message:reaction",{messageId:message.id,reactions});
 r.json({ok:true,reactions});
}));
app.post("/api/conversations/:id/read",auth,asyncRoute(async(q,r)=>{
 if(!await member(q.params.id,q.user.id))return r.sendStatus(403);
 await db.markConversationRead(q.params.id,q.user.id);
 const readAt=new Date().toISOString();
 io.to(q.params.id).emit("conversation:read",{userId:q.user.id,readAt});
 r.json({ok:true,readAt});
}));

app.get("/api/conversations/:id/search",auth,asyncRoute(async(q,r)=>{
 if(!await member(q.params.id,q.user.id))return r.sendStatus(403);
 const term=clean(q.query.q,100);
 if(term.length<2)return r.json({messages:[]});
 r.json({messages:await db.searchConversationMessages(q.params.id,q.user.id,term)});
}));
app.post("/api/messages/:id/forward",auth,asyncRoute(async(q,r)=>{
 const original=await db.getMessage(q.params.id);
 const targetId=clean(q.body?.conversationId,80);
 if(!original||!await member(original.conversation_id,q.user.id)||!await member(targetId,q.user.id))return r.sendStatus(404);
 if(original.deleted_at)return r.status(409).json({error:"No se puede reenviar un mensaje eliminado"});
 const target=await db.getConversation(targetId);
 if(!target)return r.sendStatus(404);
 if(target.type==="direct"){
  const {data:targetMembers,error:membersError}=await db.supabase.from("conversation_members").select("user_id").eq("conversation_id",targetId);
  if(membersError)throw membersError;
  const other=(targetMembers||[]).map(x=>x.user_id).find(id=>id!==q.user.id);
  if(other){
   const {data:blocked,error:blockError}=await db.supabase.from("user_blocks").select("blocker_id,blocked_id")
    .or(`and(blocker_id.eq.${q.user.id},blocked_id.eq.${other}),and(blocker_id.eq.${other},blocked_id.eq.${q.user.id})`);
   if(blockError)throw blockError;
   if((blocked||[]).length)return r.status(403).json({error:"No se puede reenviar a esta conversación por la configuración de bloqueo"});
  }
 }
 const {data:source,error}=await db.supabase.from("messages").select("body,media").eq("id",original.id).single();
 if(error)throw error;
 const forwarded=await db.createMessage({id:randomUUID(),conversationId:targetId,senderId:q.user.id,text:source.body||"",media:source.media||null});
 io.to(targetId).emit("conversation:message",forwarded);
 r.status(201).json({message:forwarded});
}));
app.post("/api/messages/:id/bookmark",auth,asyncRoute(async(q,r)=>{
 const message=await db.getMessage(q.params.id);
 if(!message||!await member(message.conversation_id,q.user.id))return r.sendStatus(404);
 const pinned=q.body?.pinned===true;
 const {data,error}=await db.supabase.from("message_bookmarks").upsert({user_id:q.user.id,message_id:message.id,is_pinned:pinned},{onConflict:"user_id,message_id"}).select().single();
 if(error)throw error;
 r.json({bookmark:data});
}));
app.delete("/api/messages/:id/bookmark",auth,asyncRoute(async(q,r)=>{
 const message=await db.getMessage(q.params.id);
 if(!message||!await member(message.conversation_id,q.user.id))return r.sendStatus(404);
 const {error}=await db.supabase.from("message_bookmarks").delete().eq("user_id",q.user.id).eq("message_id",message.id);
 if(error)throw error;r.json({ok:true});
}));
app.get("/api/bookmarks",auth,asyncRoute(async(q,r)=>{
 const {data,error}=await db.supabase.from("message_bookmarks").select("is_pinned,created_at,messages(id,conversation_id,body,media,created_at)").eq("user_id",q.user.id).order("created_at",{ascending:false}).limit(300);
 if(error)throw error;r.json({bookmarks:data||[]});
}));
app.post("/api/users/:id/block",auth,asyncRoute(async(q,r)=>{
 const target=q.params.id;if(target===q.user.id||!await db.findUserById(target))return r.status(400).json({error:"Usuario no válido"});
 const {error}=await db.supabase.from("user_blocks").upsert({blocker_id:q.user.id,blocked_id:target},{onConflict:"blocker_id,blocked_id"});
 if(error)throw error;r.json({ok:true});
}));
app.delete("/api/users/:id/block",auth,asyncRoute(async(q,r)=>{
 const {error}=await db.supabase.from("user_blocks").delete().eq("blocker_id",q.user.id).eq("blocked_id",q.params.id);
 if(error)throw error;r.json({ok:true});
}));
app.post("/api/users/:id/report",auth,asyncRoute(async(q,r)=>{
 const target=q.params.id,reason=clean(q.body?.reason,500);
 if(target===q.user.id||!await db.findUserById(target)||reason.length<3)return r.status(400).json({error:"Selecciona un motivo válido"});
 const {error}=await db.supabase.from("user_reports").insert({reporter_id:q.user.id,reported_id:target,reason});
 if(error)throw error;r.status(201).json({ok:true});
}));

app.get("/api/conversations/:id/messages",auth,asyncRoute(async(q,r)=>{
 if(!await member(q.params.id,q.user.id))return r.sendStatus(403);
 r.json({messages:await db.listMessages(q.params.id,q.user.id)});
}));
app.post("/api/conversations/:id/members",auth,asyncRoute(async(q,r)=>{
 const c=await db.getConversation(q.params.id);
 const role=c?await memberRole(c.id,q.user.id):null;
 if(!c||!["group","room"].includes(c.type)||(c.type==="room"&&c.visibility!=="private")||!isGroupManager(role))return r.sendStatus(403);
 const uid=clean(q.body?.userId,80);
 if(!uid||!await db.findUserById(uid))return r.status(404).json({error:"Usuario no encontrado"});
 await db.addConversationMember(c.id,uid);
 r.status(201).json({ok:true,role:"member"});
}));
app.delete("/api/conversations/:id/members/:userId",auth,asyncRoute(async(q,r)=>{
 const c=await db.getConversation(q.params.id);
 const actorRole=c?await memberRole(c.id,q.user.id):null;
 const targetId=clean(q.params.userId,80);
 const targetRole=c?await memberRole(c.id,targetId):null;
 if(!c||!["group","room"].includes(c.type)||!actorRole||!targetRole)return r.sendStatus(404);
 const selfLeaving=targetId===q.user.id;
 if(!selfLeaving&&!isGroupManager(actorRole))return r.sendStatus(403);
 if(targetRole==="owner")return r.status(409).json({error:"Transfiere la propiedad antes de retirar al propietario"});
 if(!selfLeaving&&actorRole==="moderator"&&targetRole!=="member")return r.sendStatus(403);
 const {error}=await db.supabase.from("conversation_members").delete()
  .eq("conversation_id",c.id).eq("user_id",targetId);
 if(error)throw error;
 io.to(c.id).emit("conversation:member_removed",{conversationId:c.id,userId:targetId,removedBy:q.user.id});
 r.json({ok:true});
}));
app.patch("/api/conversations/:id/members/:userId/role",auth,asyncRoute(async(q,r)=>{
 const c=await db.getConversation(q.params.id);
 if(!c||!["group","room"].includes(c.type)||await memberRole(c.id,q.user.id)!=="owner")return r.sendStatus(403);
 const targetId=clean(q.params.userId,80),nextRole=q.body?.role;
 if(!["admin","moderator","member"].includes(nextRole))return r.status(400).json({error:"Rol no válido"});
 if(!await member(c.id,targetId))return r.sendStatus(404);
 const {error}=await db.supabase.from("conversation_members").update({role:nextRole})
  .eq("conversation_id",c.id).eq("user_id",targetId);
 if(error)throw error;
 io.to(c.id).emit("conversation:member_role",{conversationId:c.id,userId:targetId,role:nextRole});
 r.json({ok:true,role:nextRole});
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

const storage=multer.diskStorage({
 destination:(_q,_f,cb)=>cb(null,uploadDir),
 filename:(_q,f,cb)=>cb(null,randomUUID()+resolveUploadExtension(f.originalname,f.mimetype))
});
const upload=multer({
 storage,
 limits:{fileSize:MAX_UPLOAD_BYTES,files:1},
 fileFilter:(_q,f,cb)=>{
  if(isSupportedUpload(f.originalname,f.mimetype))return cb(null,true);
  cb(new Error("Formato no compatible. Elige una imagen, video, audio o documento compatible."));
 }
}).single("file");
app.post("/api/uploads",auth,(q,r,next)=>upload(q,r,error=>{
 if(error)return next(error);
 next();
}),async(q,r)=>{
 try{
  if(!q.file)return r.status(400).json({error:"No se recibió ningún archivo. Vuelve a seleccionarlo e intenta otra vez."});
  const ext=resolveUploadExtension(q.file.originalname,q.file.mimetype);
  if(!ext)return r.status(415).json({error:"No se pudo identificar el formato del archivo."});
  const objectName=randomUUID()+ext;
  const contentType=uploadMimeForExtension(ext);
  const buffer=fs.readFileSync(q.file.path);
  await db.uploadMedia(objectName,buffer,contentType);
  fs.unlinkSync(q.file.path);
  r.status(201).json({id:path.basename(objectName,ext),name:q.file.originalname,size:q.file.size,mimeType:contentType,downloadPath:"/api/uploads/"+encodeURIComponent(objectName)});
 }catch(e){
  if(q.file?.path&&fs.existsSync(q.file.path))fs.unlinkSync(q.file.path);
  console.error("CHAT PRO upload failed:",e);
  r.status(500).json({error:"No se pudo guardar el archivo. Intenta de nuevo."});
 }
});
async function canReadUpload(uid,name){
 const mediaPath="/api/uploads/"+name;
 const {data:profile,error:pe}=await db.supabase.from("profiles").select("id").ilike("avatar_url","%"+mediaPath).limit(1);if(pe)throw pe;if(profile?.length)return true;
 const {data:rows,error}=await db.supabase.from("messages").select("conversation_id").eq("media->>downloadPath",mediaPath).limit(20);if(error)throw error;
 for(const m of rows||[])if(await member(m.conversation_id,uid))return true;
 const {data:status,error:se}=await db.supabase.from("statuses").select("id").eq("media_url",mediaPath).gt("expires_at",new Date().toISOString()).limit(1);if(se)throw se;
 return !!status?.length;
}
app.get("/api/uploads/:filename",auth,asyncRoute(async(q,r)=>{
 const name=path.basename(q.params.filename);
 if(!await canReadUpload(q.user.id,name))return r.sendStatus(403);
 const blob=await db.downloadMedia(name);
 const buffer=Buffer.from(await blob.arrayBuffer());
 const total=buffer.length;
 const inline=q.query.inline==="1";
 const safeName=name.replace(/["\\\r\n]/g,"_");
 r.setHeader("Content-Type",blob.type||"application/octet-stream");
 r.setHeader("Content-Disposition",(inline?"inline":"attachment")+"; filename="+JSON.stringify(safeName));
 r.setHeader("Accept-Ranges","bytes");
 r.setHeader("Cache-Control","private, no-store");
 const range=q.headers.range;
 if(range){
  const match=/^bytes=(\d*)-(\d*)$/.exec(range);
  if(!match)return r.status(416).set("Content-Range","bytes */"+total).end();
  let start=match[1]?Number(match[1]):Math.max(0,total-Number(match[2]||0));
  let end=match[2]&&match[1]?Number(match[2]):total-1;
  if(!Number.isSafeInteger(start)||!Number.isSafeInteger(end)||start<0||end<start||start>=total)return r.status(416).set("Content-Range","bytes */"+total).end();
  end=Math.min(end,total-1);
  r.status(206).set({"Content-Range":"bytes "+start+"-"+end+"/"+total,"Content-Length":String(end-start+1)}).send(buffer.subarray(start,end+1));
  return;
 }
 r.setHeader("Content-Length",String(total));
 r.status(200).send(buffer);
}));

const io=new Server(server,{cors:{origin:process.env.CORS_ORIGIN?.split(",")||"*",methods:["GET","POST"]},maxHttpBufferSize:1e6});
io.use(async(socket,next)=>{
 try{const token=socket.handshake.auth?.token,user=token?await db.getSessionUser(token):null;
  if(!user){if(process.env.ALLOW_LEGACY_ROOMS==="true")return next();return next(new Error("AUTH_REQUIRED"))}
  socket.data.user=user;next();
 }catch(e){next(e)}
});

const activeCalls=new Map(),userCalls=new Map();
function finishCall(callId,event="call:ended",reason="ended"){
 const call=activeCalls.get(callId);if(!call)return;
 clearTimeout(call.timer);activeCalls.delete(callId);
 userCalls.delete(call.callerId);userCalls.delete(call.calleeId);
 const payload={callId,reason};
 io.to("user:"+call.callerId).emit(event,payload);
 io.to("user:"+call.calleeId).emit(event,payload);
}

io.on("connection",socket=>{
 const user=socket.data.user;
 if(user){socket.join("user:"+user.id);db.updateLastSeen(user.id).catch(console.error);io.emit("presence:changed",{userId:user.id,online:true});}

 socket.on("call:start",async payload=>{
  try{
   if(!user)return;
   const conversationId=clean(payload?.conversationId,80),toUserId=clean(payload?.toUserId,80);
   const mediaType=payload?.mediaType==="video"?"video":"audio";
   const conversation=await db.getConversation(conversationId);
   if(!conversation||conversation.type!=="direct"||!await member(conversationId,user.id)||!await member(conversationId,toUserId)){
    socket.emit("call:error",{message:"Las llamadas solo están disponibles en chats privados entre dos personas"});return;
   }
   if(user.id===toUserId){socket.emit("call:error",{message:"No puedes llamarte a ti mismo"});return;}
   const {data:members,error}=await db.supabase.from("conversation_members").select("user_id").eq("conversation_id",conversationId);
   if(error)throw error;
   if((members||[]).length!==2||!(members||[]).some(x=>x.user_id===toUserId)){
    socket.emit("call:error",{message:"Este chat no es una conversación privada individual"});return;
   }
   if(userCalls.has(user.id)||userCalls.has(toUserId)){
    socket.emit("call:error",{message:"Uno de los usuarios ya está en otra llamada"});return;
   }
   const targetSockets=await io.in("user:"+toUserId).fetchSockets();
   if(!targetSockets.length){socket.emit("call:unavailable",{message:"La otra persona no está conectada en CHAT PRO"});return;}
   const callId=randomUUID();
   const call={callId,conversationId,callerId:user.id,calleeId:toUserId,mediaType,accepted:false,timer:null};
   call.timer=setTimeout(()=>finishCall(callId,"call:ended","no-answer"),45000);
   call.timer.unref?.();
   activeCalls.set(callId,call);userCalls.set(user.id,callId);userCalls.set(toUserId,callId);
   io.to("user:"+toUserId).emit("call:incoming",{callId,conversationId,mediaType,fromUser:db.publicUser(user)});
   socket.emit("call:started",{callId,conversationId,mediaType});
  }catch(error){console.error("call:start",error);socket.emit("call:error",{message:"No se pudo iniciar la llamada"});}
 });
 socket.on("call:ready",payload=>{
  const call=activeCalls.get(clean(payload?.callId,80));
  if(!user||!call||call.callerId!==user.id||!call.accepted)return;
  socket.emit("call:accepted",{callId:call.callId});
 });
 socket.on("call:accept",payload=>{
  const call=activeCalls.get(clean(payload?.callId,80));
  if(!user||!call||call.calleeId!==user.id)return;
  clearTimeout(call.timer);call.accepted=true;
  io.to("user:"+call.callerId).emit("call:accepted",{callId:call.callId});
  socket.emit("call:accepted",{callId:call.callId});
 });
 socket.on("call:reject",payload=>{
  const call=activeCalls.get(clean(payload?.callId,80));
  if(!user||!call||![call.callerId,call.calleeId].includes(user.id))return;
  finishCall(call.callId,"call:rejected","rejected");
 });
 socket.on("call:end",payload=>{
  const call=activeCalls.get(clean(payload?.callId,80));
  if(!user||!call||![call.callerId,call.calleeId].includes(user.id))return;
  finishCall(call.callId,"call:ended","ended");
 });
 for(const event of ["call:offer","call:answer","call:ice"]){
  socket.on(event,payload=>{
   const call=activeCalls.get(clean(payload?.callId,80));
   if(!user||!call||!call.accepted||![call.callerId,call.calleeId].includes(user.id))return;
   const targetId=call.callerId===user.id?call.calleeId:call.callerId;
   const signal=event==="call:ice"?payload?.candidate:payload?.sdp;
   if(!signal||typeof signal!=="object")return;
   io.to("user:"+targetId).emit(event,{callId:call.callId,[event==="call:ice"?"candidate":"sdp"]:signal});
  });
 }
 socket.on("conversation:join",async({conversationId}={})=>{
  try{if(!user||!await member(conversationId,user.id))return;
   for(const room of socket.rooms)if(room!==socket.id)socket.leave(room);
   socket.join(conversationId);socket.data.conversationId=conversationId;
   await db.markConversationDelivered(conversationId,user.id);
   socket.emit("conversation:history",await db.listMessages(conversationId,user.id));
   socket.to(conversationId).emit("presence:joined",{user:db.publicUser(user)});
   socket.to(conversationId).emit("conversation:delivered",{userId:user.id,conversationId});
  }catch(e){socket.emit("chat:error",{message:"No se pudo cargar la conversación"})}
 });
 socket.on("conversation:send",async payload=>{
  try{const id=socket.data.conversationId;if(!user||!id||!await member(id,user.id)||!payload||typeof payload!=="object")return;
   const {data:conversationMeta,error:conversationMetaError}=await db.supabase.from("conversations").select("type").eq("id",id).single();
   if(conversationMetaError)throw conversationMetaError;
   if(conversationMeta.type==="direct"){
    const {data:members,error:membersError}=await db.supabase.from("conversation_members").select("user_id").eq("conversation_id",id);
    if(membersError)throw membersError;
    const peer=(members||[]).map(x=>x.user_id).find(x=>x!==user.id);
    if(peer){
     const {data:blocks,error:blockError}=await db.supabase.from("user_blocks").select("blocker_id").or(`and(blocker_id.eq.${user.id},blocked_id.eq.${peer}),and(blocker_id.eq.${peer},blocked_id.eq.${user.id})`);
     if(blockError)throw blockError;
     if((blocks||[]).length){socket.emit("chat:error",{message:"No puedes enviar mensajes por la configuración de bloqueo"});return;}
    }
   }
   const text=clean(payload.text,4000);let media=null;
   if(payload.media&&typeof payload.media==="object"){const m=payload.media,p=clean(m.downloadPath,500);if(!p.startsWith("/api/uploads/"))return;media={name:clean(m.name,180)||"Archivo",mimeType:clean(m.mimeType,100)||"application/octet-stream",size:Number(m.size)||0,downloadPath:p}}
   if(!text&&!media)return;
   const item=await db.createMessage({id:randomUUID(),conversationId:id,senderId:user.id,text,media});
   const activeSockets=await io.in(id).fetchSockets();
   if(activeSockets.some(s=>s.data.user?.id&&s.data.user.id!==user.id)){
    await db.markMessageDelivered(item.id,id,user.id);item.deliveredAt=new Date().toISOString();
   }
   io.to(id).emit("conversation:message",item);
   // Notify every other member through their authenticated user socket room.
   const {data:roomMembers,error:memberError}=await db.supabase.from("conversation_members").select("user_id").eq("conversation_id",id);
   if(memberError)throw memberError;
   const preview=text||((media?.mimeType||"").startsWith("audio/")?"Te envió una nota de voz":media?"Te envió un archivo":"Nuevo mensaje");
   for(const memberRow of roomMembers||[]) {
    if(memberRow.user_id===user.id)continue;
    io.to("user:"+memberRow.user_id).emit("chat:notification",{
     conversationId:id,
     senderName:user.name||user.username||"Nuevo mensaje",
     preview:String(preview).slice(0,140)
    });
   }
  }catch(e){socket.emit("chat:error",{message:"No se pudo enviar el mensaje"})}
 });
 socket.on("room:join",({roomId}={})=>{if(process.env.ALLOW_LEGACY_ROOMS!=="false"||!roomId)return;});
 socket.on("disconnect",()=>{
  if(user){
   db.updateLastSeen(user.id).catch(console.error);
   io.emit("presence:changed",{userId:user.id,online:false,lastSeenAt:new Date().toISOString()});
   setTimeout(async()=>{try{if(!(await io.in("user:"+user.id).fetchSockets()).length){const callId=userCalls.get(user.id);if(callId)finishCall(callId,"call:ended","disconnected");}}catch(error){console.error(error);}},300);
  }
  if(socket.data.conversationId)socket.to(socket.data.conversationId).emit("presence:left",{user:user?db.publicUser(user):null});
 });
});
app.use((err,_q,r,_next)=>{console.error(err);r.status(err.status||(err instanceof multer.MulterError?413:400)).json({error:err.message||"Error al procesar la solicitud"})});
db.verifyConnection().then(()=>server.listen(PORT,"0.0.0.0",()=>console.log("CHAT PRO 2 connected to Supabase and listening on "+PORT))).catch(error=>{console.error("CHAT PRO Supabase connection failed:",error);process.exit(1)});
