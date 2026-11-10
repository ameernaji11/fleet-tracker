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
--   apps, and a password the administrator sets for someone else has to be replaced
--   by the user on their next sign-in.
--
-- New in v21 (the lockout stops lasting for ever) -- adds:
--   profiles.fails_at and locked_until, app_settings login_window_m / login_lock_m,
--   and login_cfg / login_sweep behind the three sign-in functions above. Wrong
--   passwords now only count while they keep coming: a quiet stretch puts the count
--   back to nothing, and a five-strike lockout expires by itself after the wait you
--   set on the Configuration page. A lock an administrator pressed on the Users page
--   has no expiry and still waits for his Unlock.
--
-- New in v22 (the camera stays on the phone) -- removes:
--   dms_live and dms_frame, the two tables v20 built so a supervisor could open a
--   driver's camera from the dashboard. Pushing those pictures to a browser never
--   worked dependably, so the button is gone from the dashboard and the driver starts
--   the same forward-camera watch himself from his own map. The events, rules and
--   settings the camera writes are all untouched; only the remote view retires.
--
-- New in v23 (a parked vehicle says much less, and the blackbox follows the wheels) -- adds:
--   stopped_interval 3600. A phone whose trip is running but whose vehicle is standing
--   still now sends one position an hour instead of one a minute, which is the box
--   already on the Configuration page, so any other wait you type there wins. Only a
--   database still holding the original 60 is moved; a number you chose is left alone.
--   app_settings bb_min_kmh (1) -- the blackbox stores a fix only from this speed up, so
--   a stationary shift stops filling the handset with positions nobody will ever ask for.
--
-- New in v24 (the store a clip is kept in) -- adds:
--   the dms-clips storage bucket and its three policies: a handset writes a picture of
--   its own driver into a folder named after its own account, and one may be read back
--   only by somebody who can already read the event it belongs to. No new service, no
--   new key — the same project's own file store.
--
-- New in v25 (five more things the camera can see, and the clip finally arrives) -- adds:
--   five event_rules rows: dms_yawning, dms_talking, dms_eating, dms_drinking and
--   dms_smoking. dms_smoking is seeded switched off, because what the shipped model can
--   actually see is a bare hand at the mouth rather than a cigarette. The events insert
--   policy takes fifteen types instead of ten.
--   the update policy widened by one column: a phone may now name the video of an event
--   it has already closed, which is the only way a camera alert ever has one, because
--   the picture is not finished until after the breach has. guard_events() holds the
--   line — on a closed row nothing but clip_path may move.
--
-- New in v26 (the Recording switch on each camera rule) -- adds:
--   event_rules.clip (true). The nine driver-camera rows of the Configuration page each
--   get their own switch for keeping a few seconds of picture with the event; off means
--   the alert, the row and the report all still happen and no film is made. Default true
--   is what every camera rule did before the switch existed. Vehicle rules ignore it.
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
  type text not null,                            -- any of the eighteen names the "events insert own"
                                                 -- policy at v31 allows: the six the vehicle and its
                                                 -- zones make, the nine the driver camera makes,
                                                 -- cam_off, which the driver's own button makes, and
                                                 -- the two that say when the handset stopped and
                                                 -- started seeing satellites again
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
-- vehicle has none). Sample gaps over 3 minutes are ignored - except for standing time,
-- see below - so a parked overnight gap cannot become hours of overspeed.
-- The one figure that cannot use that bound is standing time: a vehicle that reports
-- once an hour while parked (v23) is not missing data between two heartbeats, it is
-- sitting there, so stop_min accepts a gap up to that vehicle's own parked interval
-- plus room for the fix that did not arrive on time. Drive minutes, distance and
-- overspeed seconds keep the 3-minute bound, because a silence between two moving
-- samples is a phone that said nothing, and joining them would invent a straight line
-- across roads the vehicle never took in a straight line.
-- vehicle_report gained a column, and Postgres refuses to change the return type
-- of an existing function ("cannot change return type of existing function"), so
-- the old one has to be dropped before it is recreated. Safe: nothing depends on
-- it, the dashboard calls it by name.
drop function if exists vehicle_report(timestamptz, timestamptz, text);
create or replace function vehicle_report(p_from timestamptz, p_to timestamptz, p_tz text default 'UTC')
returns table(vehicle_id uuid, vehicle_name text, day date, km numeric, max_speed numeric, drive_min numeric, stop_min numeric, over_s numeric)
language sql stable security invoker as $$
 with st as (select coalesce(max(case when key = 'stopped_interval'
   then nullif(regexp_replace(value, '[^0-9]', '', 'g'), '')::int end), 3600) si from app_settings),
 p as (select po.vehicle_id, po.vehicle_name, po.recorded_at, po.speed, po.ignition, po.lat, po.lng,
   coalesce(v.speed_limit, 100) lim,
   least(86400, greatest(180, coalesce(v.stopped_interval, st.si, 3600) * 2 + 120)) si_gap,
   lag(po.lat) over w plat, lag(po.lng) over w plng, lag(po.recorded_at) over w pt
   from positions po left join vehicles v on v.id = po.vehicle_id cross join st
   where po.recorded_at between p_from and p_to and po.vehicle_id is not null
   window w as (partition by po.vehicle_id order by po.recorded_at)),
 g as (select *, extract(epoch from recorded_at - pt) dt,
   6371*2*asin(sqrt(power(sin(radians(lat-plat)/2),2)+cos(radians(plat))*cos(radians(lat))*power(sin(radians(lng-plng)/2),2))) d from p)
 -- v35: a handset now reports all day, so a vehicle that was shut off and parked at the depot
 -- overnight writes points of its own accord. Idling is engine-on standing time, and the phone
 -- says which of the two it was writing; a rest point (ignition false) is not idling and not
 -- the fuel burnt while it stood. A row from before the ignition answer says nothing either way
 -- and keeps counting exactly as it did, so no closed shift's report changes under anybody.
 select g.vehicle_id, max(g.vehicle_name), (g.recorded_at at time zone p_tz)::date,
  round(coalesce(sum(case when dt<=180 then d end),0)::numeric,2),
  round(coalesce(max(speed),0)::numeric),
  round((coalesce(sum(case when dt<=180 and speed>0 then dt end),0)/60)::numeric,1),
  round((coalesce(sum(case when dt<=si_gap and speed=0 and ignition is distinct from false then dt end),0)/60)::numeric,1),
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
-- Five wrong passwords in a row lock that account. An administrator can also set and
-- clear the same stamp by hand from the Users page, which is why login_gate can answer
-- "locked" for an account that has never typed a wrong password at all. The counter has
-- to be reachable from the sign-in screen, where nobody is logged in yet, so these
-- functions are security definer: each touches one row of profiles by its own address
-- and nothing else. They answer identically for an address that has never signed in
-- here, so the sign-in screen cannot be walked through to find out which users exist.
-- v21 below changed the two words "for ever" in this description: the count now decays
-- and a lockout now expires. The three functions are re-created there.
alter table profiles add column if not exists fails int not null default 0,
  add column if not exists locked_at timestamptz,
  add column if not exists must_change boolean not null default false;

-- These two are dropped rather than replaced, and that is not tidiness: a database that
-- has already run v21 holds the four-word answers, and Postgres refuses to change a
-- function's return type in place ("42P13: cannot change return type of existing
-- function"), which aborted the whole file here and stopped v22 onward ever running.
-- Dropped first, this file lands the same shape whether the lockout block it meets is the
-- old one or the new one, and v21 replaces both again below with the wider answers.
drop function if exists login_gate(text);
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
drop function if exists login_fail(text);
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

-- ------------------------------------------------------ v21 lockout expiry ----
-- v18 counted wrong passwords for ever: five of them spread over a week of forgetful
-- mornings locked a driver out as surely as five in a row, and the lock then waited for
-- somebody to notice. Both halves now expire by themselves. A quiet stretch puts the
-- count back to nothing, and a five-strike lockout buys its way out after a fixed wait.
-- An administrator's own Lock on the Users page keeps no expiry: that is a decision, not
-- a timeout, and only his Unlock ends it. Two columns carry the difference —
-- locked_until says how much of a lock is left, and a NULL in it says nobody but the
-- person who locked the account is going to open it.
alter table profiles add column if not exists fails_at timestamptz;
alter table profiles add column if not exists locked_until timestamptz;

-- Minutes of quiet before the wrong-password count starts again from nothing, and
-- minutes a five-strike lockout lasts. login_lock_m 0 means "until an administrator
-- unlocks it", which is the v18 behaviour for a fleet that wants it back.
insert into app_settings (key, value) values ('login_window_m','15'), ('login_lock_m','15')
  on conflict (key) do nothing;

-- The two durations, with this file's own numbers standing behind an empty or mistyped
-- box. The screen that asks for them is the sign-in screen, where a failed cast would be
-- a driver unable to reach his vehicle.
create or replace function login_cfg() returns table(win_m int, lock_m int)
language sql stable security definer set search_path = public as $$
  select greatest(1, coalesce(nullif(regexp_replace((select value from app_settings where key = 'login_window_m'), '[^0-9]', '', 'g'), '')::int, 15)),
         greatest(0, coalesce(nullif(regexp_replace((select value from app_settings where key = 'login_lock_m'),   '[^0-9]', '', 'g'), '')::int, 15))
$$;

-- Bring every account up to date before anything is asked about it. Two statements on
-- purpose: an expired lock clears itself and takes its count with it, while an aged
-- count clears only itself and leaves any lock alone. A row an administrator locked has
-- no expiry and a lock still running has one, so neither can be aged out of here by a
-- stale stamp.
create or replace function login_sweep() returns void
language plpgsql security definer set search_path = public as $$
declare w int;
begin
  update profiles set locked_at = null, locked_until = null, fails = 0, fails_at = null
   where locked_until is not null and locked_until <= now();
  select win_m into w from login_cfg();
  update profiles set fails = 0, fails_at = null
   where locked_at is null and fails > 0
     and (fails_at is null or fails_at < now() - make_interval(mins => w));
end $$;

-- Asked before a sign-in is attempted: is this account locked, how many tries has it
-- left, how many minutes are left on the lock, and how many minutes of quiet it would
-- take to put the count back to nothing. wait_min comes back NULL for an address that is
-- not locked and for one only an administrator can open, which is what lets both screens
-- say "contact the administrator" instead of promising a minute that will never arrive.
-- Both functions answer for a lock that has already run out as well: the sweep above
-- ends it first, so a driver who waited it out signs in on the very attempt that asks.
drop function if exists login_gate(text);
create or replace function login_gate(p_email text)
returns table(locked boolean, tries_left int, wait_min int, reset_min int)
language plpgsql security definer set search_path = public as $$
declare lk boolean; tr int; wm int; rm int; w int;
begin
  select win_m into w from login_cfg();
  perform login_sweep();
  -- the aggregates hold one row even when no address matches, which is what makes an
  -- address that has never signed in here answer exactly like an unlocked one
  select coalesce(bool_or(locked_at is not null), false),
         greatest(5 - coalesce(sum(fails), 0), 0),
         ceil(max(case when locked_until is null then null
                       else greatest(0, extract(epoch from locked_until - now()) / 60) end)::numeric)::int,
         case when max(fails_at) is null then null
              else greatest(0, ceil(w - extract(epoch from now() - max(fails_at)) / 60)::int) end
    into lk, tr, wm, rm
  from profiles where lower(email) = lower(p_email);
  return query select lk, tr, case when lk then wm end, rm;
