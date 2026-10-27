-- ============================================================================
-- Fleet Tracker — complete database schema (Supabase > SQL Editor > New query)
--
-- This single file replaces schema.sql + migration-v4/v5/v6/v7/v8 + migration-advanced.
-- It is written to be SAFE TO RUN TWICE: everything is if-not-exists /
-- create-or-replace / drop-policy-then-create, so it works both as a fresh
-- install on a new project and as a repair script on an already-migrated one.
--
-- Your current live project (jdaqacacdihkoliyeiao) is already at this state,
-- apart from the hdop clean-up at the end, which this file applies for you.
--
-- After a fresh install: sign in once from the dashboard, then run the
-- "make yourself admin" statement at the bottom of this file.
-- ============================================================================

-- ---------------------------------------------------------------- tables ----
create table if not exists profiles (
  id uuid primary key references auth.users on delete cascade,
  email text,
  role text not null default 'user');

create table if not exists vehicles (
  id uuid primary key default gen_random_uuid(),
  name text not null,
  plate text,
  created_at timestamptz default now(),
  -- archive (v4)
  type text, make_model text, year int, vin text, fuel_type text, notes text,
  speed_limit int,
  -- fuel model (v5): L/100 km while moving, L/hour idling
  fuel_moving real, fuel_idle real,
  -- per-vehicle sampling and event limits (v6); empty = use the defaults
  moving_interval int, stopped_interval int, brake_limit real, accel_limit real,
  -- settings panel (v8)
  custom boolean default false,                 -- true = this vehicle overrides the fleet defaults (shows the "Custom" badge)
  min_moving_distance numeric default 0,        -- km below which fuel figures are not calculated
  speed_buffer numeric default 10,              -- km/h of margin above the limit before an overspeed alert
  max_accel_warning numeric default 3);         -- km/h per second, warning threshold shown on the phone

create table if not exists drivers (
  id uuid primary key default gen_random_uuid(),
  name text not null,
  phone text,
  created_at timestamptz default now(),
  -- archive (v4)
  employee_id text, license_no text, license_expiry date, nationality text, notes text);

create table if not exists assignments (
  user_id uuid primary key references auth.users on delete cascade,
  vehicle_id uuid references vehicles on delete set null,
  driver_id  uuid references drivers  on delete set null);

create table if not exists positions (
  id bigint generated always as identity primary key,
  user_id uuid not null, vehicle_id uuid, driver_id uuid,
  vehicle_name text, driver_name text,
  lat double precision not null, lng double precision not null,
  speed double precision default 0,              -- km/h
  recorded_at timestamptz not null default now(),
  -- telemetry (v4)
  accuracy real, altitude real, heading real,
  ignition boolean, accel real,
  battery_pct real, charging boolean, battery_v real);

-- Indexes live together after the events table, which they also cover.

create table if not exists events (
  id bigint generated always as identity primary key,
  user_id uuid not null, vehicle_id uuid, driver_id uuid,
  vehicle_name text, driver_name text,
  type text not null,                            -- harsh_braking | harsh_acceleration | overspeed
  lat double precision, lng double precision,
  speed double precision,
  value double precision,                        -- km/h per second, or km/h when overspeed
  recorded_at timestamptz not null default now(),
  details text);                                 -- human-readable wording from the phone

-- Indexes for the hot queries (live list, history, event reports). Guarded by
-- definition, so re-running this file never duplicates the unnamed indexes
-- Postgres created when the original migrations first ran.
do $$ begin
  if not exists (select 1 from pg_indexes where schemaname='public' and tablename='positions' and indexdef ilike '%(vehicle_id%, %recorded_at%desc%') then
    create index positions_vehicle_time on positions (vehicle_id, recorded_at desc);
  end if;
  if not exists (select 1 from pg_indexes where schemaname='public' and tablename='positions' and indexdef ilike '%(user_id%, %recorded_at%desc%') then
    create index positions_user_time on positions (user_id, recorded_at desc);
  end if;
  if not exists (select 1 from pg_indexes where schemaname='public' and tablename='events' and indexdef ilike '%(vehicle_id%, %recorded_at%desc%') then
    create index events_vehicle_time on events (vehicle_id, recorded_at desc);
  end if;
  if not exists (select 1 from pg_indexes where schemaname='public' and tablename='events' and indexdef ilike '%(type%, %recorded_at%desc%') then
    create index events_type_time on events (type, recorded_at desc);
  end if;
