-- Run ONCE in Supabase > SQL Editor > New query
-- 1) live view must include the new GPS columns (accuracy, elevation, direction, battery ...)
create or replace view latest_positions with (security_invoker = true) as
  select distinct on (user_id) * from positions order by user_id, recorded_at desc;
-- 2) security: a login may only write data for the vehicle it is linked to (stops fake data for other vehicles)
drop policy if exists "pos insert own" on positions;
drop policy if exists "events insert own" on events;
create policy "pos insert own" on positions for insert
  with check (user_id = auth.uid() and vehicle_id in (select vehicle_id from assignments where user_id = auth.uid()));
create policy "events insert own" on events for insert
  with check (user_id = auth.uid() and vehicle_id in (select vehicle_id from assignments where user_id = auth.uid()));
