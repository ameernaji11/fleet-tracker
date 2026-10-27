-- ============================================================================
-- Fleet Tracker — complete database schema (Supabase > SQL Editor > New query)
--
-- This single file replaces schema.sql + migration-v4/v5/v6/v7/v8 + migration-advanced.
-- It is written to be SAFE TO RUN TWICE: everything is if-not-exists /
-- create-or-replace / drop-policy-then-create, so it works both as a fresh
-- install on a new project and as a repair script on an already-migrated one.
--
-- Your live project (jdaqacacdihkoliyeiao) is at the v8 state. Running this file
-- once adds only what is new: the hdop clean-up (which rebuilds the
-- latest_positions view), event_rules.hold_s (the tolerance window the
-- Configuration page now saves), vehicle_report.over_s (overspeed seconds for
-- the safety score), the score_green/score_red settings and the admin seed
-- below. Everything else is skipped.
--
-- The "admin seed" block makes ameer@fleettrack.com role=admin, so the separate
-- Admin.sql saved query in Supabase is no longer needed — delete it once this
-- file has run and you have confirmed: select email, role from profiles;
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
  min_moving_distance numeric default 0,        -- metres the vehicle must travel in a trip before events are detected
  speed_buffer numeric default 10,              -- km/h of margin above the limit before the overspeed alert starts
  max_accel_warning numeric default 3);         -- km/h per second: warns the driver without recording an event

create table if not exists drivers (
  id uuid primary key default gen_random_uuid(),
  name text not null,
  phone text,
  created_at timestamptz default now(),
  -- archive (v4)
  employee_id text, license_no text, license_expiry date, nationality text, notes text);

create table if not exists assignments (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users on delete cascade,
  vehicle_id uuid references vehicles on delete set null,
  driver_id  uuid references drivers  on delete set null,
  created_at timestamptz default now());

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
  beep_type text default 'discontinuous',       -- 'continuous' | 'discontinuous'
  -- tolerance window (v9): how long the limit must stay broken before the event is written
  hold_s numeric default 0);                    -- seconds; the phone alert still fires immediately

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
  add column if not exists beep_type text default 'discontinuous',
  add column if not exists hold_s numeric default 0;

-- ------------------------------------------------- assignments, many-to-one ----
-- v11: one login can now be linked to several vehicles and several drivers, so
-- user_id can no longer be the primary key — each grant is its own row.
alter table assignments add column if not exists id uuid default gen_random_uuid(),
  add column if not exists created_at timestamptz default now();
do $$
declare k text;
begin
  update assignments set id = gen_random_uuid() where id is null;
  select string_agg(a.attname, ',' order by a.attnum) into k
    from pg_constraint c join pg_attribute a on a.attrelid = c.conrelid and a.attnum = any (c.conkey)
   where c.conrelid = 'assignments'::regclass and c.contype = 'p';
  if k is distinct from 'id' then
    if k is not null then alter table assignments drop constraint assignments_pkey; end if;
    alter table assignments add constraint assignments_pkey primary key (id);
  end if;
end $$;
create index if not exists assignments_user on assignments (user_id);

-- ------------------------------------------------------ seed the defaults ----
insert into event_rules (type, label, enabled, threshold, alert, hold_s) values
  ('harsh_braking',     'Harsh braking',     true, 11,  true, 2),   -- km/h per second (v5 units)
  ('harsh_acceleration','Harsh acceleration', true,  9,  true, 2),  -- km/h per second
  ('overspeed',         'Overspeed',         true, 100,  true, 10)  -- km/h
  on conflict (type) do nothing;

-- give the tolerance window its 2 s / 10 s defaults exactly once on a project
-- that already had event_rules rows (the ALTER above can only add it as 0).
-- Later runs never touch it, so a tolerance you set from the dashboard survives.
do $$ begin
  if not exists (select 1 from app_settings where key='hold_s_seeded') then
    update event_rules set hold_s = case type when 'overspeed' then 10 else 2 end;
    insert into app_settings (key, value) values ('hold_s_seeded','1') on conflict (key) do nothing;
  end if;
end $$;

insert into app_settings (key, value) values ('moving_interval','10'), ('stopped_interval','60')
  on conflict (key) do nothing;

-- RAG cut-offs for the Fleet Summary safety score (v10). The score is a risk
-- index: at or below score_green it shows green, at or above score_red it shows
-- red, in between it shows amber. Both are editable from the Configuration page.
insert into app_settings (key, value) values ('score_green','1'), ('score_red','3')
  on conflict (key) do nothing;

