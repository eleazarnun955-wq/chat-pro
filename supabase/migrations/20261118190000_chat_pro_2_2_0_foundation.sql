-- CHAT PRO 2.2.0 foundation: device push tokens, per-member receipts and group roles.
-- Push delivery still requires Firebase project credentials and Android google-services.json.
create table if not exists public.push_devices (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references public.profiles(id) on delete cascade,
  fcm_token text not null unique,
  platform text not null default 'android' check (platform in ('android')),
  enabled boolean not null default true,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index if not exists push_devices_user_enabled_idx
  on public.push_devices(user_id) where enabled = true;
alter table public.push_devices enable row level security;
grant select, insert, update, delete on public.push_devices to service_role;

create table if not exists public.message_receipts (
  message_id uuid not null references public.messages(id) on delete cascade,
  user_id uuid not null references public.profiles(id) on delete cascade,
  delivered_at timestamptz,
  read_at timestamptz,
  primary key (message_id, user_id)
);
create index if not exists message_receipts_user_read_idx
  on public.message_receipts(user_id, read_at);
alter table public.message_receipts enable row level security;
grant select, insert, update, delete on public.message_receipts to service_role;

-- Existing role values remain valid; new roles support explicit group administration.
alter table public.conversation_members
  alter column role set default 'member';
alter table public.conversation_members
  drop constraint if exists conversation_members_role_check;
alter table public.conversation_members
  add constraint conversation_members_role_check
  check (role in ('owner','admin','moderator','member'));

-- Keep device tokens and receipts inaccessible to anonymous/authenticated clients:
-- the server accesses them only through its Supabase service-role client.
