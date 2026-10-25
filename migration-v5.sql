-- Run ONCE in Supabase > SQL Editor > New query
alter table vehicles add column fuel_moving real, add column fuel_idle real;   -- L/100km while moving, L/hour idling
alter table event_rules add column beep boolean default true;                   -- "alert" now means visual alert only
update event_rules set threshold = 11 where type = 'harsh_braking';            -- now km/h per second
update event_rules set threshold = 9  where type = 'harsh_acceleration';

-- Normal users see only their own vehicle's data; admins see everything
drop policy "vehicles read" on vehicles;
drop policy "drivers read" on drivers;
drop policy "pos admin read" on positions;
drop policy "events admin read" on events;
create policy "vehicles read" on vehicles for select using (is_admin() or id in (select vehicle_id from assignments where user_id = auth.uid()));
create policy "drivers read"  on drivers  for select using (is_admin() or id in (select driver_id from assignments where user_id = auth.uid()));
create policy "positions read" on positions for select using (is_admin() or vehicle_id in (select vehicle_id from assignments where user_id = auth.uid()));
create policy "events read"    on events    for select using (is_admin() or vehicle_id in (select vehicle_id from assignments where user_id = auth.uid()));
