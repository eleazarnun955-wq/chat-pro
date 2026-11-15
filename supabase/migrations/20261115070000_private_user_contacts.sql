create table if not exists public.private_user_contacts (
  user_id uuid primary key references public.profiles(id) on delete cascade,
  email text,
  phone text,
  created_at timestamptz not null default now(),
  constraint private_user_contacts_at_least_one_contact check (
    (email is not null and email <> '') or (phone is not null and phone <> '')
  )
);
insert into public.private_user_contacts(user_id,email,phone)
select id,email,phone from public.profiles
where (email is not null and email <> '') or (phone is not null and phone <> '')
on conflict (user_id) do update set email=excluded.email, phone=excluded.phone;
create unique index if not exists private_user_contacts_email_unique_lower
  on public.private_user_contacts(lower(email))
  where email is not null and email <> '';
create unique index if not exists private_user_contacts_phone_unique
  on public.private_user_contacts(phone)
  where phone is not null and phone <> '';
alter table public.private_user_contacts enable row level security;
drop index if exists public.profiles_email_unique_lower;
drop index if exists public.profiles_phone_unique;
alter table public.profiles drop column if exists email;
alter table public.profiles drop column if exists phone;
