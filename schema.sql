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
-- the safety score), the score_green/score_red settings, the user_activity
-- audit table behind the new Activity Log report and the admin seed
-- below. Everything else is skipped.
--
-- New in v12 (the trip-based Historical Tracking, Events Report and phone alert
-- update) -- running this file once adds:
--   events.ended_at plus the readings of the fix that opened the breach
--   (accuracy, altitude, heading, accel, battery, ignition, road_limit), so an
--   event shows Event End and Duration instead of only a start, and
--   event_rules.voice plus app_settings voice_on / voice_lang, which switch the
--   spoken safety warning per event, fleet-wide and by language,
--   the road_overspeed rule (the signed limit of the road being driven) — retired
--   again in v17, see the notes at the end of this file,
--   and the events update policy the phone needs to close the event it opened.
--
-- New in v13 (offline tile, per-event voice language, time on screen) -- adds:
--   user_activity.duration_s, the seconds each Session time entry holds, so the
--   Activity Log can show how long a login was actually inside the dashboard or
--   the app,
--   event_rules.voice_lang, one spoken-alert language per event,
--   app_settings offline_after_h, the hours of silence after which Live Tracking
--   greys a vehicle out as Offline.
--
-- New in v18 (account lockout and password reset) -- adds:
--   profiles.fails, locked_at and must_change, plus login_gate / login_fail /
--   login_ok / clear_pw_flag: five wrong passwords lock that account out of both
--   apps until an administrator presses Unlock on the Users page, and a password
--   the administrator sets for someone else has to be replaced by the user on
--   their next sign-in.
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
                                                 -- | zone_enter | zone_exit | zone_overspeed
  lat double precision, lng double precision,
  speed double precision,
  value double precision,                        -- km/h per second, or km/h when overspeed
  recorded_at timestamptz not null default now(),
  details text,                                  -- human-readable wording from the phone
  -- v12: the whole life of a breach. recorded_at stays the moment the limit was
  -- first broken, ended_at is written when it stopped, so the Events Report can
  -- show a duration. The readings belong to the breach-start fix, and
  -- road_limit is the signed limit that applied where the breach happened. Since
  -- v17 only a geofence has one of its own, so this column holds a zone's
  -- speed_limit; rows from the retired road lookup keep whatever they stored.
  ended_at timestamptz, accuracy real, altitude real, heading real,
  accel real, battery_pct real, charging boolean, ignition boolean,
  road_limit int);

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
  hold_s numeric default 0,                     -- seconds; the phone alert still fires immediately
  -- v12: speak the safety message for this event on the phone
  voice boolean default true);

-- fleet-wide sampling intervals (v6)
create table if not exists app_settings (key text primary key, value text not null);

-- geofences (v4) — table exists; no app screen uses it yet
create table if not exists geofences (
  id uuid primary key default gen_random_uuid(),
  name text not null, shape jsonb not null, color text default '#2f7fe6',
  enabled boolean default true, created_at timestamptz default now());

-- Audit trail (v10): every sign-in, configuration save, vehicle/driver/user
-- change, trip start/end and report export. Written by the account it describes
-- and readable only by an admin, so a driver cannot see or edit what was logged
-- about them. Names are copied in at write time; deleting a user later must not
-- erase the record of what that user did, so there is no foreign key here.
create table if not exists user_activity (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null, user_name text,
  action text not null, details text,
  device text,
  recorded_at timestamptz not null default now());
create index if not exists user_activity_time on user_activity (recorded_at desc);

-- v13: how long the user was actually in front of the screen. The dashboard and
-- the phone write one 'Session time' row each time they are left (sign out, tab
-- hidden, app backgrounded) with the seconds spent there, which the Activity Log
-- report then shows beside the entry.
alter table user_activity add column if not exists duration_s integer;
create index if not exists user_activity_user on user_activity (user_id, recorded_at desc);

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
alter table events add column if not exists ended_at timestamptz, add column if not exists accuracy real,
  add column if not exists altitude real, add column if not exists heading real, add column if not exists accel real,
  add column if not exists battery_pct real, add column if not exists charging boolean,
  add column if not exists ignition boolean, add column if not exists road_limit int;

