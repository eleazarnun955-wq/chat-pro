-- Index the reporter foreign key to avoid scans when deleting a profile.
create index if not exists user_reports_reporter_id_idx on public.user_reports(reporter_id);
