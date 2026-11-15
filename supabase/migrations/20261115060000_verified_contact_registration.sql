alter table public.profiles
  add column if not exists email text,
  add column if not exists phone text;

create unique index if not exists profiles_email_unique_lower
  on public.profiles (lower(email))
  where email is not null and email <> '';

create unique index if not exists profiles_phone_unique
  on public.profiles (phone)
  where phone is not null and phone <> '';

comment on column public.profiles.email is 'Private verified email used for account recovery; never exposed in public profile responses.';
comment on column public.profiles.phone is 'Private verified phone in E.164 format used for account recovery; never exposed in public profile responses.';