-- v16: a breach opened while the phone has no signal still has to be closable later,
-- and the handset cannot know the server's row id until the insert lands. So the phone
-- names each event itself, in this column, and its closing update is looked up by that
-- name instead of by id. Additive and nullable: rows written by older builds simply
-- have none, and every existing id, report and policy keeps working.
alter table events add column if not exists cid uuid;
do $$ begin
  if not exists (select 1 from pg_indexes where schemaname='public' and tablename='events' and indexname='events_cid') then
    create unique index events_cid on events (cid);
  end if;
end $$;

alter table event_rules add column if not exists beep boolean default true,
  add column if not exists beep_duration int default 5,
  add column if not exists beep_type text default 'discontinuous',
  add column if not exists hold_s numeric default 0,
  add column if not exists voice boolean default true;

-- v13: each event now chooses its own spoken language instead of one fleet-wide
-- setting. 'off' keeps the event silent, otherwise it is a language code the
-- phone recognizes (en, ar, hi, ur). The old boolean is kept in step with it so
-- a phone that has not been rebuilt yet still behaves.
alter table event_rules add column if not exists voice_lang text;
update event_rules set voice_lang = case
    when voice then coalesce(nullif((select value from app_settings where key = 'voice_lang'), ''), 'en')
    else 'off' end
  where voice_lang is null;

-- ---------------------------------------------------- geofences, v14 use ----
-- A zone is a polygon drawn on the Live Tracking map. Besides its shape it carries
-- the speed limit that applies inside it and which breaches make an event: driving
-- in, driving out, or passing the zone's own limit. shape holds the ring the map
-- draws, as {"type":"polygon","pts":[[lat,lng],…]} — a rectangle is stored as its
-- four corners, so one reader handles both.
alter table geofences add column if not exists speed_limit int,
  add column if not exists enter boolean default true,
  add column if not exists exit boolean default true,
  add column if not exists overspeed boolean default true,
  add column if not exists updated_at timestamptz default now();

-- ------------------------------------------------------------ blackbox ----
-- v14: the phone now records every fix it gets (one per second) and keeps them on
-- the handset only — the server never sees that stream, so it stays small. When an
-- accident has to be investigated, an admin asks that vehicle for a window of time
-- and the phone pushes exactly the stored fixes in it as soon as it has a signal.
-- status is pending until the phone answers; n_rows says how many arrived, so an
-- empty or failed pull is visible instead of silently looking like no data.
create table if not exists bb_requests (
  id uuid primary key default gen_random_uuid(),
  vehicle_id uuid not null, vehicle_name text,
  from_ts timestamptz not null, to_ts timestamptz not null,
  status text not null default 'pending',        -- pending | sent | failed
  n_rows int, error text,
  requested_by uuid, requested_at timestamptz default now(), answered_at timestamptz);
create index if not exists bb_requests_vehicle on bb_requests (vehicle_id, requested_at desc);

-- Blackbox fixes are uploaded as ordinary position rows, flagged so the history
-- screen can tell a 1-per-second dump from the sampled stream the phone normally
-- sends. recorded_at is the moment the fix was taken, never the upload time.
alter table positions add column if not exists blackbox boolean default false;

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
  ('overspeed',         'Overspeed',         true, 100,  true, 10)    -- km/h
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

-- spoken safety warnings (v12): voice_on is the fallback switch and voice_lang
-- the fleet language. From v13 every event carries its own language in
-- event_rules.voice_lang, which wins; these two are what an event uses until it
-- has one, and what a phone built before v13 reads.
insert into app_settings (key, value) values ('voice_on','1'), ('voice_lang','en')
  on conflict (key) do nothing;

-- v13: Live Tracking shows a vehicle greyed out as Offline once it has been
-- silent for longer than this many hours. Editable from the Offline tile.
insert into app_settings (key, value) values ('offline_after_h','24')
  on conflict (key) do nothing;

-- v14: geofence breaches are events like any other, so each gets its own row in
-- the Configuration events table — alert, buzzer, voice and tolerance included.
-- zone_overspeed's threshold is the km/h allowed over a zone's own limit.
insert into event_rules (type, label, enabled, threshold, alert, hold_s) values
  ('zone_enter',    'Zone entered',    true, 0,  true, 0),
  ('zone_exit',     'Zone exited',     true, 0,  true, 0),
  ('zone_overspeed','Zone overspeed',  true, 10, true, 5)
  on conflict (type) do nothing;