end $$;

-- One wrong password. The count and its stamp move together, because fails_at is the
-- whole reason an old attempt stops counting. The fifth also starts the clock on the
-- lock, and the count is then left standing at five so that the Users page can still
-- tell a lockout apart from a lock somebody pressed by hand.
drop function if exists login_fail(text);
create or replace function login_fail(p_email text)
returns table(locked boolean, tries_left int, wait_min int, reset_min int)
language plpgsql security definer set search_path = public as $$
declare n int; l int; w int; lu timestamptz;
begin
  select win_m, lock_m into w, l from login_cfg();
  perform login_sweep();
  update profiles set fails = fails + 1, fails_at = now()
   where lower(email) = lower(p_email) returning fails into n;
  if n is null then return query select false, 5, null::int, null::int; return; end if;
  if n >= 5 then
    -- "and locked_at is null" leaves the row to whichever stamp came first: a driver
    -- cannot type over a lock, and cannot restart an administrator's one either
    update profiles set locked_at = now(),
      locked_until = case when l = 0 then null else now() + make_interval(mins => l) end
     where lower(email) = lower(p_email) and locked_at is null;
    select locked_until into lu from profiles where lower(email) = lower(p_email);
    return query select true, 0,
      case when lu is null then null
           else greatest(0, ceil(extract(epoch from lu - now()) / 60)::int) end, null;
  end if;
  return query select false, greatest(5 - n, 0), null, w;
end $$;

-- A sign-in that worked still clears the count, and still never clears a lock: only the
-- administrator's Unlock does, so a lock cannot be typed away. Since v21 it puts down the
-- stamp beside the count, because a try that no longer counts must not hold a countdown
-- on the screen either.
create or replace function login_ok(p_email text) returns void
language sql security definer set search_path = public as $$
  update profiles set fails = 0, fails_at = null where lower(email) = lower(p_email)
$$;

-- Choosing one's own password after an administrator set a temporary one puts down the
-- flag and the whole count with it, exactly as login_ok does.
create or replace function clear_pw_flag() returns void
language sql security definer set search_path = public as $$
  update profiles set must_change = false, fails = 0, fails_at = null where id = auth.uid()
$$;

-- Accounts already locked when this version arrives: a five-wrong-password lock gets
-- the expiry it would have had, so nobody has to be unlocked by hand just because the
-- schema caught up late. A lock with fewer than five tries behind it is one somebody
-- pressed on the Users page, and that one keeps waiting for him.
update profiles p set locked_until = greatest(now(), p.locked_at + make_interval(mins => c.lock_m))
  from login_cfg() c
  where c.lock_m > 0 and p.locked_at is not null and p.locked_until is null and p.fails >= 5;

revoke all on function login_cfg(), login_sweep(), login_gate(text), login_fail(text) from public;
-- login_sweep is handed to a signed-in account for one reason: the Users page shows
-- these numbers, and a count that aged out overnight should not still read as a warning
-- when an administrator opens that table. It only ever ends things whose time is up, so
-- nobody can use it to open a lock or clear one that still stands.
grant execute on function login_sweep() to authenticated;
grant execute on function login_gate(text), login_fail(text), login_ok(text), clear_pw_flag()
  to anon, authenticated, service_role;

-- --------------------------------------------------- v19 driver monitoring ----
-- The handset's forward camera watches the driver's face and four rules decide when
-- what it sees is a problem. Nothing is decided here: the picture is read on the
-- phone, so the database only ever receives a finished event. That is why these are
-- ordinary event_rules rows and get the same Recorded / Visual alert / Buzzer alert /
-- Voice alert switches and the same tolerance column as overspeed does — the
-- Configuration page shows them in one table with no special casing.
--
-- threshold and hold_s carry a different meaning for each rule, and the page says so
-- next to the row:
--   dms_fatigue      threshold = the percentage of the last second the eyes were shut
--                    (PERCLOS). hold_s = how long that may go on before the event.
--   dms_distraction  threshold = how far the head may turn away from the road, degrees.
--                    hold_s = how many seconds it may stay turned.
--   dms_phone_use    threshold = how much of the last second a phone was at this
--                    driver's face or at one of his ears, percent. hold_s = seconds it
--                    must last. From v25 the phone names the handset and puts it in
--                    front of the face or against an ear; on a handset whose model
--                    cannot report where it saw a thing this falls back to the older
--                    reading, which is how much of the lower face is covered by
--                    something. Either way it is the rule most likely to need tuning
--                    against your own cabs and light.
--   dms_cam_blocked  threshold is unused (the page prints "no limit"): no face in
--                    view, or the picture has stopped changing, and hold_s is how
--                    many seconds of that before the alert.
-- speed_min is the new column. An alert about a driver's face means nothing when the
-- vehicle is standing still, and a phone left on a seat with nobody in it would
-- otherwise raise a camera-blocked every few minutes in a depot. Only these four
-- rules read it; every other rule stays armed at any speed.
alter table event_rules add column if not exists speed_min numeric not null default 0;

-- do nothing on conflict, so thresholds you have tuned are never rolled back by
-- re-running this file — the same rule the older seeds follow.
insert into event_rules (type, label, enabled, threshold, alert, hold_s, speed_min) values
  ('dms_fatigue',     'Driver fatigued',   true, 40, true, 2,  10),
  ('dms_distraction', 'Driver distracted', true, 35, true, 3,  15),
  ('dms_phone_use',   'Phone in hand',     true, 50, true, 3,  15),
  ('dms_cam_blocked', 'Camera blocked',    true,  0, true, 15, 10)
  on conflict (type) do nothing;

-- ten types the camera and the vehicle produced from v19, and fifteen since v25 added
-- the five behaviours: the six the vehicle already made plus the nine the driver camera
-- does. A type outside this list cannot be written by a handset, which is what makes the
-- Configuration page the only place a category can exist.
drop policy if exists "events insert own" on events;
create policy "events insert own" on events for insert with check (
  user_id = auth.uid() and owns_vehicle(vehicle_id) and owns_pair(vehicle_id, driver_id)
  and type in ('harsh_braking','harsh_acceleration','overspeed',
               'zone_enter','zone_exit','zone_overspeed',
               'dms_fatigue','dms_distraction','dms_phone_use','dms_cam_blocked',
               'dms_yawning','dms_talking','dms_eating','dms_drinking','dms_smoking'));

-- Where the short video an event leaves behind is kept. The camera holds a rolling
-- buffer all trip and writes the seconds before and after the alert into it, so the
-- row points at a file instead of holding one. It is nullable and nothing writes it
-- until the monitoring build is on the phone; older rows and older builds are
-- unaffected. The clip bucket, and the rights that let a handset write into it and an
-- administrator read it back, arrive with the clip phase rather than here.
alter table events add column if not exists clip_path text;

-- One camera-wide decision each, so they live in app_settings rather than on every
-- rule row. dms_on is off by default: a build that has just learned to watch the
-- driver should not start doing it before you turn it on for the fleet.
insert into app_settings (key, value) values
  ('dms_on','0'),  ('dms_pre_s','3'),  ('dms_post_s','1'),
  ('dms_fps','5'), ('dms_keep_d','7'), ('dms_preview','1')
  on conflict (key) do nothing;

-- v20 built a remote live camera on two tables, dms_live and dms_frame, and v22 retires
-- both of them again: pushing a handset's picture to a browser was only ever answerable
-- while the driver's app sat on his screen with a strong signal, and in practice it never
-- worked. The camera now belongs to the phone alone - the driver presses his own video
-- button and the same on-device watch the trip starts begins. Nothing here is read or
-- written by either app any more, so the statements that created them are gone and the
-- v22 block near the end of this file drops whatever a v20 database still holds.
-- The events the camera produces are untouched: they are ordinary rows in the events
-- table, which is what the dashboard's driver rules and every report read.

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
  -- v20 put dms_live and dms_frame in here for the remote live camera. v22 retired
  -- both tables, and dropping a table takes it out of the publication by itself, so
  -- there is nothing to undo here and nothing to add.
end $$;

-- ------------------------------------------------------- v22 retire remote camera ----
-- The remote live view is deleted from the dashboard and the phone keeps the camera for
-- itself, so the two tables v20 built for that view are dropped. Nothing else in this
-- file creates them and neither app reads or writes them any more; a database that never
-- had v20 simply finds nothing to drop.
--
-- One drop per table is the whole of the clean-up: Postgres takes the table's policies,
-- indexes, grants and publication membership down with it, and dms_frame's rows belonged
-- to a session through "on delete cascade", so no pictures are left behind either. The
-- clip column v19 added to events and the driver rule rows are deliberately untouched.
drop table if exists dms_frame;
drop table if exists dms_live;

-- ------------------------------------------- v23 the parked vehicle, and the blackbox ----
-- A vehicle standing still does not need a position every minute. One point an hour
-- keeps Live Tracking and every report honest, and it is a twentieth of the rows the same
-- stop used to write. This is the stopped_interval the Configuration page already edits,
-- so nothing new has to be learned to change it - only its starting value moves, and only
-- in a database that is still sitting on the original 60. A fleet that typed its own
-- number keeps it, and any number it types after this run survives every future run,
-- because the marker below is written once and never taken back.
do $$ begin
  if not exists (select 1 from app_settings where key='parked_3600') then
    update app_settings set value='3600' where key='stopped_interval' and value='60';
    insert into app_settings (key, value) values ('parked_3600','1') on conflict (key) do nothing;
  end if;
end $$;

-- The blackbox took every fix the phone was handed, so a vehicle parked for a whole shift
-- stored some twenty-eight thousand readings of the same stationary position, filled its
-- cap of stored seconds and pushed the oldest driving seconds out with them. It now keeps
-- only the fixes at or above bb_min_kmh, which is what the handset's own speed reading of
-- a moving vehicle looks like; 0 in that box puts the old behaviour back for a fleet that
-- wants every second of a stop as well.
insert into app_settings (key, value) values ('bb_min_kmh','1')
  on conflict (key) do nothing;