end $$;

-- thresholds the fleet may change from the dashboard (v4, beep added in v5)
create table if not exists event_rules (
  type text primary key, label text, enabled boolean default true,
  threshold double precision, alert boolean default true, beep boolean default true,
  -- buzzer timing (v8)
  beep_duration int default 5,                  -- seconds the buzzer sounds
  beep_type text default 'discontinuous');      -- 'continuous' | 'discontinuous'

-- fleet-wide sampling intervals (v6)
create table if not exists app_settings (key text primary key, value text not null);

-- geofences (v4) — table exists; no app screen uses it yet
create table if not exists geofences (
  id uuid primary key default gen_random_uuid(),
  name text not null, shape jsonb not null, color text default '#2f7fe6',
  enabled boolean default true, created_at timestamptz default now());

-- --------------------------------------------------- additive columns ----
-- The same columns, restated as idempotent ALTERs. create-table-if-not-exists
-- above does nothing to a table that already exists, so without this block a
-- project that only ran some of the old migrations would stay incomplete.
-- Safe to run on your live database: every column is already there, so each
-- statement is skipped with a NOTICE.
alter table vehicles add column if not exists type text, add column if not exists make_model text,
  add column if not exists year int, add column if not exists vin text, add column if not exists fuel_type text,
  add column if not exists notes text, add column if not exists speed_limit int,
  add column if not exists fuel_moving real, add column if not exists fuel_idle real,
  add column if not exists moving_interval int, add column if not exists stopped_interval int,
  add column if not exists brake_limit real, add column if not exists accel_limit real,
  add column if not exists custom boolean default false,
  add column if not exists min_moving_distance numeric default 0,
  add column if not exists speed_buffer numeric default 10,
  add column if not exists max_accel_warning numeric default 3;

alter table drivers add column if not exists employee_id text, add column if not exists license_no text,
  add column if not exists license_expiry date, add column if not exists nationality text,
  add column if not exists notes text;

alter table positions add column if not exists accuracy real, add column if not exists altitude real,
  add column if not exists heading real, add column if not exists ignition boolean,
  add column if not exists accel real, add column if not exists battery_pct real,
  add column if not exists charging boolean, add column if not exists battery_v real;

alter table events add column if not exists details text;

alter table event_rules add column if not exists beep boolean default true,
  add column if not exists beep_duration int default 5,
  add column if not exists beep_type text default 'discontinuous';

-- ------------------------------------------------------ seed the defaults ----
insert into event_rules (type, label, enabled, threshold, alert) values
  ('harsh_braking',     'Harsh braking',     true, 11,  true),   -- km/h per second (v5 units)
  ('harsh_acceleration','Harsh acceleration', true,  9,  true),  -- km/h per second
  ('overspeed',         'Overspeed',         true, 100,  true)   -- km/h
  on conflict (type) do nothing;

insert into app_settings (key, value) values ('moving_interval','10'), ('stopped_interval','60')
  on conflict (key) do nothing;

-- ------------------------------------------------------------ views/procs ----
-- one row per tracking phone: its most recent position
create or replace view latest_positions with (security_invoker = true) as
  select distinct on (user_id) * from positions order by user_id, recorded_at desc;

-- auto-create the profile row when somebody signs up
create or replace function handle_new_user() returns trigger language plpgsql security definer set search_path = public as $$
begin insert into profiles(id,email) values (new.id,new.email); return new; end $$;

drop trigger if exists on_signup on auth.users;
create trigger on_signup after insert on auth.users for each row execute function handle_new_user();

-- is_admin() drives every policy below; security definer so it can read profiles
create or replace function is_admin() returns boolean language sql security definer stable set search_path = public as
$$ select exists(select 1 from profiles where id = auth.uid() and role = 'admin') $$;

