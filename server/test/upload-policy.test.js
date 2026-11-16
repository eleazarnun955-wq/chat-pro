import test from "node:test";
import assert from "node:assert/strict";
import { resolveUploadExtension, isSupportedUpload, uploadMimeForExtension } from "../src/upload-policy.js";

test("accepts a known extension even when Android reports generic MIME",()=>{
 assert.equal(resolveUploadExtension("cancion.mp3","application/octet-stream"),".mp3");
 assert.equal(isSupportedUpload("foto.jpg","application/octet-stream"),true);
});
test("infers extension when Android provider omits or mislabels filename extension",()=>{
 assert.equal(resolveUploadExtension("audio","audio/mpeg"),".mp3");
 assert.equal(resolveUploadExtension("IMG_001","image/heic"),".heic");
 assert.equal(resolveUploadExtension("clip.bin","video/mp4"),".mp4");
});
test("supports common audio, image, video and document formats",()=>{
 for(const [name,mime,ext] of [
  ["track.m4a","audio/x-m4a",".m4a"],["voice.ogg","audio/ogg",".ogg"],
  ["photo.avif","image/avif",".avif"],["movie.mkv","video/x-matroska",".mkv"],
  ["report.pdf","application/pdf",".pdf"],["sheet.xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",".xlsx"]
 ]) assert.equal(resolveUploadExtension(name,mime),ext);
});
test("rejects unknown formats and never trusts an unknown extension alone",()=>{
 assert.equal(isSupportedUpload("payload.exe","application/octet-stream"),false);
 assert.equal(isSupportedUpload("payload.unknown","application/x-unknown"),false);
});
test("returns canonical MIME type for stored object extension",()=>{
 assert.equal(uploadMimeForExtension(".mp3"),"audio/mpeg");
 assert.equal(uploadMimeForExtension(".mp4"),"video/mp4");
});