-- ---------------------------------------------------- v24 the clip an alert leaves ----
-- The camera has written a few seconds of picture around every driver alert since v19 and
-- kept it on the handset, with the events row holding the path it would have pointed at.
-- What was missing was the file store, so the pictures never left the phone and the
-- administrator had the reading without the reason. This is that store: Supabase's own
-- Storage, the same project and the same subscription, so no new service is asked for a
-- key and nothing is paid for twice.
--
-- The rights below are the whole of its safety, and they are copied from the events table
-- rather than invented: a handset may write only into a folder named after its own
-- account, and a clip may be read back only by somebody who can already read the event it
-- belongs to - the vehicle's owner, its driver, or an administrator. A short clip at the
-- size the camera records is tens of kilobytes, so a whole fleet's alerts cost the project
-- nothing it was not already paying for positions and photos.
--
-- Buckets are rows in Supabase's own storage schema, so an idempotent insert builds one.
-- public = false means nothing on the internet can list it or reach a file in it; every
-- read is a short link the viewer's own rights have to first answer for.
insert into storage.buckets (id, name, public)
  values ('dms-clips', 'dms-clips', false)
  on conflict (id) do nothing;

-- The path a phone writes is <its own account id>/<event id>.mp4: one folder deep, and
-- the folder is the uploader, so no handset can put a picture of its driver into another
-- driver's file. Only the phone's own clips are ever this shape, and an mp4 is the only
-- thing accepted, so a bucket this fleet trusts cannot also hold a file somebody else
-- would be surprised to open.
drop policy if exists "dms clips write own folder" on storage.objects;
create policy "dms clips write own folder" on storage.objects for insert to authenticated
 with check (
   bucket_id = 'dms-clips'
   and name like '%.mp4'
   and array_length(storage.foldername(name), 1) = 1
   and (storage.foldername(name))[1] = auth.uid()::text
 );

-- Reading a clip is reading the event. The three tests are the ones every events policy in
-- this file has always used, but the join is deliberately narrower than "the row names
-- this path": a phone can write its own clip_path, so a row aimed at another driver's
-- folder would otherwise hand out that driver's picture. The file must also be the one
-- this event's own making - in its own driver's folder, under its own id - which is the
-- shape the handset writes and the only shape that can be read that way.
--
-- A driver may always read his own folder: it holds his own face, the listing is what his
-- phone uses to clear the days the Configuration page set, and Supabase needs SELECT for
-- the list and the download alike. Nobody but that driver ever reaches that folder.
drop policy if exists "dms clips read with the event" on storage.objects;
create policy "dms clips read with the event" on storage.objects for select to authenticated
 using (
   bucket_id = 'dms-clips'
   and ((storage.foldername(name))[1] = auth.uid()::text
        or exists (select 1 from events e
                    where e.clip_path = storage.objects.name
                      and storage.objects.name = e.user_id::text || '/' || e.cid::text || '.mp4'
                      and (is_admin() or owns_vehicle(e.vehicle_id) or owns_pair(e.vehicle_id, e.driver_id))))
 );

-- The retention days the Configuration page sets for the handset's own clips are the same
-- window on the server, and the phone is the thing that knows when a day has passed: it
-- clears its own folder on sign-in. An administrator can clear any folder, because a
-- departed driver's account may not come back to do it.
drop policy if exists "dms clips clear" on storage.objects;
create policy "dms clips clear" on storage.objects for delete to authenticated
 using (
   bucket_id = 'dms-clips'
   and (is_admin() or (storage.foldername(name))[1] = auth.uid()::text)
 );

-- A phone may put its own event's clip path on the row. Until v25 that was only ever
-- possible while ended_at was still null, which in practice meant never: a camera alert
-- closes itself a second or two after it opens, and its picture is not finished until
-- after that. v25 widens the update policy and pins what may move in guard_events(), so
-- the row is described below rather than here.

-- ------------------------------------------- v25 five behaviours, and the clip's turn ----
-- The camera already measured the mouth and named what the driver was holding; this is
-- the version that says what to do about it. Five rules join the four that existed:
--
--   dms_yawning    threshold = how wide this driver's own mouth has to open, as a
--                  percentage of its width. The phone will not call anything a yawn
--                  below 14 % however the row is set, because that is where talking
--                  stops and a yawn begins - so a threshold under 14 behaves like 14.
--                  hold_s = 0 is the honest setting: a yawn is a moment, and waiting
--                  for it to last makes the event disappear instead of the driver stop
--                  yawning.
--   dms_talking    threshold = how many times a minute the mouth may move. This is a
--                  rate, not a percentage, and the Configuration page prints "a minute"
--                  after the number instead of a per-cent sign. A single long open
--                  mouth never counts, because the phone only ever counts a movement
--                  that begins after the mouth has closed again.
--   dms_eating     threshold = the percentage of the last second the model saw food at
--                  this driver's mouth.
--   dms_drinking   threshold = the percentage of the last second it saw a cup or bottle
--                  there.
--   dms_smoking    threshold = the percentage of the last second a bare hand was at the
--                  mouth: lips the camera still reads as this driver's own skin, with
--                  the mouth's own dark line gone under them.
--
-- Two of these deserve to be said plainly, because an administrator who reads the row
-- afterwards deserves to know what the machine actually looked at:
--
-- The model the phone ships with is the one that comes out of the box with the face
-- toolkit. It knows food, a cup and a bottle, and it does not know a cigarette - there
-- is no cigarette class for it to answer with. So dms_smoking is not "a cigarette was
-- seen": it is "a hand was at the mouth, holding nothing the camera could name", which
-- is also what a driver scratching his nose or yawning with his hand over his face
-- looks like. It is seeded switched off for exactly that reason, and the Configuration
-- page says so on its row. Turn it on when you have seen what it does in your own cabs,
-- and judge the clip it leaves behind before you judge the driver.
--
-- The first build of that reading asked only the colour question - are the lips this
-- driver's own skin colour - and an ordinary closed mouth answers it in a sunlit cab, so
-- fleets that switched the rule on got a stream of events about drivers doing nothing.
-- v32 puts the second half of the sign in the phone's own code (the dark line of the
-- mouth has to have gone under something) and raises this row's two numbers, and both
-- halves are written below.
--
-- Eating and drinking depend on the same second model, and on some handsets that model
-- cannot report where it saw a thing - a limitation of the reader library, named on the
-- phone's own camera status rather than hidden. On those handsets the two rules answer
-- nothing at all and their rows stay empty. Fatigue, distraction, phone use and a
-- blocked camera never needed it and carry on working everywhere.
--
-- do nothing on conflict again: thresholds a fleet has tuned are never rolled back by
-- re-running this file.
insert into event_rules (type, label, enabled, threshold, alert, hold_s, speed_min) values
  ('dms_yawning', 'Driver yawning',     true,  20, true,  0, 10),
  ('dms_talking', 'Talking while driving', true, 40, true, 2, 10),
  ('dms_eating',  'Eating while driving',  true, 40, true, 1, 15),
  ('dms_drinking','Drinking while driving', true, 40, true, 1, 15),
  ('dms_smoking', 'Smoking',            false, 70, true,  3, 15)
  on conflict (type) do nothing;

-- A phone that is offline does not know which build of the rules its own settings copy
-- carries, so a rule the page has never sent stays off rather than firing on a guess.
-- That is the Config side of the same decision; nothing has to change here for it.

-- The clip could never arrive, and this is the statement that fixes it.
--
-- An alert closes itself as soon as the driver recovers - a yawn, a head turned away, a
-- hand at the mouth: a second or two - while its picture is still being assembled from
-- the second of camera that follows it. The v15 update policy let an account change its
-- own rows only while ended_at was still null, so by the time the file existed the row
-- had closed and the phone was no longer allowed to name it. Every camera event of that
-- shape lost its video, silently, which is the whole of why an Events table full of
-- driver alerts had no pictures beside any of them.
--
-- The widening is one column deep and the trigger is what holds the line: an account may
-- now update a row it has already closed, and on such a row nothing but clip_path may
-- move. An open row is still free to be filled in the way it always was, because that is
-- the phone closing its own breach.
drop policy if exists "events update own" on events;
create policy "events update own" on events for update to authenticated
  using (user_id = auth.uid())
  with check (user_id = auth.uid());

-- The identity columns were pinned from v15 and stay pinned exactly as they were; what
-- is new is the second half, which pins everything else on a row that has already
-- finished. A clip arriving late is the only thing this account may still say about an
-- event it has closed, and the file it points at is its own folder and its own id -
-- which the storage policy of v24 already insists on, from the other side.
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
  if old.ended_at is not null and (
       new.ended_at     is distinct from old.ended_at
    or new.value        is distinct from old.value
    or new.details      is distinct from old.details
    or new.lat          is distinct from old.lat
    or new.lng          is distinct from old.lng
    or new.speed        is distinct from old.speed
    or new.accuracy     is distinct from old.accuracy
    or new.altitude     is distinct from old.altitude
    or new.heading      is distinct from old.heading
    or new.accel        is distinct from old.accel
    or new.battery_pct  is distinct from old.battery_pct
    or new.charging     is distinct from old.charging
    or new.ignition     is distinct from old.ignition
    or new.road_limit   is distinct from old.road_limit
    or new.vehicle_name is distinct from old.vehicle_name
    or new.driver_name  is distinct from old.driver_name) then
    raise exception 'a closed event may only have its video attached';
  end if;
  return new;
end $$;

-- ------------------------------------------- v26 a recording per rule, chosen ----
-- Every camera event this fleet has ever raised came with a few seconds of picture,
-- because there was no way to say otherwise. A film of a driver's own face is the one
-- thing in this system that cannot be un-seen afterwards, and a fleet that watches the
-- cab on a motorway and not in a school run wants that decision made rule by rule and
-- vehicle by vehicle, not once for the whole project. So the nine camera rows of the
-- Configuration page each get a Recording switch, and this is the column it writes.
--
-- It changes what a phone keeps, nothing else. A rule with the switch off still warns
-- the driver, still writes its row with its reading and its duration, and still appears
-- in every report; only the file is not made, and the Events table then shows the row
-- with no clip icon beside it rather than a row that says "video missing".
--
-- Default true, and that is deliberate: it is what every one of these rules did before
-- the switch existed, so a fleet that runs this file notices no change until an
-- administrator asks for one. Vehicle rules (speed, braking, zones) have no use for the
-- column - the driver camera is the only thing on this system that has a lens - so for
-- them it simply sits at true and is never read.
alter table event_rules add column if not exists clip boolean not null default true;
comment on column event_rules.clip is
  'Driver-camera rules only: keep a few seconds of picture with the event. Vehicle rules ignore it.';