-- v17: the road speed limit is retired. It read the maxspeed OpenStreetMap keeps on
-- a road through the public Overpass API, which answers slowly, rate-limits a phone
-- on mobile data, and hardly tags our roads at all — so the alert never fired. The
-- rule row is deleted rather than left switched off, because the Configuration page
-- builds itself from this table and must stop offering an event no phone produces.
delete from event_rules where type='road_overspeed';

-- v14: bb_on switches the phone's 1-per-second blackbox recording, bb_days is how
-- long the handset keeps it before overwriting the oldest fixes, zones_on lets an
-- admin stop all zone detection fleet-wide without deleting the zones, and places
-- is the JSON list of saved place names the dashboard writes
-- ([{n,la,lo,r},..]) — a name there beats every map service inside its radius.
insert into app_settings (key, value) values ('bb_on','1'), ('bb_days','3'), ('zones_on','1'), ('places','[]')
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
alter table bb_requests enable row level security;
alter table user_activity enable row level security;

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

-- v12: an event row is opened when a limit is broken and closed when it stops, so
-- the phone must be able to update its own rows. vehicle_id is still checked, so
-- an account can only ever close an event for a vehicle assigned to it.
drop policy if exists "events update own" on events;
create policy "events update own" on events for update to authenticated
  using (user_id = auth.uid())
  with check (user_id = auth.uid() and vehicle_id in (select vehicle_id from assignments where user_id = auth.uid()));

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

-- v14 blackbox pull requests. Only an admin may ask for a window of stored fixes
-- (insert) and see the whole list; a driver can only read and answer the requests
-- made about a vehicle assigned to them, which is what lets the phone mark its own
-- row done without being able to invent or rewrite anyone else's.
drop policy if exists "bb admin" on bb_requests;
create policy "bb admin" on bb_requests for all using (is_admin()) with check (is_admin());
drop policy if exists "bb read own" on bb_requests;
create policy "bb read own" on bb_requests for select to authenticated
  using (vehicle_id in (select vehicle_id from assignments where user_id = auth.uid()));
drop policy if exists "bb answer own" on bb_requests;
create policy "bb answer own" on bb_requests for update to authenticated
  using (vehicle_id in (select vehicle_id from assignments where user_id = auth.uid()))
  with check (vehicle_id in (select vehicle_id from assignments where user_id = auth.uid()));

-- Audit trail: an account may only write entries about itself, and only an admin
-- can read them. There is deliberately no update or delete policy, so nobody —
-- not even an admin — can alter or erase what was logged.
drop policy if exists "activity insert own" on user_activity;
create policy "activity insert own" on user_activity for insert to authenticated with check (user_id = auth.uid());
drop policy if exists "activity admin read" on user_activity;
create policy "activity admin read" on user_activity for select to authenticated using (is_admin());

-- ------------------------------------------------------ v15 write hardening ----
-- The insert rules above prove an account owns a row by its user_id and its
-- vehicle_id, but nothing stopped it from naming a DIFFERENT driver on that row, or
-- from inventing an event type the Configuration page cannot switch off. Every
-- report reads those copied columns straight off the row, so a driver holding the
-- public key could have put words in someone else's mouth.
--
-- Two helpers do the checks a policy cannot express directly: inside a policy
-- subquery a bare column name resolves to the inner table first, so writing
-- "a.driver_id = driver_id" would quietly compare an assignment with itself. Both
-- read the assignment table as security definer, which also keeps them independent
-- of the caller's own row-level policies.
create or replace function owns_vehicle(vid uuid) returns boolean
  language sql security definer stable set search_path = public as
$$ select vid is not null and exists
     (select 1 from assignments where user_id = auth.uid() and vehicle_id = vid) $$;

create or replace function owns_pair(vid uuid, did uuid) returns boolean
  language sql security definer stable set search_path = public as
$$ select did is null or exists
     (select 1 from assignments where user_id = auth.uid() and vehicle_id = vid and driver_id = did) $$;

revoke all on function owns_vehicle(uuid) from public;
revoke all on function owns_pair(uuid, uuid) from public;
grant execute on function owns_vehicle(uuid) to authenticated, anon, service_role;
grant execute on function owns_pair(uuid, uuid) to authenticated, anon, service_role;

