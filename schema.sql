-- Run once in Supabase > SQL Editor
create table profiles (id uuid primary key references auth.users on delete cascade, email text, role text not null default 'user');
create table vehicles (id uuid primary key default gen_random_uuid(), name text not null, plate text, created_at timestamptz default now());
create table drivers  (id uuid primary key default gen_random_uuid(), name text not null, phone text, created_at timestamptz default now());
create table assignments (user_id uuid primary key references auth.users on delete cascade, vehicle_id uuid references vehicles on delete set null, driver_id uuid references drivers on delete set null);
create table positions (
  id bigint generated always as identity primary key,
  user_id uuid not null, vehicle_id uuid, driver_id uuid,
  vehicle_name text, driver_name text,
  lat double precision not null, lng double precision not null,
  speed double precision default 0,           -- km/h
  recorded_at timestamptz not null default now());
create index on positions (vehicle_id, recorded_at desc);
create index on positions (user_id, recorded_at desc);

create view latest_positions with (security_invoker = true) as
  select distinct on (user_id) * from positions order by user_id, recorded_at desc;

-- auto-create profile on signup
create function handle_new_user() returns trigger language plpgsql security definer set search_path = public as $$
begin insert into profiles(id,email) values (new.id,new.email); return new; end $$;
create trigger on_signup after insert on auth.users for each row execute function handle_new_user();

create function is_admin() returns boolean language sql security definer stable set search_path = public as
$$ select exists(select 1 from profiles where id = auth.uid() and role = 'admin') $$;

alter table profiles enable row level security;
alter table vehicles enable row level security;
alter table drivers enable row level security;
alter table assignments enable row level security;
alter table positions enable row level security;

create policy "profiles read"  on profiles for select using (id = auth.uid() or is_admin());
create policy "profiles admin" on profiles for all using (is_admin()) with check (is_admin());
create policy "vehicles read"  on vehicles for select using (auth.uid() is not null);
create policy "vehicles admin" on vehicles for all using (is_admin()) with check (is_admin());
create policy "drivers read"   on drivers  for select using (auth.uid() is not null);
create policy "drivers admin"  on drivers  for all using (is_admin()) with check (is_admin());
create policy "assign read"    on assignments for select using (user_id = auth.uid() or is_admin());
create policy "assign admin"   on assignments for all using (is_admin()) with check (is_admin());
create policy "pos insert own" on positions for insert with check (user_id = auth.uid());
create policy "pos admin read" on positions for select using (is_admin());

alter publication supabase_realtime add table positions;

-- Optional: keep the free 500 MB database small (needs pg_cron: Database > Extensions)
-- select cron.schedule('purge-old-positions','0 3 * * *',$$delete from positions where recorded_at < now() - interval '90 days'$$);

-- After you create your own account, make yourself admin:
-- update profiles set role='admin' where email='YOUR@EMAIL.COM';
