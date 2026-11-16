import { createClient } from "@supabase/supabase-js";
import { createHash } from "node:crypto";

const url = process.env.SUPABASE_URL;
const key = process.env.SUPABASE_SECRET_KEY;
if (!url || !key) throw new Error("Faltan SUPABASE_URL o SUPABASE_SECRET_KEY en el servidor");
export const supabase = createClient(url, key, {
  auth: { autoRefreshToken: false, persistSession: false, detectSessionInUrl: false }
});
const fail = (error) => { if (error) throw new Error(error.message || "Error de Supabase"); };
const tokenHash = (token) => createHash("sha256").update(token).digest("hex");
export const publicUser = (u) => u && ({
  id: u.id, name: u.display_name, username: u.username,
  about: u.about || "", avatarUrl: u.avatar_url || "", createdAt: u.created_at, lastSeenAt: u.last_seen_at || null
});
export async function findUserById(id) {
  const { data, error } = await supabase.from("profiles").select("*").eq("id", id).maybeSingle(); fail(error);
  if (!data) return null;
  const { data: cred, error: ce } = await supabase.from("app_credentials").select("password_salt,password_hash").eq("user_id", id).maybeSingle(); fail(ce);
  return { ...data, salt: cred?.password_salt, hash: cred?.password_hash };
}
export async function findUserByUsername(username) {
  const { data, error } = await supabase.from("profiles").select("*").eq("username", username).maybeSingle(); fail(error);
  if (!data) return null;
  const { data: cred, error: ce } = await supabase.from("app_credentials").select("password_salt,password_hash").eq("user_id", data.id).maybeSingle(); fail(ce);
  return { ...data, salt: cred?.password_salt, hash: cred?.password_hash };
}
export async function findUserByEmail(email) {
  const normalized = String(email || "").trim().toLowerCase();
  const { data, error } = await supabase.from("private_user_contacts").select("user_id").eq("email", normalized).maybeSingle(); fail(error);
  return data ? findUserById(data.user_id) : null;
}
export async function setUserEmail(userId, email) {
  const { error } = await supabase.from("private_user_contacts").upsert({ user_id: userId, email }, { onConflict: "user_id" });
  if (error?.code === "23505") throw Object.assign(new Error("Ese correo ya está registrado"), { status: 409 });
  fail(error);
}
export async function registerUser({ id, name, username, email, salt, hash }) {
  const { data, error } = await supabase.from("profiles").insert({ id, username, display_name: name }).select("*").single(); fail(error);
  try {
    const { error: contactError } = await supabase.from("private_user_contacts").insert({ user_id: id, email });
    if (contactError) {
      if (contactError.code === "23505") throw Object.assign(new Error("Ese correo ya está registrado"), { status: 409 });
      throw new Error(contactError.message || "No se pudo guardar el correo");
    }
    const { error: credentialError } = await supabase.from("app_credentials").insert({ user_id: id, password_salt: salt, password_hash: hash });
    fail(credentialError);
    return data;
  } catch (error) {
    await supabase.from("private_user_contacts").delete().eq("user_id", id);
    await supabase.from("profiles").delete().eq("id", id);
    throw error;
  }
}
export async function updateUser(id, patch) {
  const values = {};
  if (patch.name) values.display_name = patch.name;
  if (patch.about !== undefined) values.about = patch.about;
  if (patch.avatarUrl !== undefined) values.avatar_url = patch.avatarUrl;
  const { data, error } = await supabase.from("profiles").update(values).eq("id", id).select("*").single(); fail(error); return data;
}
export async function createSession(token, userId) {
  const { error } = await supabase.from("app_sessions").upsert({ token_hash: tokenHash(token), user_id: userId, expires_at: new Date(Date.now()+30*86400000).toISOString() }); fail(error);
}
export async function getSessionUser(token) {
  const { data, error } = await supabase.from("app_sessions").select("user_id,expires_at").eq("token_hash", tokenHash(token)).maybeSingle(); fail(error);
  if (!data || new Date(data.expires_at).getTime() <= Date.now()) {
    if (data) await supabase.from("app_sessions").delete().eq("token_hash", tokenHash(token));
    return null;
  }
  return findUserById(data.user_id);
}
export async function deleteSession(token) {
  const { error } = await supabase.from("app_sessions").delete().eq("token_hash", tokenHash(token)); fail(error);
}
export async function updatePassword(userId, salt, hash, currentToken) {
  const { error } = await supabase.from("app_credentials").update({ password_salt:salt, password_hash:hash, updated_at:new Date().toISOString() }).eq("user_id",userId);
  fail(error);
  const { error: sessionsError } = await supabase.from("app_sessions").delete().eq("user_id",userId).neq("token_hash",tokenHash(currentToken));
  fail(sessionsError);
}
export async function searchUsers(term, excludeId) {
  const { data, error } = await supabase.from("profiles").select("*").neq("id", excludeId).or(`username.ilike.%${term}%,display_name.ilike.%${term}%`).limit(30); fail(error);
  return (data || []).map(publicUser);
}
export async function isMember(conversationId, userId) {
  const { data, error } = await supabase.from("conversation_members").select("conversation_id").eq("conversation_id", conversationId).eq("user_id", userId).maybeSingle(); fail(error); return !!data;
}
export async function getConversation(id) {
  const { data, error } = await supabase.from("conversations").select("*").eq("id", id).maybeSingle(); fail(error); return data;
}
export async function listConversations(userId) {
  const { data: memberships, error: me } = await supabase.from("conversation_members").select("conversation_id,role,last_read_at").eq("user_id", userId); fail(me);
  const ids = (memberships || []).map(x => x.conversation_id); if (!ids.length) return [];
  const { data: convs, error } = await supabase.from("conversations").select("*").in("id", ids).order("created_at", { ascending: false }); fail(error);
  const out = [];
  for (const c of convs || []) {
    const membership = (memberships || []).find(x => x.conversation_id === c.id);
    const [{ data: members, error: mErr }, { data: last, error: lErr }, { count: unread, error: uErr }] = await Promise.all([
      supabase.from("conversation_members").select("user_id").eq("conversation_id", c.id),
      supabase.from("messages").select("id,body,media,created_at,sender_id").eq("conversation_id", c.id).order("created_at", { ascending: false }).limit(1),
      (() => { let q = supabase.from("messages").select("id", { count: "exact", head: true }).eq("conversation_id", c.id).neq("sender_id", userId); return membership?.last_read_at ? q.gt("created_at", membership.last_read_at) : q; })()
    ]);
    fail(mErr); fail(lErr); fail(uErr);
    out.push({ id:c.id, type:c.type, title:c.title, members:(members||[]).map(x=>x.user_id), createdBy:c.created_by, createdAt:c.created_at, visibility:c.visibility, description:c.description, unread:unread||0, lastMessage:last?.[0]?{id:last[0].id,text:last[0].body,media:last[0].media,createdAt:last[0].created_at,senderId:last[0].sender_id}:null });
  }
  return out;
}
export async function createConversation({ id, type, title, members, createdBy, visibility="private", description="" }) {
  const { data, error } = await supabase.from("conversations").insert({ id, type, title, created_by:createdBy, visibility, description }).select("*").single(); fail(error);
  const rows = members.map((user_id) => ({ conversation_id:id, user_id, role:user_id===createdBy?"owner":"member" }));
  const { error: me } = await supabase.from("conversation_members").insert(rows); fail(me);
  return { id:data.id, type:data.type, title:data.title, members, createdBy:data.created_by, createdAt:data.created_at, visibility:data.visibility, description:data.description };
}
export async function addConversationMember(conversationId, userId) {
  const { error } = await supabase.from("conversation_members").upsert({ conversation_id:conversationId, user_id:userId }); fail(error);
}
export async function markConversationRead(conversationId, userId) {
  const now = new Date().toISOString();
  const { error: memberError } = await supabase.from("conversation_members").update({ last_read_at: now }).eq("conversation_id", conversationId).eq("user_id", userId);
  fail(memberError);
  const { error: messageError } = await supabase.from("messages").update({ read_at: now }).eq("conversation_id", conversationId).neq("sender_id", userId).is("read_at", null);
  fail(messageError);
  return now;
}
export async function listMessages(conversationId, userId) {
  const { data, error } = await supabase.from("messages").select("*,profiles!messages_sender_id_fkey(*)").eq("conversation_id",conversationId).order("created_at",{ascending:true}).limit(500); fail(error);
  const ids=(data||[]).map(m=>m.id);
  const [{data:reactions,error:re},{data:hidden,error:he}] = await Promise.all([
    ids.length ? supabase.from("message_reactions").select("message_id,user_id,reaction").in("message_id",ids) : Promise.resolve({data:[],error:null}),
    userId ? supabase.from("message_hidden_for").select("message_id").eq("user_id",userId).in("message_id",ids) : Promise.resolve({data:[],error:null})
  ]);
  fail(re); fail(he);
  const hiddenIds=new Set((hidden||[]).map(x=>x.message_id));
  return (data||[]).filter(m=>!hiddenIds.has(m.id)).map(m=>({ id:m.id, conversationId:m.conversation_id, user:publicUser(m.profiles), text:m.body, media:m.media, createdAt:m.created_at, deliveredAt:m.delivered_at, readAt:m.read_at, deletedAt:m.deleted_at||null, deletedBy:m.deleted_by||null, reactions:(reactions||[]).filter(x=>x.message_id===m.id).map(x=>({userId:x.user_id,reaction:x.reaction})) }));
}
export async function getMessage(id) {
  const { data,error } = await supabase.from("messages").select("id,conversation_id,sender_id,created_at,deleted_at").eq("id",id).maybeSingle(); fail(error); return data;
}
export async function deleteMessageForUser(messageId,userId) {
  const {error}=await supabase.from("message_hidden_for").upsert({message_id:messageId,user_id:userId},{onConflict:"message_id,user_id"}); fail(error);
}
export async function clearConversationHistory(conversationId,userId) {
  let offset=0, hiddenCount=0;
  while(true) {
    const {data,error}=await supabase.from("messages").select("id")
      .eq("conversation_id",conversationId).order("created_at",{ascending:true})
      .range(offset,offset+999);
    fail(error);
    const rows=(data||[]).map(message=>({message_id:message.id,user_id:userId}));
    if(rows.length) {
      const {error:hideError}=await supabase.from("message_hidden_for")
        .upsert(rows,{onConflict:"message_id,user_id"});
      fail(hideError);
      hiddenCount+=rows.length;
    }
    if(!data||data.length<1000)break;
    offset+=1000;
  }
  const {error:readError}=await supabase.from("conversation_members")
    .update({last_read_at:new Date().toISOString()})
    .eq("conversation_id",conversationId).eq("user_id",userId);
  fail(readError);
  return hiddenCount;
}
export async function deleteMessageForEveryone(messageId,userId) {
  // messages_have_content requires body text or media. Keep a non-empty tombstone
  // while clearing the original text and attachment so the delete is accepted.
  const {data,error}=await supabase.from("messages")
    .update({body:"[Mensaje eliminado]",media:null,deleted_at:new Date().toISOString(),deleted_by:userId})
    .eq("id",messageId).eq("sender_id",userId).is("deleted_at",null)
    .select("id").maybeSingle();
  fail(error);
  if(!data) throw new Error("No se pudo eliminar el mensaje: ya no existe o no pertenece a tu cuenta");
  const {error:reactionError}=await supabase.from("message_reactions").delete().eq("message_id",messageId); fail(reactionError);
}
export async function toggleMessageReaction(messageId,userId,reaction) {
  const { data: existing,error: findError } = await supabase.from("message_reactions").select("reaction").eq("message_id",messageId).eq("user_id",userId).maybeSingle(); fail(findError);
  if(existing?.reaction===reaction) {
    const { error } = await supabase.from("message_reactions").delete().eq("message_id",messageId).eq("user_id",userId); fail(error);
  } else {
    const { error } = await supabase.from("message_reactions").upsert({message_id:messageId,user_id:userId,reaction},{onConflict:"message_id,user_id"}); fail(error);
  }
  const { data,error } = await supabase.from("message_reactions").select("user_id,reaction").eq("message_id",messageId); fail(error); return data||[];
}
export async function markConversationDelivered(conversationId,userId) {
  const { error } = await supabase.from("messages").update({delivered_at:new Date().toISOString()}).eq("conversation_id",conversationId).neq("sender_id",userId).is("delivered_at",null); fail(error);
}
export async function markMessageDelivered(messageId,conversationId,senderId) {
  const { error } = await supabase.from("messages").update({delivered_at:new Date().toISOString()}).eq("id",messageId).eq("conversation_id",conversationId).neq("sender_id",senderId).is("delivered_at",null); fail(error);
}
export async function updateLastSeen(userId) {
  const { error } = await supabase.from("profiles").update({last_seen_at:new Date().toISOString()}).eq("id",userId); fail(error);
}
export async function listPublicRooms(userId) {
  const { data,error } = await supabase.from("conversations").select("id,title,description,created_by,created_at").eq("type","room").eq("visibility","public").order("created_at",{ascending:false}).limit(100); fail(error);
  const rooms=[];
  for(const room of data||[]) {
    const [{count,error:ce},{data:mine,error:me}] = await Promise.all([
      supabase.from("conversation_members").select("user_id",{count:"exact",head:true}).eq("conversation_id",room.id),
      supabase.from("conversation_members").select("user_id").eq("conversation_id",room.id).eq("user_id",userId).maybeSingle()
    ]);
    fail(ce); fail(me); rooms.push({...room,members:count||0,isMember:!!mine});
  }
  return rooms;
}
export async function joinPublicRoom(conversationId,userId) {
  const {data,error}=await supabase.from("conversations").select("id,type,visibility").eq("id",conversationId).maybeSingle(); fail(error);
  if(!data||data.type!=="room"||data.visibility!=="public")return false;
  const {error:joinError}=await supabase.from("conversation_members").upsert({conversation_id:conversationId,user_id:userId},{onConflict:"conversation_id,user_id"}); fail(joinError); return true;
}
export async function createMessage({ id, conversationId, senderId, text, media }) {
  const { data, error } = await supabase.from("messages").insert({ id, conversation_id:conversationId, sender_id:senderId, body:text||"", media:media||null }).select("*,profiles!messages_sender_id_fkey(*)").single(); fail(error);
  return { id:data.id, conversationId:data.conversation_id, user:publicUser(data.profiles), text:data.body, media:data.media, createdAt:data.created_at };
}
export async function listStatuses() {
  const { data, error } = await supabase.from("statuses").select("*,profiles!statuses_user_id_fkey(*)").gt("expires_at",new Date().toISOString()).order("created_at",{ascending:true}); fail(error);
  return data||[];
}
export async function createStatus({ id,userId,caption,media }) {
  const mediaUrl = media?.downloadPath || "";
  const mediaType = media ? (String(media.mimeType||"").startsWith("video/")?"video":"image") : "text";
  const { data,error } = await supabase.from("statuses").insert({id,user_id:userId,caption,media_url:mediaUrl,media_type:mediaType,expires_at:new Date(Date.now()+86400000).toISOString()}).select("*,profiles!statuses_user_id_fkey(*)").single(); fail(error); return data;
}
export async function recordStatusView(statusId,userId) {
  const { error } = await supabase.from("status_views").upsert({status_id:statusId,viewer_id:userId},{onConflict:"status_id,viewer_id"}); fail(error);
}
export async function getStatusViewers(statusId) {
  const { data,error } = await supabase.from("status_views").select("*,profiles!status_views_viewer_id_fkey(*)").eq("status_id",statusId).order("viewed_at",{ascending:true}); fail(error); return data||[];
}
export async function deleteStatus(statusId,userId) {
  const { error } = await supabase.from("statuses").delete().eq("id",statusId).eq("user_id",userId); fail(error);
}

export async function uploadMedia(objectName, fileBuffer, contentType) {
  const { data, error } = await supabase.storage.from("chat-pro-media").upload(objectName, fileBuffer, {
    contentType: contentType || "application/octet-stream", upsert: false
  });
  fail(error); return data;
}
export async function downloadMedia(objectName) {
  const { data, error } = await supabase.storage.from("chat-pro-media").download(objectName);
  fail(error); return data;
}

export async function verifyConnection() {
  const { error } = await supabase.from("profiles").select("id").limit(1);
  fail(error);
  return true;
}