-- The copied names are not checked, they are rewritten: the database puts the real
-- name of the vehicle and driver on every row as it lands. A forged name cannot
-- survive, and a rename or a phone still holding last week's cached name cannot
-- make an upload fail — losing a fix would cost more than the label is worth.
create or replace function stamp_names() returns trigger language plpgsql as $$
begin
  if new.vehicle_id is null then new.vehicle_name := null;
  else select name into new.vehicle_name from vehicles where id = new.vehicle_id; end if;
  if new.driver_id is null then new.driver_name := null;
  else select name into new.driver_name from drivers where id = new.driver_id; end if;
  return new;
end $$;

drop trigger if exists names_stamp on positions;
create trigger names_stamp before insert on positions for each row execute function stamp_names();
drop trigger if exists names_stamp on events;
create trigger names_stamp before insert on events for each row execute function stamp_names();

drop policy if exists "pos insert own" on positions;
create policy "pos insert own" on positions for insert with check (
  user_id = auth.uid() and owns_vehicle(vehicle_id) and owns_pair(vehicle_id, driver_id));

-- Every event type the phone can produce. A row of any other type is refused, so
-- nobody can invent a category the Configuration page cannot switch off.
drop policy if exists "events insert own" on events;
create policy "events insert own" on events for insert with check (
  user_id = auth.uid() and owns_vehicle(vehicle_id) and owns_pair(vehicle_id, driver_id)
  and type in ('harsh_braking','harsh_acceleration','overspeed',
               'zone_enter','zone_exit','zone_overspeed'));

-- An account may only close a breach on a row it already owns, and only one that
-- is still open. A policy can only look at the row as it is being written, so the
-- columns that must not move are pinned by the trigger below instead: opening an
-- event, then filling in its end time, peak and duration is all a phone may do.
drop policy if exists "events update own" on events;
create policy "events update own" on events for update to authenticated
  using (user_id = auth.uid() and ended_at is null)
  with check (user_id = auth.uid());

create or replace function guard_events() returns trigger language plpgsql as $$
begin
  if is_admin() then return new; end if;
  if new.id          is distinct from old.id
  or new.cid         is distinct from old.cid
  or new.user_id     is distinct from old.user_id
  or new.vehicle_id  is distinct from old.vehicle_id
  or new.driver_id   is distinct from old.driver_id
  or new.type        is distinct from old.type
  or new.recorded_at is distinct from old.recorded_at then
    raise exception 'an event row cannot be rewritten';
  end if;
  return new;
end $$;

drop trigger if exists events_guard on events;
create trigger events_guard before update on events for each row execute function guard_events();

-- A log entry may only ever be about the account writing it, and only with a
-- sensible action label. There is still no update or delete policy above, so the
-- audit trail stays append-only for everyone — admin included.
drop policy if exists "activity insert own" on user_activity;
create policy "activity insert own" on user_activity for insert to authenticated
  with check (user_id = auth.uid() and action is not null and length(action) <= 80);

-- --------------------------------------------------------- v18 lockout ----
-- Five wrong passwords in a row lock that account until an administrator unlocks
-- it on the Users page. The counter has to be reachable from the sign-in screen,
-- where nobody is logged in yet, so these functions are security definer: each
-- touches one row of profiles by its own address and nothing else. They answer
-- identically for an address that has never signed in here, so the sign-in screen
-- cannot be walked through to find out which users exist.
alter table profiles add column if not exists fails int not null default 0,
  add column if not exists locked_at timestamptz,
  add column if not exists must_change boolean not null default false;

-- asked before a sign-in is attempted: is this account already locked?
create or replace function login_gate(p_email text) returns table(locked boolean, tries_left int)
language sql security definer set search_path = public as $$
  -- the aggregates keep one row on the screen even when nothing matches, which is
  -- what makes an unknown address answer exactly like an unlocked one
  select coalesce(bool_or(locked_at is not null), false),
         coalesce(greatest(5 - max(fails), 0), 5)
  from profiles where lower(email) = lower(p_email)
$$;

