-- Run ONCE in Supabase > SQL Editor > New query
alter table vehicles add column moving_interval int, add column stopped_interval int,   -- seconds between records (empty = default)
                     add column brake_limit real, add column accel_limit real;           -- km/h per second (empty = default)
create table app_settings (key text primary key, value text not null);
insert into app_settings values ('moving_interval','10'),('stopped_interval','60');
alter table app_settings enable row level security;
create policy "settings read"  on app_settings for select using (auth.uid() is not null);
create policy "settings admin" on app_settings for all using (is_admin()) with check (is_admin());