-- -------------------------------------------- v27 the smoking rule, by its name ----
-- The rule the camera raises when a bare hand sits at this driver's mouth is called
-- Smoking everywhere it is read - the Configuration row, the alert on the phone, the
-- Events table and the headline of the notice - so the row's own label goes with it.
--
-- This is an UPDATE and not just a seed edit, on purpose. The insert above says "do
-- nothing on conflict", which is what protects thresholds a fleet has tuned, and it is
-- also why a project that already has this row would keep answering "Hand at the mouth"
-- for ever: the Configuration page prints the label the database holds, and the phone
-- reads the same column. Only these two lines reach a live project, and they name the
-- old wording in the WHERE clause so a label an administrator has changed himself is
-- left alone.
--
-- What the rule measures has not moved: the shipped model has no cigarette class, so it
-- reports a hand at the face. The name says what a fleet acts on; the reading, which is
-- written into the event and burned onto its clip, still says what was seen.
update event_rules set label = 'Smoking'
  where type = 'dms_smoking' and label = 'Hand at the mouth';

-- ------------------------------------------- v28 one handset per account ----
-- A driver who signs in on a second phone while the first one is still working puts two
-- streams of positions on one vehicle at the same time, and the trip then reads as though
-- the cab was in two places. Supabase gives every device its own session, and the key a
-- handset holds cannot take a session belonging to another one away, so the rule lives
-- here instead: the account has one seat, a handset sits in it by naming itself, and the
-- seat only comes free when the phone in it stops answering.
--
-- did is the install's own identifier - Android's per-app, per-device number, which
-- survives an app update and changes only when the phone is wiped - and model is the name
-- of that handset as the two messages this table produces show it. since is when this
-- handset took the seat and renewed_at the last moment it proved it was still in it, which
-- is the only number the rule reads. tripping says the phone in the seat has a trip
-- running right now, and trip_since when that trip began.
--
-- The three tried_ columns hold the last refusal rather than a list: who asked, which
-- phone it was, and when. It is written so that the handset already sitting in the seat
-- can read it out loud on its next renewal - another phone tried to sign in as you - and
-- it stays on the row afterwards for the Users page to show an attempt the driver never
-- noticed. A second attempt replaces the first: the interesting question is whether the
-- account is being used by more than one handset, and one row answers that.
create table if not exists device_claim (
  user_id uuid primary key references profiles(id) on delete cascade,
  did text not null, model text,
  since timestamptz not null default now(),
  renewed_at timestamptz not null default now(),
  tripping boolean not null default false, trip_since timestamptz,
  tried_did text, tried_model text, tried_at timestamptz);

-- The seat is read by the account that sits in it and by an administrator. It is written
-- only by the two functions below, which is what stops the wait being talked around from
-- a handset: a phone cannot mark itself fresh, and the refused one cannot empty the row.
alter table device_claim enable row level security;
drop policy if exists "claim read own or admin" on device_claim;
create policy "claim read own or admin" on device_claim for select to authenticated
  using (user_id = auth.uid() or is_admin());
-- Releasing an account from the Users page is the one write an administrator makes here,
-- and it is for a phone that is lost, broken or left in a drawer: his delete on this row
-- is what frees the seat before the wait is up.
drop policy if exists "claim admin release" on device_claim;
create policy "claim admin release" on device_claim for delete to authenticated
  using (is_admin());

-- One handset asking for the seat, and every half minute afterwards telling the server it
-- is still sitting in it. The seat is granted when it is empty, when it already belongs to
-- this install, or when the phone holding it has not renewed for two minutes - silence of
-- that length is a dead battery or a swipe-away, not a second phone in a pocket, and the
-- driver who has to move to a spare handset should not have to hunt down an administrator
-- first. While a trip is running the wait is five minutes instead, because a handset whose
-- page Android has put to sleep mid-shift must not hand the same vehicle to a second phone
-- and record the road twice: two minutes of sleep is ordinary, five minutes of it means
-- the phone is really gone.
--
-- A refusal changes nothing about the seat - that is the whole point of it - and instead
-- stamps the attempt for the phone in the seat to read out, and answers with the name of
-- the handset holding the seat, the minutes left on its wait, and whether it is on a trip,
-- which is what lets the sign-in screen tell the driver whose phone to go and close.
drop function if exists device_take(text,text,boolean);
create or replace function device_take(p_did text, p_model text, p_trip boolean default false)
returns table(ok boolean, model text, since timestamptz, renewed timestamptz,
              wait_s int, tripping boolean, trip_since timestamptz,
              tried_model text, tried_at timestamptz)
language plpgsql security definer set search_path = public as $$
declare c device_claim%rowtype; win int;
        m text := left(nullif(trim(coalesce(p_model,'')), ''), 60);
        d text := left(trim(coalesce(p_did,'')), 60);
begin
  -- nobody signed in: there is no account to hold a seat, and nothing to say
  if auth.uid() is null or d = '' then
    return query select false, null::text, null::timestamptz, null::timestamptz,
      0, false, null::timestamptz, null::text, null::timestamptz;
    return;
  end if;
  -- An account that has never sat in the seat gets its row here, because the lock below
  -- needs a row to lock. Two handsets arriving for the first time at the very same second
  -- cannot both win: the second one's insert is thrown away by the primary key, so it
  -- waits like any other second phone.
  insert into device_claim (user_id, did, model, since, renewed_at)
    values (auth.uid(), d, m, now(), now()) on conflict (user_id) do nothing;
  select * into c from device_claim where user_id = auth.uid() for update;
  win := case when c.tripping then 300 else 120 end;
  if c.did = d or c.renewed_at is null or c.renewed_at < now() - make_interval(secs => win) then
    update device_claim set did = d, model = m, renewed_at = now(),
      tripping = coalesce(p_trip, false),
      -- the handset already in the seat keeps the clock on its own trip; one taking the
      -- seat over starts a fresh clock, and both keep the trip's own start where it was
      trip_since = case when c.did = d then (case when p_trip then coalesce(trip_since, now()) end)
                        when p_trip then now() end,
      since = case when c.did = d then since else now() end
     where user_id = auth.uid() returning * into c;
    return query select true, c.model, c.since, c.renewed_at, 0, c.tripping, c.trip_since,
      c.tried_model, c.tried_at;
  end if;
  update device_claim set tried_did = d, tried_model = m, tried_at = now()
   where user_id = auth.uid() returning * into c;
  return query select false, c.model, c.since, c.renewed_at,
    greatest(1, ceil(extract(epoch from (c.renewed_at + make_interval(secs => win)))
                   - extract(epoch from now()))::int),
    c.tripping, c.trip_since, c.tried_model, c.tried_at;
end $$;

-- Signing out stands up from the seat at once, so the next handset - the driver's own the
-- following morning, or the spare he picks up when this one dies - never has to wait out
-- the silence. Only the install in the seat can empty it: the phone that was refused has
-- no way to push the driver sitting in it out of the way, which is exactly the difference
-- between this and a fight over the account.
drop function if exists device_give(text);
create or replace function device_give(p_did text) returns void
language sql security definer set search_path = public as $$
  delete from device_claim
   where user_id = auth.uid() and did = left(trim(coalesce(p_did,'')), 60)
$$;

-- Both questions are about the account that asks them, so a handset that is not signed in
-- has nothing to ask; and since the seat cannot be written any other way, the row is
-- readable and nothing more.
-- The revoke has to name anon as well as public. Supabase hands EXECUTE on a new function
-- to anon, authenticated and service_role as separate grants, and pulling the pseudo-role
-- public leaves the anon one standing - which was proved on the live project, where a bare
-- POST to rpc/device_give with nothing but the anon key in the headers returned 204 and
-- rpc/device_take returned 200. No seat moved, because both functions ask auth.uid() and
-- an anonymous caller has none, so nothing here is a hole a driver can be pushed out of:
-- it is a door left open on a room that turns out to be locked from the inside. The anon
-- key ships inside every handset, and a future version of either function that stops
-- asking who is calling would then be reachable by anyone who has the app, so the grant
-- goes. The dashboard's Release button does not use these two - it deletes the row under
-- "claim admin release" - so signing anon out of them costs nothing.
revoke all on function device_take(text,text,boolean), device_give(text) from public, anon;
grant execute on function device_take(text,text,boolean) to authenticated;
grant execute on function device_give(text) to authenticated;

-- --------------------------- v29 the trips the phone makes when nobody presses ----
-- A handset in a cab knows when the cab is moving, so a trip does not have to wait for a
-- driver to remember the button: five seconds above 5 km/h opens one, and ten minutes
-- below it closes one. That is right for a fleet whose records are otherwise missing the
-- first hour of every shift, and wrong for one that may only write down the hours a man
-- said he drove. These five keys are that choice, and they carry the numbers with it,
-- because a taxi crawling through a city and a lorry idling at a depot cannot both be
-- measured against the same 5 km/h.
--
-- Both switches seed ON, which is exactly what every handset does today, so this block
-- changes nothing on its own. The new "Trips the phone starts and ends by itself" card on
-- the Configuration page is where a fleet turns either half off, and the phone reads all
-- five keys within five minutes or at the next trip start.
--
-- A database that has not run this still auto-starts and auto-ends a trip, at five
-- seconds, ten minutes and 5 km/h: an absent key is the same answer as these defaults, so
-- an older dashboard build cannot switch the behaviour off by not knowing about it.
insert into app_settings (key, value) values
   ('auto_start_on','1'), ('auto_start_s','5')
  ,('auto_end_on','1'),   ('auto_end_min','10')
  ,('auto_kmh','5')
  on conflict (key) do nothing;

-- ------------------ v30 the driver's own camera stop, written down by his handset ----
-- Since this build the driver's video button puts down the trip's judging as well as his
-- own picture, and it stays down until he presses again. A trip that is silent because a man
-- switched his camera off and a trip that is silent because he did nothing wrong look the same
-- on the map unless the phone is allowed to say so, so it writes one row of its own for the
-- stretch: opened by his press, closed when the lens comes back or the trip ends.
--
-- Nothing else changes. The row is written by the page rather than the camera service, so no
-- Java, no clip and no rule is involved: there is no threshold to configure and nothing to
-- warn about, which is why it is absent from event_rules and from the Configuration page.
--
-- The policy below is the whole migration, and it has to be run before the phone build that
-- writes this row: a type outside this list is refused by the database, the handset drops the
-- record out loud and the office simply never learns the camera was stopped.
drop policy if exists "events insert own" on events;
create policy "events insert own" on events for insert with check (
  user_id = auth.uid() and owns_vehicle(vehicle_id) and owns_pair(vehicle_id, driver_id)
  and type in ('harsh_braking','harsh_acceleration','overspeed',
               'zone_enter','zone_exit','zone_overspeed',
               'dms_fatigue','dms_distraction','dms_phone_use','dms_cam_blocked',
               'dms_yawning','dms_talking','dms_eating','dms_drinking','dms_smoking',
               'cam_off'));

