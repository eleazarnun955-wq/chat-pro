-- CHAT PRO account sign-in identifier: email address.
-- Keep the contact email private and unique regardless of letter casing.
create unique index if not exists private_user_contacts_email_unique
  on public.private_user_contacts (lower(btrim(email)))
  where email is not null and btrim(email) <> '';