-- fleet report computed in the database (no 1000-row limit, fast)
create or replace function vehicle_report(p_from timestamptz, p_to timestamptz, p_tz text default 'UTC')
returns table(vehicle_id uuid, vehicle_name text, day date, km numeric, max_speed numeric, drive_min numeric, stop_min numeric)
language sql stable security invoker as $$
 with p as (select vehicle_id, vehicle_name, recorded_at, speed, lat, lng,
   lag(lat) over w plat, lag(lng) over w plng, lag(recorded_at) over w pt
   from positions where recorded_at between p_from and p_to and positions.vehicle_id is not null
   window w as (partition by positions.vehicle_id order by recorded_at)),
 g as (select *, extract(epoch from recorded_at - pt) dt,
   6371*2*asin(sqrt(power(sin(radians(lat-plat)/2),2)+cos(radians(plat))*cos(radians(lat))*power(sin(radians(lng-plng)/2),2))) d from p)
 select g.vehicle_id, max(g.vehicle_name), (g.recorded_at at time zone p_tz)::date,
  round(coalesce(sum(case when dt<=180 then d end),0)::numeric,2),
  round(coalesce(max(speed),0)::numeric),
  round((coalesce(sum(case when dt<=180 and speed>0 then dt end),0)/60)::numeric,1),
  round((coalesce(sum(case when dt<=180 and speed=0 then dt end),0)/60)::numeric,1)
 from g group by g.vehicle_id, 3 order by 3 $$;

-- ------------------------------------------------------- row level security ----
-- Final rules: admins see and edit everything; a normal user sees only the
-- vehicle/driver assigned to them and may only write rows for their own vehicle.
alter table profiles    enable row level security;
alter table vehicles    enable row level security;
alter table drivers     enable row level security;
alter table assignments enable row level security;
alter table positions   enable row level security;
alter table events      enable row level security;
alter table event_rules enable row level security;
alter table app_settings enable row level security;
alter table geofences   enable row level security;

drop policy if exists "profiles read"  on profiles;
create policy "profiles read"  on profiles for select using (id = auth.uid() or is_admin());
drop policy if exists "profiles admin" on profiles;
create policy "profiles admin" on profiles for all using (is_admin()) with check (is_admin());

drop policy if exists "vehicles read"  on vehicles;
create policy "vehicles read"  on vehicles for select using (is_admin() or id in (select vehicle_id from assignments where user_id = auth.uid()));
drop policy if exists "vehicles admin" on vehicles;
create policy "vehicles admin" on vehicles for all using (is_admin()) with check (is_admin());

drop policy if exists "drivers read"   on drivers;
create policy "drivers read"   on drivers  for select using (is_admin() or id in (select driver_id from assignments where user_id = auth.uid()));
drop policy if exists "drivers admin"  on drivers;
create policy "drivers admin"  on drivers  for all using (is_admin()) with check (is_admin());

drop policy if exists "assign read"    on assignments;
create policy "assign read"    on assignments for select using (user_id = auth.uid() or is_admin());
drop policy if exists "assign admin"   on assignments;
create policy "assign admin"   on assignments for all using (is_admin()) with check (is_admin());

-- Two policies from the first version were replaced in v5. Dropping them by
-- their old names too makes this file a complete repair script even if a
-- project stopped part-way through the old migration sequence.
drop policy if exists "pos admin read"    on positions;
drop policy if exists "events admin read" on events;

drop policy if exists "pos insert own" on positions;
create policy "pos insert own" on positions for insert with check (
  user_id = auth.uid() and vehicle_id in (select vehicle_id from assignments where user_id = auth.uid()));
drop policy if exists "positions read" on positions;
create policy "positions read" on positions for select using (
  is_admin() or vehicle_id in (select vehicle_id from assignments where user_id = auth.uid()));

drop policy if exists "events insert own" on events;
create policy "events insert own" on events for insert with check (
  user_id = auth.uid() and vehicle_id in (select vehicle_id from assignments where user_id = auth.uid()));
drop policy if exists "events read"       on events;
create policy "events read"   on events for select using (
  is_admin() or vehicle_id in (select vehicle_id from assignments where user_id = auth.uid()));

drop policy if exists "rules read" on event_rules;
create policy "rules read" on event_rules for select using (auth.uid() is not null);
drop policy if exists "rules admin" on event_rules;
create policy "rules admin" on event_rules for all using (is_admin()) with check (is_admin());

drop policy if exists "settings read" on app_settings;
create policy "settings read" on app_settings for select using (auth.uid() is not null);
drop policy if exists "settings admin" on app_settings;
create policy "settings admin" on app_settings for all using (is_admin()) with check (is_admin());