-- ------------------ v31 the handset says out loud when it stops seeing satellites ----
-- A vehicle can be driving perfectly and reporting nothing at all: the fix never locks, so no
-- position, no speed and no place for any other event to stand on. Until now the only sign of
-- that was a gap in the route, which an office reads as a parked vehicle. These two rows turn
-- the silence into something the driver hears and the fleet can count.
--
-- Both are written by his handset, from the same watcher that delivers every fix: gps_lost
-- opens once the fleet's own wait has passed with no locked position, gps_recovered closes it
-- with ended_at when a fix arrives again. The wait is the Trigger limit of the gps_lost rule,
-- in seconds, so an office that wants a tighter leash sets 60 and one that drives through long
-- tunnels sets 300.
insert into event_rules (type, label, enabled, threshold, alert, hold_s) values
  ('gps_lost',      'GPS lost',      true, 120, true, 0),  -- seconds with no locked position
  ('gps_recovered', 'GPS recovered', true, null, true, 0)   -- a moment: no limit to measure
  on conflict (type) do nothing;

-- Eighteen types instead of sixteen: the two above. A phone that raises one against a database
-- that has not run this block has its record refused, says so on the driver's screen and drops
-- it - which is why the wait is only honoured while the rule row itself exists.
drop policy if exists "events insert own" on events;
create policy "events insert own" on events for insert with check (
  user_id = auth.uid() and owns_vehicle(vehicle_id) and owns_pair(vehicle_id, driver_id)
  and type in ('harsh_braking','harsh_acceleration','overspeed',
               'zone_enter','zone_exit','zone_overspeed',
               'dms_fatigue','dms_distraction','dms_phone_use','dms_cam_blocked',
               'dms_yawning','dms_talking','dms_eating','dms_drinking','dms_smoking',
               'cam_off','gps_lost','gps_recovered'));

-- ------------------ v32 the hand at the mouth has to leave a mark -------------
-- The other half of this fix is in the phone's own code: the smoking reading now asks that the
-- mouth's own dark line has gone as well as that its colour has turned to this driver's skin,
-- because a plain closed mouth in a sunlit cab answers the colour question and used to raise an
-- event. This block is the database half of the same complaint, and it is the half that works on
-- the handsets that are already out there.
--
-- Both lines only raise a number that is still at or under the old shipped default, so an
-- administrator who had already set either one the stricter way keeps what he set: 70 is four
-- of the five pictures a second rather than three of them, and three seconds is a hand
-- that stayed at the mouth, not a scratch on the way past.
update event_rules set threshold = 70 where type = 'dms_smoking' and threshold < 70;
update event_rules set hold_s = 3 where type = 'dms_smoking' and hold_s < 3;

-- ------------------ v33 the doors an anonymous caller could still push ----------
-- Nothing here changes what either app does on a normal day. Each line closes one way to
-- ask the database for something the page that asks is not entitled to ask for, and each
-- was checked against what both pages actually call, so no working screen loses a door.

-- (1) The wrong-password count is put down only by a sign-in that already worked, and a
--     sign-in that worked holds a token. Until now a caller with nothing but the anon key
--     that ships inside every handset could POST rpc/login_ok with any address and zero
--     that account's count, so five guessed passwords could be followed by five more with
--     the lockout wiped between the rounds, and the Users page would read "no attempts"
--     the whole time. v28 wrote this lesson down for the device seat and it is the same
--     here: revoking from the pseudo-role `public` leaves the separate `anon` grant
--     standing. login_gate and login_fail keep their anon grant because the sign-in box
--     genuinely calls them before it has a session; login_ok has no such excuse.
revoke execute on function login_ok(text) from anon;
grant execute on function login_ok(text) to authenticated;

-- (2) Two functions neither page asks while it is still anonymous. Both are SECURITY
--     DEFINER and login_gate reaches them from inside itself, so closing them off from
--     outside costs a driver nothing at his sign-in box. Left open, an unauthenticated
--     stranger could read the fleet's own lockout windows and run the sweep on demand,
--     ageing a lock out a minute early.
revoke all on function login_cfg(), login_sweep() from public, anon;
grant execute on function login_sweep() to authenticated;

-- (3) A blackbox pull is the office's request and the handset's answer, and the update
--     policy lets a phone answer a pull for its own vehicle - correctly. What it never
--     said was which columns the answer may touch, so a handset could mark a pull 'sent'
--     with n_rows 99999 without uploading one byte, and could rewrite the window, the
--     asker and the request time on the administrator's own record. This is guard_events()
--     from v15 applied to the other two-party row in the file: everything that made the
--     request is pinned, everything that answers it stays free, and an administrator's own
--     edit passes straight through.
create or replace function guard_bb() returns trigger language plpgsql as $$
begin
  if is_admin() then return new; end if;
  if new.id           is distinct from old.id
  or new.vehicle_id   is distinct from old.vehicle_id
  or new.vehicle_name is distinct from old.vehicle_name
  or new.from_ts      is distinct from old.from_ts
  or new.to_ts        is distinct from old.to_ts
  or new.requested_by is distinct from old.requested_by
  or new.requested_at is distinct from old.requested_at
  then raise exception 'a handset may answer a blackbox request, not rewrite it';
  end if;
  return new;
end $$;

drop trigger if exists bb_guard on bb_requests;
create trigger bb_guard before update on bb_requests for each row execute function guard_bb();

-- (4) user_activity is the log an administrator reads to see who did what. The insert
--     policy already insists that an account may only write about itself, but the NAME
--     printed on the row was whatever the caller typed, so a handset could file an entry
--     that read as another person's and the Activity Log would print it. Same rule as
--     stamp_names() in v15: do not argue with the label, rewrite it from the account the
--     row is really about. An address with no profile row keeps what it sent, because a
--     missing profile is a schema that has not caught up and is not a reason to lose an
--     entry.
create or replace function stamp_activity() returns trigger language plpgsql as $$
declare n text;
begin
  select case when p.email like '%@fleet.local' then split_part(p.email, '@', 1) else p.email end
    into n from profiles p where p.id = new.user_id;
  if n is not null then new.user_name := n; end if;
  return new;
end $$;

drop trigger if exists activity_stamp on user_activity;
create trigger activity_stamp before insert on user_activity for each row execute function stamp_activity();

-- (5) recorded_at is written by the handset, so until now it was the handset's to choose.
--     A row filed with last March's stamp, or next week's, lands inside the trip
--     splitter's gap window and is read as a vehicle that drove through a silence it never
--     crossed - which is exactly the shape an investigation is trying to see. A trigger
--     rather than another copy of the insert policies, because those have been rewritten
--     five times over and this file's last word has to stay the type list, not this.
--     Neither app is affected: the blackbox holds fourteen days of fixes, the offline
--     outbox is flushed within hours of a signal returning, and both sit inside this
--     window with room to spare. The two hours ahead is clock skew and a phone that woke
--     up in a different timezone, not a licence to file a trip in the future.
create or replace function guard_time() returns trigger language plpgsql as $$
begin
  if new.recorded_at is null then new.recorded_at := now(); return new; end if;
  if new.recorded_at < now() - interval '45 days' or new.recorded_at > now() + interval '2 hours'
  then raise exception 'a record dated outside the last 45 days or more than two hours ahead is refused';
  end if;
  return new;
end $$;

drop trigger if exists time_guard on positions;
create trigger time_guard before insert on positions for each row execute function guard_time();
drop trigger if exists time_guard on events;
create trigger time_guard before insert on events for each row execute function guard_time();

-- (6) clear_pw_flag() is the one write an ordinary account is allowed to make about
--     itself, and it is the only one it can make without a screen in the way: the
--     "this password was set for you" flag is a promise to the office rather than a
--     wall, and a caller who knows the address can put it down with curl. Two things
--     follow, neither of which touches a working screen. It is no longer answerable by a
--     caller that holds nothing but the anon key shipped inside every handset - the flag
--     is put down after a password has actually been changed, and that needs a session.
--     And when it does come down, the database writes its own line in the Activity Log,
--     which is the report the administrator reads: a flag that went down with no
--     'Changed own password' entry beside it is now visible as exactly that. The line is
--     written only for an account that really was carrying the flag, so a driver who
--     changes his own password for no reason leaves no second entry behind.
revoke execute on function clear_pw_flag() from anon;
grant execute on function clear_pw_flag() to authenticated;

create or replace function clear_pw_flag() returns void
language plpgsql security definer set search_path = public as $$
declare n int;
begin
  update profiles set must_change = false, fails = 0, fails_at = null
   where id = auth.uid() and must_change
    returning 1 into n;
  if n is not null then
    insert into user_activity (user_id, action, details)
    values (auth.uid(), 'Temporary password put down',
      'the flag an administrator raised was cleared for this account');
  end if;
end $$;

-- ------------------ v34 the two reads that got slower every week --------------
-- Live Tracking opens with one question: where is each phone right now. The view that
-- answered it walked the whole positions index and kept the newest row of every group,
-- which is nothing at all on a project's first week and a table scan by its first year -
-- so the busiest page in the fleet used to get heavier every single day without one byte
-- of the settings changing. The answer is a pointer the database keeps for itself: one
-- row per phone, always the newest of its positions, so the read costs as much as the
-- fleet has vehicles rather than as much as it has history. The page does not change:
-- latest_positions keeps its name, its columns and its row-for-row rights.
create table if not exists latest_pos (
  user_id uuid primary key,
  pos_id  bigint not null,
  latest_at timestamptz not null);
alter table latest_pos enable row level security;
-- The pointer says only which row is somebody's newest; the position itself is still read
-- through positions' own policies, which is what keeps a driver's Live Tracking to his own
-- vehicles exactly as the old view did.
drop policy if exists "latest read" on latest_pos;
create policy "latest read" on latest_pos for select to authenticated using (true);

-- The writer is a trigger, not another copy of the insert path, because three different
-- pieces of software write positions - the page, the handset's own runner and the
-- blackbox push - and all three must land the pointer. A newer recorded_at always wins,
-- which matters for the phone that drove a dead spot for an hour and then flushed its
-- queue oldest-first: the burst ends on the newest fix, like every other writer.
--
-- SECURITY DEFINER, and that is not decoration: this writes a second table on behalf of
-- whoever inserted the position, so it runs as the function's owner rather than as the
-- caller. RLS is enabled on latest_pos with one SELECT policy and no INSERT or UPDATE one,
-- which is exactly what is wanted for a pointer nobody outside the database may move - but
-- as an ordinary trigger function it ran as the driver, and the driver is not allowed to
-- write that table. Every position insert then failed inside this trigger with
-- "new row violates row-level security policy for table latest_pos", the handset dropped
-- the record, and with it went every trip and every tacho second, because both read the
-- positions rows this refused to accept. Reads stay as they were: the view is security
-- invoker, so each position is still gated by positions' own policies.
create or replace function stamp_latest() returns trigger language plpgsql
  security definer set search_path = public as $$
begin
  if new.user_id is not null and new.id is not null then
    insert into latest_pos (user_id, pos_id, latest_at)
         values (new.user_id, new.id, new.recorded_at)
      on conflict (user_id) do update
        set pos_id = excluded.pos_id, latest_at = excluded.latest_at
      where excluded.latest_at > latest_pos.latest_at;
  end if;
  return new;
