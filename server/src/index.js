import express from "express";
import cors from "cors";
import http from "node:http";
import path from "node:path";
import fs from "node:fs";
import { Server } from "socket.io";
import multer from "multer";
import { randomUUID } from "node:crypto";

const app = express();
const server = http.createServer(app);
const PORT = Number(process.env.PORT || 10000);
const MAX_UPLOAD_BYTES = Number(process.env.MAX_UPLOAD_BYTES || 512 * 1024 * 1024);
const uploadDir = process.env.UPLOAD_DIR || path.resolve("uploads");
fs.mkdirSync(uploadDir, { recursive: true });

app.use(cors({ origin: process.env.CORS_ORIGIN?.split(",") || "*" }));
app.use(express.json({ limit: "1mb" }));
app.get("/health", (_req, res) => res.json({ ok: true, service: "CHAT PRO", version: "1.0.0" }));

const allowedAudio = new Set([".mp3", ".wav", ".m4a", ".flac"]);
const storage = multer.diskStorage({
  destination: (_req, _file, cb) => cb(null, uploadDir),
  filename: (_req, file, cb) => cb(null, randomUUID() + path.extname(file.originalname).toLowerCase())
});
const uploader = multer({
  storage,
  limits: { fileSize: MAX_UPLOAD_BYTES, files: 1 },
  fileFilter: (_req, file, cb) => {
    const ext = path.extname(file.originalname).toLowerCase();
    const isAudio = allowedAudio.has(ext);
    const isOtherAllowed = /^(image|video|application)\//.test(file.mimetype);
    cb(isAudio || isOtherAllowed ? null : new Error("Tipo de archivo no permitido"), isAudio || isOtherAllowed);
  }
});

app.post("/api/uploads", uploader.single("file"), (req, res) => {
  if (!req.file) return res.status(400).json({ error: "Falta el archivo" });
  const file = req.file;
  res.status(201).json({
    id: path.basename(file.filename, path.extname(file.filename)),
    name: file.originalname,
    size: file.size,
    mimeType: file.mimetype,
    downloadPath: "/api/uploads/" + encodeURIComponent(file.filename)
  });
});

app.get("/api/uploads/:filename", (req, res) => {
  const name = path.basename(req.params.filename);
  const fullPath = path.join(uploadDir, name);
  if (!fs.existsSync(fullPath)) return res.sendStatus(404);
  res.download(fullPath);
});

const io = new Server(server, {
  cors: { origin: process.env.CORS_ORIGIN?.split(",") || "*", methods: ["GET", "POST"] },
  maxHttpBufferSize: 1e6
});
const roomMessages = new Map();

io.on("connection", socket => {
  socket.on("room:join", ({ roomId, displayName }) => {
    if (typeof roomId !== "string" || !roomId.trim() || roomId.length > 80) return;
    socket.join(roomId);
    socket.data.roomId = roomId;
    socket.data.displayName = typeof displayName === "string" ? displayName.slice(0, 40) : "Usuario";
    socket.emit("room:history", roomMessages.get(roomId) || []);
    socket.to(roomId).emit("presence:joined", { displayName: socket.data.displayName });
  });

  socket.on("message:send", payload => {
    const roomId = socket.data.roomId;
    if (!roomId || !payload || typeof payload.text !== "string") return;
    const text = payload.text.trim().slice(0, 4000);
    if (!text) return;
    const item = {
      id: randomUUID(),
      roomId,
      displayName: socket.data.displayName || "Usuario",
      text,
      createdAt: new Date().toISOString()
    };
    const history = roomMessages.get(roomId) || [];
    history.push(item);
    roomMessages.set(roomId, history.slice(-200));
    io.to(roomId).emit("message:new", item);
  });

  socket.on("disconnect", () => {
    if (socket.data.roomId) socket.to(socket.data.roomId).emit("presence:left", {
      displayName: socket.data.displayName || "Usuario"
    });
  });
});

app.use((err, _req, res, _next) => {
  const status = err instanceof multer.MulterError ? 413 : 400;
  res.status(status).json({ error: err.message || "Error al procesar la solicitud" });
});

server.listen(PORT, "0.0.0.0", () => {
  console.log("CHAT PRO server listening on " + PORT);
});