-- one wrong password. Returns what the screen should say next.
create or replace function login_fail(p_email text) returns table(locked boolean, tries_left int)
language plpgsql security definer set search_path = public as $$
declare n int;
begin
  update profiles set fails = fails + 1 where lower(email) = lower(p_email) returning fails into n;
  if n is null then return query select false, 5; return; end if;
  if n >= 5 then
    update profiles set locked_at = now() where lower(email) = lower(p_email) and locked_at is null;
    return query select true, 0;
  end if;
  return query select false, greatest(5 - n, 0);
end $$;

-- a sign-in that worked clears the count. It never clears a lock: only the
-- administrator's Unlock button does, so a lock cannot be typed away.
create or replace function login_ok(p_email text) returns void
language sql security definer set search_path = public as $$
  update profiles set fails = 0 where lower(email) = lower(p_email)
$$;

-- the account itself, and only the account itself, puts down the "the
-- administrator reset my password" flag once it has chosen a new password. An
-- ordinary user has no update policy on profiles, so this is the one narrow way.
create or replace function clear_pw_flag() returns void
language sql security definer set search_path = public as $$
  update profiles set must_change = false, fails = 0 where id = auth.uid()
$$;

revoke all on function login_gate(text), login_fail(text), login_ok(text), clear_pw_flag() from public;
grant execute on function login_gate(text), login_fail(text), login_ok(text), clear_pw_flag() to anon, authenticated, service_role;

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
  -- v14: the phone listens for a blackbox request so a pull starts the moment an
  -- admin presses Download; it also polls, so a handset that was offline still
  -- answers the request when it next gets a signal.
  if not exists (select 1 from pg_publication_tables where pubname='supabase_realtime' and schemaname='public' and tablename='bb_requests') then
    alter publication supabase_realtime add table bb_requests;
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
-- new in this file (v12, trip history, event duration, voice warnings)
--   events gains ended_at and a full snapshot of the fix that opened the breach
--        (accuracy, altitude, heading, accel, battery_pct, charging, ignition, road_limit). The phone writes all of
--        it when the limit is broken and only edits ended_at / value / details when it stops, which is what gives the
--        Events Report its Event End and Duration columns.
--   policy "events update own"                                       -> without it the phone could insert an event
--        but never close it again.
--   event_rules.voice (true)                                           -> per-event tick on the Configuration page;
--        the driver app speaks the safety warning for that event in the chosen language.
--   app_settings keys 'voice_on' (1) and 'voice_lang' (en)              -> the fleet-wide switch and the language
--        (en, ar, hi or ur) for those spoken warnings.
--   seeded event_rules row 'road_overspeed' (threshold 10 km/h, hold 5 s)  -> a new event type: the phone looked up
--        the maxspeed signed on the road it was driving on (OpenStreetMap data). v17 retired it again — that source
--        cannot be relied on without an API key, so the rule row is deleted and the type is no longer accepted.
-- v13, on top of the above:
--   user_activity.duration_s                                           -> the Activity Log's Time on screen column.
--        Both apps write a 'Session time' row (sign out, tab hidden, app backgrounded) holding the seconds spent
--        there; without the column those rows land as ordinary entries with no length.
--   event_rules.voice_lang                                             -> one voice per event (Off, English,
--        Arabic, Hindi or Urdu) chosen on the Configuration page; the two app_settings keys above become the
--        fallback only.
--   app_settings key 'offline_after_h' (24)                            -> Live Tracking greys a vehicle out as
--        Offline after this many hours without a position; the number is editable in the Offline tile itself.
-- v14, on top of the above:
--   geofences gains speed_limit / enter / exit / overspeed / updated_at  -> zones are no longer a dead table. An
--        admin draws them on the Live Tracking map, gives each a speed limit, and chooses whether driving in, out or
--        over the limit makes an event. The phone reads the shapes and evaluates every fix against them.
--   seeded event_rules rows 'zone_enter', 'zone_exit', 'zone_overspeed'  -> the three new event types, so they
--        appear in the Configuration events list with their own alert, buzzer, voice and tolerance settings.
--   bb_requests table + 3 policies + publication entry                   -> the blackbox pull: an admin asks a
--        vehicle for a window of its locally stored fixes, the phone answers it (realtime or its own poll) and
--        reports how many rows arrived. A driver can only see and answer requests about an assigned vehicle.
--   positions.blackbox (false)                                           -> marks a fix that came from the
--        blackbox dump rather than the normal sampled stream.
--   app_settings keys 'bb_on' (1), 'bb_days' (3), 'zones_on' (1)          -> blackbox recording on/off, how many
--        days the handset keeps before overwriting the oldest fixes, and a fleet-wide switch for zone detection.
--   app_settings key 'places' ('[]')                                      -> the saved place names. A JSON list of
--        {n,la,lo,r} the admin edits on the Configuration page; any position inside r metres of la,lo is reported
--        with that name instead of whatever the map services call it. Written only by an admin, read by everyone.
--
-- v15, on top of the above (write hardening — no new tables, nothing to migrate):
--   owns_vehicle / owns_pair functions                                    -> the two checks a policy subquery
--        cannot express on its own. They prove the vehicle and the driver on a written row really belong to the
--        account writing it.
--   names_stamp trigger on positions and events                           -> the database writes the true
--        vehicle_name / driver_name onto every row as it lands, so a forged name cannot be stored and a renamed
--        vehicle never makes an upload fail.
--   positions and events insert policies                                  -> use both helpers, and an event may
--        only be one of the seven types the phone can produce. Before this, an account holding the public key
--        could have stored a position or an event under ANOTHER driver's name on a vehicle it is assigned to.
--   events update policy + events_guard trigger                           -> a phone may only close its own breach
--        that is still open: id, vehicle, driver, type and start time are pinned by the trigger, so an event
--        cannot be moved onto another vehicle or rewritten after the fact.
--   user_activity insert policy                                           -> an entry must be about the account
--        writing it and the action label kept short. The trail is still append-only for everyone, admin included.
--
-- v16, on top of the above (one new column, nothing rewritten):
--   events.cid (uuid, unique, nullable)                                  -> the name a handset gives its own
--        breach. A phone that opened an event while it had no signal cannot know the server's row id, so it writes
--        this uuid with the insert and looks the row up by it when the breach ends. Rows from older builds have
--        none and are read, reported and closed exactly as before.
--   guard_events trigger                                                 -> also pins cid, so a close cannot
--        re-point an event at another name.
--   Run this file BEFORE installing the rebuilt phone app: the new build writes cid, and an insert would be
--   refused by a database that does not have the column yet.
--
-- v17, on top of the above (one event type retired, nothing added):
--   deletes the 'road_overspeed' rule row                                -> the phone read the signed limit of the
--        road it was on from OpenStreetMap through the public Overpass API. There is no key-free source that answers
--        dependably: the public endpoints queue a handset on mobile data, answer slowly or not at all, and our roads
--        are hardly tagged, so the alert never fired. Every part of it is gone from both apps - the lookup, the
--        speed-sign badge on the driver screen, the rule and its Configuration row.
--   events insert policy                                                 -> 'road_overspeed' is no longer an accepted
--        type. Rows an older build already stored are read, reported and exported exactly as before.
--   Overspeed (the vehicle's own limit) and Zone overspeed (a geofence's limit) are untouched and keep working.
--
-- v18, on top of the above (account lockout and password reset):
--   profiles.fails (0), locked_at (null), must_change (false)             -> the five-wrong-password lock. fails
--        counts the wrong passwords typed against this address, locked_at is the moment it reached five, and
--        must_change is raised when an administrator sets a password for somebody else so that password has to be
--        replaced on the next sign-in.
--   login_gate / login_fail / login_ok                                    -> the three calls the sign-in screens
--        make: ask before attempting, record a wrong password, clear the count after a good one. Definer functions
--        because the sign-in screen is not logged in yet, and written so an address that has never signed in here
--        answers exactly like an unlocked account.
--   clear_pw_flag()                                                       -> the only update an ordinary account may
--        make to its own profile: put down the must_change flag once it has chosen its new password.
--   Unlock needs no new rights — the existing "profiles admin" policy already lets an administrator clear
--   fails/locked_at. Setting a NEW password for another account does: the public key cannot do that, so the Users
--   page calls the fleet-admin Edge function (functions/fleet-admin/index.ts), which checks that the caller really
--   is an administrator before it uses the server-only key Supabase gives it. Deploy that function or the button
--   reports that the service is missing; everything else in the lock works without it.
-- ============================================================================