end $$;

drop trigger if exists latest_stamp on positions;
create trigger latest_stamp after insert on positions for each row execute function stamp_latest();

-- The pointers a project already owes are filled in once, here, by the one expensive
-- read this version exists to stop doing on every page open. Re-running this file on a
-- project that has already done it costs nothing, and a project that never had a
-- position written still gets a table of its own rather than an error.
do $$ begin
  if not exists (select 1 from latest_pos) then
    insert into latest_pos (user_id, pos_id, latest_at)
    select distinct on (user_id) user_id, id, recorded_at from positions
      where user_id is not null
      order by user_id, recorded_at desc
    on conflict (user_id) do update set pos_id = excluded.pos_id, latest_at = excluded.latest_at
      where excluded.latest_at > latest_pos.latest_at;
  end if;
end $$;

drop view if exists latest_positions;
create or replace view latest_positions with (security_invoker = true) as
  select p.* from positions p join latest_pos l on l.pos_id = p.id;

-- (2) Two reports read a period and nothing else - the Events page before a vehicle is
--     picked, and the fleet-wide daily charts - and both used to plan a full scan because
--     every index on these two tables leads with a vehicle, a user or a type. A plain
--     time index is what a date range wants.
do $$ begin
  if not exists (select 1 from pg_indexes where schemaname='public' and tablename='positions' and indexdef ilike '%(recorded_at%desc%') then
    create index positions_time on positions (recorded_at desc);
  end if;
  if not exists (select 1 from pg_indexes where schemaname='public' and tablename='events' and indexdef ilike '%(recorded_at%desc%') then
    create index events_time on events (recorded_at desc);
  end if;
end $$;

-- ------------------ v36 every position this file's own trigger refused -------
-- The fix above is the whole of it, restated here instead of only in the v34 block so
-- that a project which already ran v34 repairs itself with one more paste - and every
-- project has, because the one that reported it had run it. A handset that hits this says
-- so on the driver's screen: "A position was refused by the server and dropped: new row
-- violates row-level security policy for table "latest_pos"", and every trip, tacho
-- second and distance figure the office reads went with it, since all three are counted
-- from the positions rows this refused to accept.
--
-- It is restated with the same signature and the same body, so the trigger that already
-- points at it keeps pointing: no table, column, policy or view changes in this version,
-- and nothing in either app has to be rebuilt to go with it.
--
-- The pointers themselves need no repair. While the trigger was failing no position was
-- accepted at all, so nothing landed that the pointer missed; each account's row still
-- names the newest position it genuinely has, and every handset fills it in again on the
-- first fix it sends after this file is run.
create or replace function stamp_latest() returns trigger language plpgsql
  security definer set search_path = public as $$
begin
  if new.user_id is not null and new.id is not null then
    insert into latest_pos (user_id, pos_id, latest_at)
         values (new.user_id, new.id, new.recorded_at)
      on conflict (user_id) do update
        set pos_id = excluded.pos_id, latest_at = excluded.latest_at
      where excluded.latest_at > latest_pos.latest_at;
  end if;
  return new;
end $$;

drop trigger if exists latest_stamp on positions;
create trigger latest_stamp after insert on positions for each row execute function stamp_latest();

-- ------------------ v37 the one journey the office counted eight times --------
-- auto_end_min says how long standing makes a trip finish. It was the only wait there was,
-- and a handset obeyed it the second it expired: the driver's End-trip record closed, the
-- watcher that waits for the next start re-armed in the same breath, and five seconds of
-- movement opened another trip. A delivery point, a queue at a loading bay, or a long ramp
-- therefore read as several journeys - each with its own start, its own distance and its own
-- events - and one shift with eight stops came home as eight trips.
--
-- This key is the tolerance between "the vehicle has stood long enough" and "the trip
-- really ended": the phone holds the record open this many further minutes below the moving
-- speed, and a single moving fix cancels the pending end outright. 0 asks for the behaviour
-- above, unchanged.
--
-- Nothing to repair here, and no paste is owed: a database that has not run this has no such
-- key, and a handset reads an absent key as 3 - the same number seeded below - so the fleet
-- gets the new behaviour from the build alone, and this row only makes it visible and
-- editable on the Configuration page.
insert into app_settings (key, value) values ('auto_end_grace_min','3')
  on conflict (key) do nothing;

-- ------------------------------------------- v38: which app was in the hand ----
-- Two handset apps write to this one database: the full Fleet Tracker and the
-- tracking-only Lite. Nothing on a position or an event says which of them sent it,
-- so the office could not tell a vehicle running the camera app from one running the
-- light one. Every audit entry now names the app that wrote it, and the Vehicles page
-- reads the newest sign-in beside each vehicle.
--
-- The column is nullable and nothing defaults it: entries written by an older build
-- keep a blank app, which is the honest answer for a row that never carried one.
alter table user_activity add column if not exists app text;

-- One row per vehicle: the app its account last signed in or opened with. A vehicle
-- given to several accounts is answered by whichever of them used the app most
-- recently, which is the handset actually in the cab. 'Opened app' counts alongside
-- 'Signed in' because a remembered session is still the driver using that app.
--
-- Not security definer: it reads user_activity, which only an admin may read, so it
-- runs as the caller and a normal user gets nothing back. The existing
-- user_activity_user index (user_id, recorded_at desc) is what this walks.
create or replace function vehicle_apps()
  returns table(vehicle_id uuid, app text, seen_at timestamptz)
  language sql stable as $$
  select distinct on (a.vehicle_id) a.vehicle_id, u.app, u.recorded_at
  from assignments a
  join user_activity u on u.user_id = a.user_id
  where a.vehicle_id is not null
    and u.app in ('main','lite')
    and u.action in ('Signed in','Opened app')
  order by a.vehicle_id, u.recorded_at desc
