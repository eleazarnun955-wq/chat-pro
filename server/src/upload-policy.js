const MIME_TO_EXTENSION = new Map(Object.entries({
 "image/jpeg":".jpg","image/png":".png","image/webp":".webp","image/gif":".gif",
 "image/bmp":".bmp","image/heic":".heic","image/heif":".heic","image/avif":".avif",
 "video/mp4":".mp4","video/x-m4v":".m4v","video/quicktime":".mov","video/webm":".webm",
 "video/3gpp":".3gp","video/3gpp2":".3g2","video/x-matroska":".mkv","video/x-msvideo":".avi",
 "audio/mpeg":".mp3","audio/mp3":".mp3","audio/mp4":".m4a","audio/x-m4a":".m4a",
 "audio/wav":".wav","audio/x-wav":".wav","audio/wave":".wav","audio/aac":".aac",
 "audio/ogg":".ogg","application/ogg":".ogg","audio/opus":".opus","audio/flac":".flac",
 "audio/x-flac":".flac","audio/amr":".amr","audio/3gpp":".amr","audio/aiff":".aiff",
 "audio/x-aiff":".aiff","audio/midi":".mid","audio/x-midi":".mid",
 "application/pdf":".pdf","application/msword":".doc",
 "application/vnd.openxmlformats-officedocument.wordprocessingml.document":".docx",
 "application/vnd.ms-excel":".xls",
 "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet":".xlsx",
 "application/vnd.ms-powerpoint":".ppt",
 "application/vnd.openxmlformats-officedocument.presentationml.presentation":".pptx",
 "text/plain":".txt","text/csv":".csv","application/rtf":".rtf",
 "application/zip":".zip","application/x-zip-compressed":".zip",
 "application/vnd.rar":".rar","application/x-rar-compressed":".rar",
 "application/x-7z-compressed":".7z"
}));
const EXTENSION_TO_MIME = new Map([
 [".jpg","image/jpeg"],[".jpeg","image/jpeg"],[".png","image/png"],[".webp","image/webp"],
 [".gif","image/gif"],[".bmp","image/bmp"],[".heic","image/heic"],[".avif","image/avif"],
 [".mp4","video/mp4"],[".m4v","video/mp4"],[".mov","video/quicktime"],[".webm","video/webm"],
 [".3gp","video/3gpp"],[".3g2","video/3gpp2"],[".mkv","video/x-matroska"],[".avi","video/x-msvideo"],
 [".mp3","audio/mpeg"],[".m4a","audio/mp4"],[".wav","audio/wav"],[".flac","audio/flac"],
 [".aac","audio/aac"],[".ogg","audio/ogg"],[".opus","audio/ogg"],[".amr","audio/amr"],
 [".aiff","audio/aiff"],[".mid","audio/midi"],[".pdf","application/pdf"],[".doc","application/msword"],
 [".docx","application/vnd.openxmlformats-officedocument.wordprocessingml.document"],
 [".xls","application/vnd.ms-excel"],[".xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"],
 [".ppt","application/vnd.ms-powerpoint"],
 [".pptx","application/vnd.openxmlformats-officedocument.presentationml.presentation"],
 [".txt","text/plain"],[".csv","text/csv"],[".rtf","application/rtf"],[".zip","application/zip"],
 [".rar","application/vnd.rar"],[".7z","application/x-7z-compressed"]
]);
export function resolveUploadExtension(originalName,mimetype) {
 const name=String(originalName||"").replace(/\\/g,"/");
 const rawExtension=name.slice(name.lastIndexOf("/")+1).match(/\\.[^.]+$/)?.[0]?.toLowerCase()||"";
 if(EXTENSION_TO_MIME.has(rawExtension))return rawExtension;
 const mime=String(mimetype||"").split(";")[0].trim().toLowerCase();
 return MIME_TO_EXTENSION.get(mime)||"";
}
export function isSupportedUpload(originalName,mimetype) {
 return resolveUploadExtension(originalName,mimetype)!=="";
}
export function uploadMimeForExtension(extension) {
 return EXTENSION_TO_MIME.get(String(extension||"").toLowerCase())||"application/octet-stream";
}
