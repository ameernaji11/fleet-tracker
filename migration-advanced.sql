-- Run ONCE in Supabase > SQL Editor > New query (this is a new file; do not re-run schema.sql)
create table events (
  id bigint generated always as identity primary key,
  user_id uuid not null, vehicle_id uuid, driver_id uuid,
  vehicle_name text, driver_name text,
  type text not null,            -- harsh_braking | harsh_acceleration | overspeed
  lat double precision, lng double precision,
  speed double precision, value double precision,   -- value = m/s2 (braking/accel) or km/h (overspeed)
  recorded_at timestamptz not null default now());
create index on events (vehicle_id, recorded_at desc);
create index on events (type, recorded_at desc);
alter table events enable row level security;
create policy "events insert own" on events for insert with check (user_id = auth.uid());
create policy "events admin read" on events for select using (is_admin());
alter publication supabase_realtime add table events;

-- Fleet report computed inside the database (fast, no 1000-row limit)
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