-- ------------------------------------------------------------ views/procs ----
-- hdop was added in v4 and never written by the phone or read by the dashboard.
-- It must be removed BEFORE the view below is rebuilt, and the view must be
-- dropped first: latest_positions is defined with SELECT *, so Postgres bakes
-- hdop into it and refuses the column drop (2BP01), while CREATE OR REPLACE
-- VIEW can add columns but never remove one. Dropping and recreating the view
-- in the same run is safe — nothing else in this file or the apps depends on it.
drop view if exists latest_positions;
alter table positions drop column if exists hdop;

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
-- over_s: seconds spent above the vehicle's own speed_limit (100 km/h when the
-- vehicle has none). Sample gaps over 3 minutes are ignored, as everywhere else
-- in this function, so a parked overnight gap cannot become hours of overspeed.
-- vehicle_report gained a column, and Postgres refuses to change the return type
-- of an existing function ("cannot change return type of existing function"), so
-- the old one has to be dropped before it is recreated. Safe: nothing depends on
-- it, the dashboard calls it by name.
drop function if exists vehicle_report(timestamptz, timestamptz, text);
create or replace function vehicle_report(p_from timestamptz, p_to timestamptz, p_tz text default 'UTC')
returns table(vehicle_id uuid, vehicle_name text, day date, km numeric, max_speed numeric, drive_min numeric, stop_min numeric, over_s numeric)
language sql stable security invoker as $$
 with p as (select po.vehicle_id, po.vehicle_name, po.recorded_at, po.speed, po.lat, po.lng,
   coalesce(v.speed_limit, 100) lim,
   lag(po.lat) over w plat, lag(po.lng) over w plng, lag(po.recorded_at) over w pt
   from positions po left join vehicles v on v.id = po.vehicle_id
   where po.recorded_at between p_from and p_to and po.vehicle_id is not null
   window w as (partition by po.vehicle_id order by po.recorded_at)),
 g as (select *, extract(epoch from recorded_at - pt) dt,
   6371*2*asin(sqrt(power(sin(radians(lat-plat)/2),2)+cos(radians(plat))*cos(radians(lat))*power(sin(radians(lng-plng)/2),2))) d from p)
 select g.vehicle_id, max(g.vehicle_name), (g.recorded_at at time zone p_tz)::date,
  round(coalesce(sum(case when dt<=180 then d end),0)::numeric,2),
  round(coalesce(max(speed),0)::numeric),
  round((coalesce(sum(case when dt<=180 and speed>0 then dt end),0)/60)::numeric,1),
  round((coalesce(sum(case when dt<=180 and speed=0 then dt end),0)/60)::numeric,1),
  round(coalesce(sum(case when dt<=180 and speed>lim then dt end),0)::numeric,1)
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

-- ------------------------------------------------------------- admin seed ----
-- Promote the owner account. Runs after the tables/policies exist, and is safe
-- to re-run. If the profile does not exist yet (nobody has signed in with this
-- address), it reports that instead of failing, and the next run of this file
-- picks it up. Do NOT put a real password or token here — only this update.
do $$
declare n int;
begin
  update profiles set role='admin' where lower(email)=lower('ameer@fleettrack.com');
  get diagnostics n = row_count;
  if n = 0 then
    raise notice 'admin seed: no profile with email ameer@fleettrack.com yet. Existing profiles: %',
      (select coalesce(string_agg(email, ', '), 'none')
         from (select email from profiles order by email limit 8) x);
  else
    raise notice 'admin seed: ameer@fleettrack.com is now role=admin';
  end if;
end $$;

-- ============================================================================
-- ONE-OFF STEPS (not part of the schema above)
--
-- 1. Admin account: handled automatically by the "admin seed" block above
--    (ameer@fleettrack.com). On a brand-new project, sign in from the
--    dashboard once, then run this file again so the seed finds the profile.
--    To make the grant un-typo-able, use the stable id instead of the address:
--      update profiles set role='admin' where id='<uuid from auth.users>';
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
--   positions.hdop real                                                 -> nothing ever wrote or read it; dropped just before the latest_positions view is rebuilt
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
--   NOTE: these six columns were dead when v8 was written. They are now wired up (see v9 below).
-- new in this file (v9, dashboard Configuration rework) — nothing was removed to get here
--   event_rules.hold_s numeric default 0                               -> tolerance window in seconds: the phone alerts the
--        driver as soon as a limit is broken but only writes the event if it lasts longer than hold_s.
--        Seeded 2 s for harsh braking/acceleration and 10 s for overspeed, editable per event type.
--   event_rules.beep / beep_duration / beep_type                       -> the phone buzzer now honours them: on/off,
--        how many seconds, and 'continuous' (one long tone) or 'discontinuous' (beep-beep-beep).
--   vehicles.custom                                                    -> set by the dashboard when a vehicle keeps its own
--        values; renders the "Custom" badge and the "Use fleet defaults" reset (which nulls the overrides).
--   vehicles.speed_buffer / max_accel_warning / min_moving_distance    -> read by the phone: km/h added to the limit
--        before the overspeed alert starts, a lower acceleration level that only warns, and the metres a trip must
--        cover before events are detected (stops GPS jitter in a parked vehicle from making false events).
--   app_settings key 'hold_s_seeded'                                   -> marker so the one-off tolerance defaulting
--        above never overwrites values you set from the dashboard.
--   admin seed DO block (after the clean-up section)                   -> replaces the separate Admin.sql saved query in
--        Supabase: promotes ameer@fleettrack.com to role='admin', case-insensitively, and prints a NOTICE listing the
--        profiles it could not match instead of failing silently.
-- new in this file (v10, safety score as an IVMS-style RAG index)
--   vehicle_report gains an 8th output column over_s                   -> seconds above the vehicle's own speed_limit
--        (100 km/h when it has none), counted only across sample gaps of 3 minutes or less. The dashboard's safety
--        score is now (harsh accel events + harsh brake events + over_s) * (100 / km), so lower is safer.
--   app_settings keys 'score_green' (1) and 'score_red' (3)             -> the RAG cut-offs, editable from the
--        Configuration page: at or below score_green = green, at or above score_red = red, between = amber.
-- new in this file (v11, many vehicles and drivers per login)
--   assignments.id uuid primary key                                    -> user_id was the primary key, which limited a
--        login to one row. The DO block moves the key onto a generated id and leaves a plain index on user_id, so the
--        Users page can store several vehicles and several drivers for the same person. Existing rows are untouched.
--   assignments.created_at                                             -> ordering for the driver app's vehicle list.
-- ============================================================================
