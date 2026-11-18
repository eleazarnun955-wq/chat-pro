# CHAT PRO 2.0 — Free-first implementation plan

## Non-negotiable release rules
- Build from the exact CHAT PRO 1.9.0 commit that created branch `chat-pro-2.0-free`.
- Keep CHAT PRO 1.9.0 installed and usable. CHAT PRO 2.0 must use a new Android applicationId and app label.
- Do not modify CHAT DJ'S VIP.
- Preserve the existing account system, conversations, rooms, messages, statuses, and media on the current backend.
- No paid APIs, paid SDKs, paid TURN/SFU services, paid push gateways, subscriptions, or new paid hosting.
- Prefer open-source libraries and existing free services. A free-tier service may have quotas or setup requirements; do not silently introduce a paid dependency.
- Never claim a feature is complete until it is implemented, built, and tested.

## Feature scope

### UX and visual system
- Consistent WhatsApp-inspired information architecture, not copied branding/assets.
- Modern Chats, Contacts, Status, Calls and Settings navigation.
- Conversation list with avatar, last message, timestamp, unread count, pinned and muted states.
- Chat bubbles with timestamps, delivery/read indicators, reply previews, media previews and contextual actions.
- Consistent typography, spacing, iconography, accessibility labels, dark/light themes and responsive layouts.
- Profile, privacy, storage, notification and appearance settings.

### Messaging
- Reply/quote, edit, forward, copy, pin, star/favorite, multi-select and message search.
- Typing indicators and online/last-seen presence.
- Temporary messages only if server-side expiry and deletion semantics can be implemented safely.
- Existing voice notes, media sharing, reactions, deletion, delivery/read receipts and history must remain functional.

### Groups and rooms
- Admin/moderator roles, member management, group metadata and avatar, invite links, join/leave, mute and granular posting permissions.
- Polls only if implemented with persistent server-side records and authorization.
- Preserve existing public/private room behavior.

### Notifications
- Investigate Firebase Cloud Messaging as a no-cost push transport; requires user-owned Firebase project/configuration and must not include private service credentials in the APK.
- Keep in-app Socket.IO notifications as fallback.
- Notifications for direct chats, groups/rooms and calls; per-chat mute and notification preferences.
- Do not promise delivery when app is closed until FCM is configured and tested on physical devices.

### Calls
- Improve call lifecycle, incoming call UX, reconnect/error handling and call history.
- Use only currently available free STUN options. Do not claim reliable connectivity on restrictive networks without TURN.
- Group calls/SFU and hosted TURN are out of scope if they require paid infrastructure. Consider a clearly labeled best-effort peer-to-peer experiment only if it is reliable and secure.

### Privacy and security
- Block/report users, profile/status visibility controls, session/device management and secure local credential storage.
- Review server authorization and upload access.
- End-to-end encryption is a separate cryptographic project: do not label existing transport/local protections as E2EE. Implement only with a reviewed protocol/library and migration plan; do not roll custom cryptography.
- Do not weaken Supabase RLS or expose service-role credentials.

### Backup and storage
- Provide storage usage and media management first.
- Cloud backup only within existing free storage quotas and with clear user consent. Do not promise unlimited free backups.
- Restore must not overwrite newer local/server data without confirmation.

## Delivery and verification gates
1. Keep 1.9.0 untouched; develop only on this branch.
2. Add tests for every new server route, authorization rule and data migration.
3. Run Node syntax checks and server tests.
4. Build debug APK in GitHub Actions; verify APK exists and package/applicationId is distinct from 1.9.0.
5. Test two real Android devices: registration/login, direct messages, groups, media, voice notes, statuses, notification permissions, app background/closed behavior and calls on Wi-Fi/mobile data.
6. Do not merge or call it release-ready while critical tests fail.
7. Produce a signed release only if the owner has configured signing secrets; otherwise provide a clearly labeled debug APK.

## Known gaps confirmed in the 1.9.0 source review
- MainActivity is a large monolithic activity and should be decomposed incrementally without a risky rewrite.
- Message notifications currently rely on the active Socket.IO connection; push delivery while app is closed needs FCM setup and device testing.
- Calls use WebRTC/STUN; without TURN, some NAT/firewall combinations will fail.
- Current CI can build debug APKs; release signing depends on owner-provided keystore secrets.
- Successful CI compilation is not proof of real-device functionality.
