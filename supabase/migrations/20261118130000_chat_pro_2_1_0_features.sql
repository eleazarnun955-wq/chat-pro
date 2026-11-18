-- CHAT PRO 2.1.0: synchronized bookmarks, blocks and reports.
create table if not exists public.message_bookmarks (
  user_id uuid not null references public.profiles(id) on delete cascade,
  message_id uuid not null references public.messages(id) on delete cascade,
  is_pinned boolean not null default false,
  created_at timestamptz not null default now(),
  primary key (user_id, message_id)
);
create index if not exists message_bookmarks_message_id_idx on public.message_bookmarks(message_id);
create table if not exists public.user_blocks (
  blocker_id uuid not null references public.profiles(id) on delete cascade,
  blocked_id uuid not null references public.profiles(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (blocker_id, blocked_id),
  constraint user_blocks_no_self check (blocker_id <> blocked_id)
);
create index if not exists user_blocks_blocked_id_idx on public.user_blocks(blocked_id);
create table if not exists public.user_reports (
  id uuid primary key default gen_random_uuid(),
  reporter_id uuid not null references public.profiles(id) on delete cascade,
  reported_id uuid not null references public.profiles(id) on delete cascade,
  reason text not null check (char_length(reason) between 3 and 500),
  created_at timestamptz not null default now(),
  constraint user_reports_no_self check (reporter_id <> reported_id)
);
create index if not exists user_reports_reported_id_idx on public.user_reports(reported_id);
alter table public.message_bookmarks enable row level security;
alter table public.user_blocks enable row level security;
alter table public.user_reports enable row level security;
create index if not exists messages_conversation_created_idx on public.messages(conversation_id, created_at desc);
create index if not exists messages_deleted_by_idx on public.messages(deleted_by) where deleted_by is not null;
