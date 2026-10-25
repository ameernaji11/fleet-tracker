-- Run ONCE in Supabase > SQL Editor > New query
alter table positions add column accuracy real, add column altitude real, add column heading real, add column hdop real,
  add column ignition boolean, add column accel real, add column battery_pct real, add column charging boolean, add column battery_v real;
alter table vehicles add column type text, add column make_model text, add column year int, add column vin text,
  add column fuel_type text, add column speed_limit int, add column notes text;
alter table drivers add column employee_id text, add column license_no text, add column license_expiry date,
  add column nationality text, add column notes text;
alter table events add column details text;

create table event_rules (type text primary key, label text, enabled boolean default true, threshold double precision, alert boolean default true);
insert into event_rules values ('harsh_braking','Harsh braking',true,3.0,true),('harsh_acceleration','Harsh acceleration',true,2.5,true),('overspeed','Overspeed',true,100,true);
alter table event_rules enable row level security;
create policy "rules read" on event_rules for select using (auth.uid() is not null);
create policy "rules admin" on event_rules for all using (is_admin()) with check (is_admin());

create table geofences (id uuid primary key default gen_random_uuid(), name text not null, shape jsonb not null, color text default '#2f7fe6', enabled boolean default true, created_at timestamptz default now());
alter table geofences enable row level security;
create policy "geo read" on geofences for select using (auth.uid() is not null);
create policy "geo admin" on geofences for all using (is_admin()) with check (is_admin());

-- 1-second data fills the free 500 MB fast. Optional automatic cleanup (enable pg_cron in Database > Extensions):
-- select cron.schedule('purge-positions','0 3 * * *',$$delete from positions where recorded_at < now() - interval '30 days'$$);
