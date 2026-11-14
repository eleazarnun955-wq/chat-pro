import express from "express";
import cors from "cors";
import http from "node:http";
import path from "node:path";
import fs from "node:fs";
import { Server } from "socket.io";
import multer from "multer";
import { randomUUID } from "node:crypto";

const app=express();
const server=http.createServer(app);
const PORT=Number(process.env.PORT||10000);
const MAX_UPLOAD_BYTES=Number(process.env.MAX_UPLOAD_BYTES||512*1024*1024);
const uploadDir=process.env.UPLOAD_DIR||path.resolve("uploads");
const dataDir=process.env.DATA_DIR||path.resolve("data");
fs.mkdirSync(uploadDir,{recursive:true});
fs.mkdirSync(dataDir,{recursive:true});
const historyFile=path.join(dataDir,"messages.json");
let roomMessages=new Map();
try {
 const saved=JSON.parse(fs.readFileSync(historyFile,"utf8"));
 for(const [room,items] of Object.entries(saved)) roomMessages.set(room,Array.isArray(items)?items.slice(-500):[]);
} catch {}
function persist() {
 const obj=Object.fromEntries(roomMessages);
 const tmp=historyFile+".tmp";
 fs.writeFileSync(tmp,JSON.stringify(obj));
 fs.renameSync(tmp,historyFile);
}
app.use(cors({origin:process.env.CORS_ORIGIN?.split(",")||"*"}));
app.use(express.json({limit:"1mb"}));
app.get("/health",(_req,res)=>res.json({ok:true,service:"CHAT PRO",version:"1.1.0"}));

const allowedAudio=new Set([".mp3",".wav",".m4a",".flac"]);
const storage=multer.diskStorage({
 destination:(_req,_file,cb)=>cb(null,uploadDir),
 filename:(_req,file,cb)=>cb(null,randomUUID()+path.extname(file.originalname).toLowerCase())
});
const uploader=multer({
 storage,
 limits:{fileSize:MAX_UPLOAD_BYTES,files:1},
 fileFilter:(_req,file,cb)=>{
  const ext=path.extname(file.originalname).toLowerCase();
  const allowed=allowedAudio.has(ext)||/^(image|video|application)\//.test(file.mimetype);
  cb(allowed?null:new Error("Tipo de archivo no permitido"),allowed);
 }
});
app.post("/api/uploads",uploader.single("file"),(req,res)=>{
 if(!req.file) return res.status(400).json({error:"Falta el archivo"});
 const f=req.file;
 res.status(201).json({id:path.basename(f.filename,path.extname(f.filename)),name:f.originalname,size:f.size,mimeType:f.mimetype,downloadPath:"/api/uploads/"+encodeURIComponent(f.filename)});
});
app.get("/api/uploads/:filename",(req,res)=>{
 const name=path.basename(req.params.filename);
 const full=path.join(uploadDir,name);
 if(!fs.existsSync(full)) return res.sendStatus(404);
 res.download(full);
});

const io=new Server(server,{cors:{origin:process.env.CORS_ORIGIN?.split(",")||"*",methods:["GET","POST"]},maxHttpBufferSize:1e6});
io.on("connection",socket=>{
 socket.on("room:join",({roomId,displayName}={})=>{
  if(typeof roomId!=="string"||!roomId.trim()||roomId.length>80) return;
  if(socket.data.roomId) socket.leave(socket.data.roomId);
  socket.join(roomId);
  socket.data.roomId=roomId;
  socket.data.displayName=typeof displayName==="string"?displayName.slice(0,40):"Usuario";
  socket.emit("room:history",roomMessages.get(roomId)||[]);
  socket.to(roomId).emit("presence:joined",{displayName:socket.data.displayName});
 });
 socket.on("message:send",payload=>{
  const roomId=socket.data.roomId;
  if(!roomId||!payload||typeof payload!=="object") return;
  const text=typeof payload.text==="string"?payload.text.trim().slice(0,4000):"";
  let media=null;
  if(payload.media&&typeof payload.media==="object") {
   const m=payload.media;
   const p=typeof m.downloadPath==="string"?m.downloadPath:"";
   if(!p.startsWith("/api/uploads/")) return;
   media={name:String(m.name||"Archivo").slice(0,180),mimeType:String(m.mimeType||"application/octet-stream").slice(0,100),size:Number(m.size)||0,downloadPath:p};
  }
  if(!text&&!media) return;
  const item={id:randomUUID(),roomId,displayName:socket.data.displayName||"Usuario",text,media,createdAt:new Date().toISOString()};
  const history=roomMessages.get(roomId)||[];
  history.push(item);roomMessages.set(roomId,history.slice(-500));
  try { persist(); } catch(err) { console.error("No se pudo guardar el historial",err); }
  io.to(roomId).emit("message:new",item);
 });
 socket.on("disconnect",()=>{
  if(socket.data.roomId) socket.to(socket.data.roomId).emit("presence:left",{displayName:socket.data.displayName||"Usuario"});
 });
});
app.use((err,_req,res,_next)=>{
 const status=err instanceof multer.MulterError?413:400;
 res.status(status).json({error:err.message||"Error al procesar la solicitud"});
});
server.listen(PORT,"0.0.0.0",()=>console.log("CHAT PRO server listening on "+PORT));