drop policy if exists "geo read" on geofences;
create policy "geo read" on geofences for select using (auth.uid() is not null);
drop policy if exists "geo admin" on geofences;
create policy "geo admin" on geofences for all using (is_admin()) with check (is_admin());

-- ------------------------------------------------------------- realtime ----
-- the dashboard listens for position and event inserts; both must be in the
-- publication. Guarded so re-running this file does not error.
do $$ begin
  if not exists (select 1 from pg_publication_tables where pubname='supabase_realtime' and schemaname='public' and tablename='positions') then
    alter publication supabase_realtime add table positions;
  end if;
  if not exists (select 1 from pg_publication_tables where pubname='supabase_realtime' and schemaname='public' and tablename='events') then
    alter publication supabase_realtime add table events;
  end if;
end $$;

-- ------------------------------------------------------------- clean-up ----
-- hdop was added in v4 and never written by the phone or read by the dashboard.
alter table positions drop column if exists hdop;

-- ============================================================================
-- ONE-OFF STEPS (not part of the schema above)
--
-- 1. Make your own account the first admin, after signing in once:
--      update profiles set role='admin' where email='YOUR@EMAIL.COM';
--
-- 2. Supabase project settings the apps depend on:
--      Authentication > Providers > Email: turn OFF "Confirm email"
--      (the dashboard creates accounts with a synthetic @fleet.local address,
--       so confirmation and password-reset emails cannot be delivered).
--
-- 3. Optional history purge to protect the free 500 MB limit
--    (enable pg_cron first: Database > Extensions):
--      select cron.schedule('purge-positions','0 3 * * *',
--        $$delete from positions where recorded_at < now() - interval '30 days'$$);
-- ============================================================================

-- ============================================================================
-- CHANGE LOG — the lines from the old migration files that are deliberately
-- NOT in the statements above. Nothing was lost: each one is either replaced by
-- a later decision (so the final state is what you run) or folded into a seed.
--
-- from schema.sql (v1)
--   create policy "vehicles read" ... using (auth.uid() is not null)   -> replaced by v5 scope rule
--   create policy "drivers read"  ... using (auth.uid() is not null)   -> replaced by v5 scope rule
--   create policy "pos insert own" ... (user_id = auth.uid())          -> tightened by v7
--   create policy "pos admin read" ... using (is_admin())              -> replaced by "positions read" (v5)
--   comment: cron purge of positions after 90 days, job 'purge-old-positions'
--                                                                        -> one 30-day job above (v4 wording won)
-- from migration-advanced.sql
--   create policy "events insert own" ... (user_id = auth.uid())       -> tightened by v7
--   create policy "events admin read" ... using (is_admin())           -> replaced by "events read" (v5)
-- from migration-v4.sql
--   positions.hdop real                                                 -> nothing ever wrote or read it; dropped in the clean-up section below
--   event_rules seed thresholds 3.0 braking / 2.5 accel (m/s2)          -> v5 restated them as 11 / 9 km/h per second, which is what the seed above uses
--   comment: 1-second data fills the free 500 MB fast                   -> kept as the purge note above
-- from migration-v5.sql
--   alter table event_rules add column beep ...                        -> folded into create table event_rules
--   comment: "alert" now means visual alert only                       -> kept as the alert/beep column names in the dashboard Configuration table
--   update event_rules set threshold = 11 / 9                          -> folded into the seed above (fresh installs only; it never overwrites thresholds you edited)
-- from migration-v6.sql
--   insert into app_settings values ('moving_interval','10'), ('stopped_interval','60')
--                                                                      -> same values, written as an upsert-safe insert above
-- from migration-v7.sql
--   drop policy if exists "pos insert own" / "events insert own"       -> these drops are still here, immediately before the re-create
-- from migration-v8.sql (settings panel, "Categories A-G" work in progress)
--   vehicles.custom boolean default false                              -> in create table vehicles AND the additive-columns block
--   vehicles.min_moving_distance / speed_buffer / max_accel_warning    -> same, with the same defaults (0 / 10 / 3)
--   event_rules.beep_duration int default 5                            -> same (seconds the buzzer sounds)
--   event_rules.beep_type text default 'discontinuous'                 -> same ('continuous' | 'discontinuous')
--   NOTE: no dashboard or phone code reads these six columns yet - the settings screens that were
--   planned around them are still to be built. They are kept here so the database is never behind.
-- ============================================================================