$$;
revoke execute on function vehicle_apps() from anon;

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
--        Arabic, Hindi, Urdu, and since v28 Kurdish in both scripts: ku for Kurmanji, ckb for Sorani) chosen on
--        the Configuration page; the two app_settings keys above become the
--        fallback only. The column is free text on purpose: the words live in each app's own VMSG table, so a new
--        language is a release of the apps, never a migration.
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
--
-- v19, on top of the above (driver monitoring by the phone's forward camera):
--   event_rules.speed_min (0)                                             -> the km/h above which a rule is armed.
--        Only the four camera rules read it: a drowsy or covered-face alert is noise when the vehicle is parked,
--        and a phone left on a seat with nobody in it would otherwise raise Camera blocked all day in a depot.
--   four new event_rules rows: dms_fatigue, dms_distraction, dms_phone_use, dms_cam_blocked
--        -> ordinary rules, so they inherit the Configuration page's tolerance, Recorded, Visual alert, Buzzer
--        alert and Voice alert columns unchanged. Seeded with do nothing, so your tuning survives a re-run.
--   events insert policy                                                  -> ten accepted types now, not six.
--   events.clip_path (null)                                               -> where the short clip an event leaves
--        behind is stored. Nothing writes it until the monitoring build reaches the phones, and a row without a
--        clip reads and exports exactly as every older row does.
--   app_settings dms_on (0), dms_pre_s (3), dms_post_s (1), dms_fps (5), dms_keep_d (7),
--        dms_preview (1)                                                  -> camera-wide switches: the master
--        arm, how many seconds of the rolling buffer an event keeps either side of
--        itself, how often a frame is looked at, how long clips survive, and whether
--        the driver sees the small picture on his screen.
--   Run this file BEFORE installing a build that monitors the driver: the new event types are refused by a
--   database that has not been told about them, and the Configuration page warns you when speed_min is missing.
--   The clip bucket itself is created in the next phase, together with the rights that let a handset write into
--   it and an administrator read it back.
--
-- v20, on top of the above (a supervisor opens the driver's camera on demand, and the
-- driver can open his own and invite the office to look):
--   dms_live table + 4 policies + publication entry                       -> one row per "show me the driver"
--        press: the vehicle, who asked and by what name, pending until the handset answers, and stop_at — the
--        moment the watch ends by itself. An administrator opens, reads and closes any session; a driver only sees
--        and answers requests about a vehicle assigned to him, the same shape as the blackbox pull rules, plus the
--        right to insert a session of his own so he can start the view from his phone. Every driver-side rule also
--        accepts is_admin(), because the handset used for testing is usually signed in with a supervisor login that
--        holds no vehicle - without that the phone could neither read the request nor push its pictures and would
--        fail without a word on either screen.
--   dms_frame table + 3 policies + publication entry                      -> the short-lived base64 pictures the
--        phone pushes about once a second while a session is live. Only that vehicle's handset may write them, only
--        an administrator reads them, and the handset may delete its own, so an abandoned viewer cannot leave a
--        pile of images behind. Rows are removed when the session ends.
--   Nothing else changes. There is no new setting and no new column on an existing table, and the phone still
--   streams only while its app is on screen — the camera lives in the page, not yet in the background service.
--        Run this file BEFORE publishing the dashboard build with the video button: an insert into a table the
--   database has not been told about is refused, and the panel would report a missing service instead of a picture.
--   RETIRED IN v22: neither table is created by the statements above any more, and the two drops at the end of this
--   file take them out of a database that already has them. See the v22 entry below.
--
-- v21, on top of the above (a lockout that expires instead of waiting for a person):
--   profiles.fails_at                                                  -> the moment the wrong-password count last
--        moved. login_window_m quiet minutes after it, the count is back to nothing, so five forgetful mornings in
--        one week no longer add up to a lockout the way five in a row do.
--   profiles.locked_until                                              -> how much of a lock is left. Set only by a
--        five-strike lockout; NULL means an administrator pressed Lock on the Users page and only his Unlock ends
--        it. login_lock_m says how long a lockout lasts, and login_lock_m 0 restores the v18 behaviour of a lock
--        that waits forever.
--   app_settings keys 'login_window_m' (15) and 'login_lock_m' (15)      -> those two waits, edited on the
--        Configuration page. They are read by login_cfg(), which stands its own default behind an empty or mistyped
--        box because the screen that calls it is the sign-in screen.
--   login_sweep()                                                      -> ends expired locks and aged counts. Every
--        sign-in call runs it first, so nobody needs a scheduled job: a lock that ran out is simply gone by the next
--        attempt, and the person who waited it out signs in on the attempt that asks. It is granted to a signed-in
--        account so the Users page can show the truth rather than a stale warning.
--   login_gate / login_fail                                            -> re-created with two more answers:
--        wait_min, the minutes a lock has left, and reset_min, the minutes of quiet after which the count starts
--        again from nothing. wait_min is NULL when there is no lock or only an administrator can end it. Both are
--        what lets the two sign-in screens tell a driver how long to wait instead of only sending him to the
--        office. The v18 two-column versions are dropped before these are created: Postgres
--        cannot change a function's return type in place, and a file that is run twice has to
--        find the same signature both times.
--   login_ok / clear_pw_flag                                           -> also put down fails_at, so a good sign-in
--        and a new password both start the clock from nothing. Neither clears a lock: a lockout is ended by its own
--        clock or by Unlock, never by typing.
--   one-off backfill                                                    -> accounts sitting locked when this version
--        arrives get the expiry they would have had, so nobody has to be unlocked by hand just because the schema
--        caught up late. A lock with fewer than five tries behind it is one an administrator pressed, and it is left
--        alone.
--   Run this file before publishing the build that shows the two waits on the Configuration page; until then both
--   apps keep working with the v18 answers and simply never mention an expiry.
--
-- v22, on top of the above (the camera stops being somebody else's remote control):
--   drop dms_frame, then drop dms_live                                    -> v20's remote live view is deleted.
--        Pushing a handset's pictures into a browser only ever answered while the driver's app was the screen in
--        front of him and the signal was good enough to carry a frame a second; on the roads this fleet runs that
--        never held, and a button that promises a picture and shows a black square is worse than no button. The
--        video button is gone from Live Tracking, and the driver opens the same forward camera from his own map:
--        the phone's DMS service, the four driver rules, the buzzer, the voice and the rows in the events table
--        are exactly the ones the fleet's own "Watch the driver" switch produces, because the driver's button
--        hands the camera the same setup a trip hands it.
--   events, event_rules, app_settings dms_* , events.clip_path            -> untouched. Every record the camera
--        makes still arrives in the events table and still shows in Live Tracking, Events, the reports and the
--        Configuration page. Only the two tables that carried pictures to a browser retire.
--   Nothing to run in a particular order: the two drops are if-exists, so a fresh project and a v20 project both
--   end in the same state. Publish the dashboard and the phone builds that stop using the tables first if you
--   want the button gone before the data does - the reverse order only means a press that finds no table, which
--   the panel already reports as a missing service.
--
-- v23, on top of the above (a parked vehicle speaks once an hour, and the blackbox keeps only the road):
--   app_settings 'stopped_interval' 60 -> 3600                             -> one position an hour while the
--        vehicle stands still, instead of one a minute. It is the same box on the Configuration page that has
--        always held it, and the same per-vehicle column that has always overridden it, so a fleet that wants a
--        different wait - 300 for a taxi, 3600 for a lorry at a depot - changes one number and nothing else.
--        The update runs only where the value is still the original 60, and 'parked_3600' marks that it ran: a
--        number you set yourself, before or after, is never moved again.
--      Live Tracking                                                        -> a stopped vehicle is a point up to an
--        hour old, so between two heartbeats it reads Parking rather than Idling. Nothing else about it changes:
--        it is not Offline, because that waits for offline_after_h, and it starts reporting every moving interval
--        again the moment it rolls.
--      Historical Tracking, the Tacho report and every distance figure      -> the trip splitter and the idling
--        maths used to treat a silence of ten minutes and a silence of three minutes as breaks in the drive. Both
--        now read the recording intervals you configured, so an hour parked between two heartbeats stays one trip
--        and counts as idling rather than as missing data. A phone that was switched off still shows a gap.
--   vehicle_report()                                                     -> its stop_min term takes the same
--        parked-interval bound, so the Fleet Summary keeps reporting a parked shift as standing time when the phone
--        says nothing about it for an hour. km, drive_min and over_s keep the three-minute bound on purpose: a
--        silence between two moving samples is a phone that said nothing, and bridging it would draw a straight
--        line over roads no vehicle took straight.
--   app_settings 'bb_min_kmh' (1)                                        -> the speed, in km/h, at which the
--        handset begins storing a second in its blackbox. Below it a fix is dropped, so a shift spent parked costs
--        the phone nothing and cannot push the driving seconds out of its cap. 0 means every fix again. This
--        changes only what is kept on the handset: the download the Vehicles page asks for, its windows, its
--        de-duplication and the Blackbox rows in the tacho report are all exactly as before.
--   Publish the dashboard and the phone that read these two before running this file. The order hardly matters -
--   an older app simply never sees the new keys and keeps its own built-in defaults (60 s stopped, every fix
--   stored) - but a newer phone holding a stopped_interval of 3600 while the dashboard still offers a 60 s box is
--   a page that lies about what the fleet does.
--
-- v24, on top of the above (the short film an alert leaves behind):
--   storage bucket 'dms-clips' (private)                                  -> the few seconds of forward camera the
--        phone already records around every driver alert now have somewhere to go. It is Storage inside this same
--        project, so no new service asks for a key and nothing is paid for twice.
--   3 policies on storage.objects                                         -> a handset writes only
--        <its own account id>/<event id>.mp4, and a clip opens for the same three people who can already read the
--        event it belongs to: the vehicle's owner, its driver, an administrator. A driver can always list his own
--        folder, which is what his phone uses to clear the days the Configuration page set. Nothing else in the
--        project can reach a file in here, and the bucket is not public.
--   events.clip_path                                                      -> the column v19 added is used at last.
--        It is written while the row is still open - on the insert that opens it, or the update that closes it -
--        because the phone's own policy on events ends the moment a row is closed. No new column and no new table:
--        this version only gives the path a place to live.
--   Run this file before publishing the phone build that uploads, and the dashboard build that shows the camera
--   icon. An older dashboard simply never draws the icon; an older phone leaves the alerts on the handset, which is
--   exactly the behaviour v23 shipped with. Nothing is lost by running it early, and nothing is gained by running
--   it twice: the bucket insert is do-nothing and every policy is dropped and rebuilt.
--
-- v25, on top of the above (five more things the camera can see, and the clip that never arrived):
--   five event_rules rows                                             -> dms_yawning, dms_talking, dms_eating,
--        dms_drinking, dms_smoking. Seeded do-nothing, so thresholds a fleet tuned are never rolled back.
--        dms_smoking is seeded OFF: the shipped model sees a bare hand at the mouth, not a cigarette.
--   events insert policy                                              -> fifteen types instead of ten. Without this
--        the five new alerts were refused by the database and the phone threw the refusal away, which is what made a
--        vehicle full of warnings show two event types in the dashboard.
--   "events update own" policy + guard_events()                       -> a phone may name the video of an event it
--        has already closed, and on a closed row nothing but clip_path may move. This is the fix for the clip: a
--        camera breach lasts a second or two and its picture is not finished until after that, so the old policy
--        refused the write for every alert of that shape.
--   Publish the phone that sends the five types before running this file, or after - both orders work, and the
--   after-order is simply the situation this version exists to end.
--
-- v26, on top of the above (a recording per rule, chosen by the fleet):
--   event_rules.clip (true)                                           -> the Recording switch each of the nine
--        driver-camera rows of the Configuration page gets. Off means the driver is still warned, the row is still
--        written with its reading, duration and place, and no seconds of film are made for it. Default true is
--        exactly what every camera rule did before the switch, so running this file changes nothing until somebody
--        ticks a box. Vehicle rules hold the column and never read it: the driver camera is the only lens here.
--   Run this file before publishing the phone build that reads the switch and the dashboard build that draws it.
--   An older phone records for every camera rule, which is the behaviour this column has until then; an older
--   dashboard simply has no box to tick and its save does not send the word.
--
-- v27, on top of the above (the hand-at-the-mouth rule called by what a fleet acts on):
--   update event_rules set label='Smoking' where type='dms_smoking' and label='Hand at the mouth'
--        -> one line, and the only part of it a live project needs. The v25 seed writes the new words but says
--        "do nothing on conflict", so a project that already ran v25 keeps the old label from the database: the
--        Configuration row, the phone's alert and the Events table all print this column. The rule measures the
--        same thing as before (the shipped model has no cigarette class, so the reading burned into the clip still
--        says a hand at the face); only the name changed, and an administrator's own wording is left alone.
--        Both builds already say Smoking, so run this whenever convenient - a project that skips it shows one
--        old name and nothing else goes wrong.
--
-- v28, on top of the above (one handset per account, so a vehicle cannot be driven twice):
--   device_claim (one row per account: did, model, since, renewed_at, tripping, trip_since, tried_did/model/at)
--        -> the seat. A handset running this build names itself there and renews about every half minute; a
--        second handset signing in as the same account is refused while the first keeps answering, and the first
--        one is only ever told that somebody tried. Reads are limited to the account itself and to an
--        administrator; the row changes only through the two functions below.
--   device_take(p_did, p_model, p_trip) -> takes the seat when it is empty, already this handset's, or left for
--        two minutes (five while a trip runs), and otherwise stamps the attempt and says who is sitting in the
--        seat. Granted to authenticated only.
--   device_give(p_did)                  -> sign-out emptying the seat, and only ever the handset in it.
--        -> Re-run the three revoke/grant lines above if this project already ran v28: Supabase grants a new
--        function to anon, authenticated and service_role separately, so the original "revoke from public" left
--        the anon key that ships inside every handset able to call both. Proved on the live project on
--        2026-11-01 - a bare POST to rpc/device_give answered 204 and rpc/device_take 200 with no sign-in at
--        all. Neither one moved a seat, because both ask auth.uid() and an anonymous caller has none, so this
--        is a door closed for what might come later rather than a hole that exists now; it costs nothing to fix
--        and nothing to re-run.
--   Run this file BEFORE publishing the phone build that claims a seat. Until then no handset writes here, so
--   every driver gets in on every phone as before and the Users page shows no phone for anybody - which is the
--   honest state of a project that has the table but not the handsets. Publish the dashboard build after the
--   phone one: its Release button only has rows to delete once handsets are making them.
--   Releasing a seat by hand is the answer for a phone that is lost, broken or left in a drawer.
--
-- v29, on top of the above (the trips the phone makes when nobody presses a button):
--   app_settings keys 'auto_start_on' (1), 'auto_start_s' (5), 'auto_end_on' (1),
--        'auto_end_min' (10), 'auto_kmh' (5)   -> the two halves of the automatic trip, each with its
--        own switch and its own wait. A handset that is not in a trip keeps one background watcher alive
--        for this alone; auto_start_s seconds above auto_kmh km/h starts the trip, and auto_end_min
--        minutes below it ends the one that is running. The same speed is the rule for both, so one
--        number says what moving means. Turn either half off and the button on the driver's screen is
--        the only thing that can move the trip - the phone then never opens or closes a record by
--        itself. Nothing is renamed or dropped: an absent key reads as these same defaults, so a project
--        that skips this migration behaves exactly as it did, and an old phone build ignores keys it has
--        never heard of.
--   Configuration page, "Trips the phone starts and ends by itself" -> the two switches and the three
--        boxes. The driver's own Start/End tooltips and the two banners quote the numbers the fleet set
--        rather than the old fixed five seconds and ten minutes.
--   Run this BEFORE publishing the phone build that reads the switches. Until then a handset keeps the
--        hardcoded behaviour, so a fleet that saves Off here and does not update its handsets sees trips
--        still starting on their own.
--
-- v30, on top of the above (the driver's own camera stop, written down):
--   the events insert policy takes sixteen types instead of fifteen: cam_off.
--        -> The driver's video button now puts down the trip's judging as well as his own
--        picture, and stays down until he presses it again, so a quiet trip can be read two
--        ways from the map. This row is the difference: his handset opens it with the press
--        and closes it with ended_at when the lens comes back or the trip ends.
--        -> Page-written, like a zone crossing: no event_rules row, no Configuration switch,
--        no clip and no Java. It has no voice wording in any language because nothing
--        announces a switch the driver pressed himself.
--   Run this BEFORE publishing the phone build that writes it. Until then the database refuses
--        the type, the handset says so and drops the record, and the office cannot tell a
--        stopped camera from a clean shift - which is the one thing this row exists to say.
--
-- v31, on top of the above (the handset names a lost satellite lock):
--   two event_rules rows: gps_lost (threshold 120 s, recorded, alert on)
--        and gps_recovered (no threshold: a moment, like a zone crossing).
--        -> gps_lost is the wait before anything is said, in seconds: the phone's own location
--        watcher delivers a fix every second while it can, so two minutes of silence means the
--        position is genuinely gone, not merely throttled. Set it wide for a fleet that drives
--        tunnels and under bridges, narrow for one that must never disappear.
--        -> Switching gps_lost off at the Recorded column stops both rows: the close belongs to
--        the breach, and an office that gets gps_recovered without gps_lost would read a
--        recovery from an outage it was never told about.
--        -> Both are ordinary rule rows, so the Visual alert / Buzzer / Voice switches work on
--        them like any other event, and the driver is warned out loud the moment his vehicle
--        stops being seen.
--   the events insert policy takes eighteen types instead of sixteen: gps_lost, gps_recovered.
--   Run this BEFORE publishing the phone build that writes them. Until then a handset with the
--        wait elapsed has its row refused by the database and says so, and the route keeps
--        looking like a vehicle that simply stopped moving.
--
-- v32, on top of the above (the hand at the mouth has to leave a mark):
--   update event_rules set threshold = 70 where type = 'dms_smoking' and threshold < 70;
--   update event_rules set hold_s = 3      where type = 'dms_smoking' and hold_s < 3;
--        -> the smoking rule used to answer one colour question: are these lips this driver's
--        own skin. A plain closed mouth in a sunlit cab answers that as well, so a fleet that
--        switched the rule on was handed an event every few seconds about a driver doing
--        nothing at all, which is the complaint this version exists to end.
--        -> The other half of the fix is in the phone's own code - the dark line of the mouth
--        has to have gone too, and only something laid over it takes that away - and that half
--        needs a rebuilt APK. These two lines are the half that works today: 70 is four of the
--        five pictures a second rather than three of them, and a three second hold is
--        a hand that stayed there, not a scratch.
--        -> Both lines only raise a number that was still at or under the old shipped default,
--        so a fleet that had already set either one the stricter way keeps its own setting, and
--        the rule stays seeded switched off.
--   Run this whenever convenient: no build depends on it, and a project that skips it keeps the
--   looser numbers on a rule that is off unless somebody ticked it.
--
-- v33, on top of the above (the doors an anonymous caller could still push):
--   all of it is in the body above, so re-running this file is the whole step. Nothing is
--   seeded and no row is rewritten.
--        -> four functions lose a grant they never needed (login_ok and clear_pw_flag stop
--        answering the anon key; login_cfg and login_sweep stop answering a stranger), and no
--        screen in either app loses a door: both pages ask those four only after a sign-in has
--        handed them a token.
--        -> three triggers add the invariants the policies could not say: a handset may answer
--        a blackbox request without rewriting it, an Activity Log entry is stamped with the
--        name of the account it is really about, and a position or event dated further back
--        than the fleet's own fourteen day blackbox - or more than two hours into the future -
--        is refused instead of silently splitting a trip that never happened.
--        -> The one thing to know before running it on a project with old data: a manual import
--        of history older than 45 days will be refused by that last trigger. Import first, or
--        run this version after the import, and nothing else about the fleet changes.
--
-- v34, on top of the above (the two reads that got slower every week):
--   all of it is in the body above; re-running this file is the whole step. One new table
--   (latest_pos), one trigger on positions, one rebuilt view and two indexes.
--        -> Live Tracking asks the database the same question as always and gets the same rows
--        back with the same rights, but from a pointer table the size of the fleet instead of
--        a walk of the size of its history. Nothing in either app changes, so no build is
--        owed alongside it and none is locked out by running it.
--        -> The first run backfills the pointers with the one full read this version exists to
--        stop repeating: on a project with years of positions that paste takes a few seconds
--        in the SQL Editor and never runs again.
--        -> If the optional history purge has ever deleted a vehicle's very newest position,
--        that vehicle has no marker until its phone reports again. It had none under the old
--        view either, and a driver whose handset is working fills the pointer on his next fix.
--
-- v35, on top of the above (a parked night is not a shift of idling):
--   vehicle_report()                                                     -> its stop_min term ignores a point the
--        handset wrote with the ignition down.
--        -> The phone now reports at the fleet's two intervals all day, whether or not anybody pressed Start, so
--        a vehicle shut off at the depot for the night keeps saying where it stands. Those rest points are nearer
--        to each other than the parked bound this function joins on, and every one of them used to be counted as
--        idling: a two hour shift printed as twenty-two hours of standing, and the Fuel idling column turned that
--        into litres the engine never burned. The Fleet Summary chart, its table and its fuel figures all read
--        this one column, so the correction belongs here rather than in the page.
--        -> Idling keeps its meaning for everything the phone could not tell us: a point from a build that never
--        answered about the ignition (the column is null) counts exactly as it did, and a driver stopped with the
--        engine running still writes ignition true, so his real idle minutes and their fuel are untouched.
--        -> km, max_speed, drive_min and over_s are unchanged; the dashboard's own per-trip idling already
--        measures inside a trip, and a rest point is never joined into one.
--   Run this file again - it is create-or-replace, so one paste is the whole step. No build is owed with it: an
--   older dashboard simply shows the old inflated numbers, and a newer one shows the truth either way.
--
-- v36, on top of the above (the pointer that refused every position):
--   stamp_latest()                                                       -> SECURITY DEFINER now, like every other
--        function in this file that writes a second table on somebody's behalf. Same signature, same body, same
--        trigger; nothing else moves.
--   Run this file again - one paste is the whole step, and it repairs a live database from the inside: no app
--        version fixes it and no app version is made wrong by running it.
--        -> Until it is run, EVERY position the fleet writes is refused. The page, the handset's background runner
--        and a blackbox push all insert into positions, and v34's after-insert trigger then tries to move
--        latest_pos as the caller - a table a caller has no right to write. The row is rolled back with "new row
--        violates row-level security policy for table "latest_pos"", the driver's screen fills with dropped-record
--        messages, and Historical Tracking shows no trips, the Tacho report says there is no data in the period
--        and the Fleet Summary says no positions were recorded - because all three are counted from those rows.
--        Events keep arriving, since events carry no such trigger: that split is what those symptoms are made of.
--        -> v34's own note above claimed no build was owed with it and nothing was locked out by running it. That
--        was wrong: the version it added lands a pointer through a trigger, and a trigger that cannot write its
--        table refuses the insert it was there to decorate. The claim is corrected here rather than there, so the
--        v34 block stays the description of what that version created.
--        -> The other two messages on that screen are app ones, and no schema change answers them: "All object
--        keys must match" (the runner sent a batch whose rows did not all name the same columns) and a refused
--        alert row. Both are fixed in the build alongside this file.
--   The pointers need no repair: while this was failing no position was accepted at all, so nothing landed that a
--   pointer missed, and each handset fills its own row in again on the first fix it sends after the paste.
--
-- v37, on top of the above (the one journey the office counted eight times):
--   app_settings 'auto_end_grace_min' ('3')                                    -> one new key, no table, column,
--        policy, function or view. It is the tolerance a phone holds a trip open for after auto_end_min has
--        already expired; 0 means close the trip the moment that first wait runs out, which is what every handset
--        did until this build.
--   -> Nothing is owed by running it, and no app version is made wrong by it: a phone that cannot see the key
--        obeys the same 3 minutes as its own default, so an older dashboard simply has no box for it. The card
--        "Trips the phone starts and ends by itself" on the Configuration page is where a fleet changes it, and
--        the handsets pick the new number within five minutes or at the next trip start.
--   -> v29's note above is left as it wrote itself: it described the two waits that version seeded, and at that
--        moment the standing wait really was the only thing that ended a trip. The eight-trips-from-one-shift
--        symptom belonged to that design, not to the paste, and is answered here.
--   -> The reports needed no change. Trip boundaries are still cut purely from the ignition column: what this
--        version changes is how often a handset puts a false value in it, so Historical Tracking, the Trip list,
--        the Tacho report and vehicle_report stop receiving the splits rather than learning to merge them.
--        A trip that was already recorded as several stays several - rows on the server cannot be repaired from
--        the phone, and the office that needs an old shift re-cut has to delete the wrongly-opened records by hand.
--
-- v38, on top of the above (which of the two apps is in the cab):
--   user_activity.app                                                    -> the app that wrote the entry:
--        'main', 'lite' or 'dashboard'. Nullable, with no default, so an entry from a build that predates this
--        keeps a blank rather than guessing. The Activity Log prints it beside every row.
--   vehicle_apps()                                                       -> one row per vehicle holding the app
--        its account last signed in or opened with, and when. The Vehicles page prints it, so a vehicle moved
--        from the full app to the Lite one changes column the next time that driver signs in.
--   Run this file BEFORE uploading the apps and the dashboard. This is the ordering v24 and v28 already taught:
--        the three logAct writers now name an app column on every insert, and against a database that has not
--        run this block PostgREST refuses the whole row with "column app does not exist". logAct swallows that
--        by design - a logging failure must never stop a trip - so the symptom is not an error on the phone but
--        an Activity Log that quietly stops receiving entries until the paste is done.
--   -> Nothing here repairs old rows: an entry written before this version has no app, and the Vehicles page
--        shows a blank for a vehicle whose account has not signed in since. Both fill in from the next sign-in.
--   -> vehicle_apps() is deliberately not security definer. It reads user_activity, which only an admin may
--        read, so it answers a normal user with nothing rather than leaking another account's movements.
--   -> The vehicle comes from assignments, the same link the phone itself reads at sign-in. An account given
--        no vehicle - a supervisor's own login, typically - has no cab to name, so every row stays blank for
--        it however often it signs in. Give that account a vehicle on the Users page to see the column move.

-- ============================================================================
