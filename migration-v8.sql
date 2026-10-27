-- ============================================================
-- Fleet Tracker Database Changes for New Features
-- Run this file in Supabase > SQL Editor > New query
-- This file contains ALL changes needed for Categories A-G modifications
-- ============================================================

-- 1. Add custom config column to vehicles (E2: Custom vs Default badge)
-- Vehicles with custom settings show "Custom" badge, defaults show "Default"
ALTER TABLE vehicles ADD COLUMN IF NOT EXISTS custom BOOLEAN DEFAULT FALSE;

-- 2. Add configurable buzzer fields to event_rules (E3: Buzzer timing)
-- beep_duration: seconds for buzzer to sound (default 5)
-- beep_type: 'continuous' or 'discontinuous' (default 'discontinuous')
ALTER TABLE event_rules ADD COLUMN IF NOT EXISTS beep_duration INT DEFAULT 5;
ALTER TABLE event_rules ADD COLUMN IF NOT EXISTS beep_type TEXT DEFAULT 'discontinuous';

-- 3. Add new configuration fields to vehicles (E5: More control panel)
-- min_moving_distance: minimum distance for fuel calculation (km)
-- speed_buffer: extra margin beyond speed limit before alert (km/h)
-- max_accel_warning: max acceleration warning threshold (km/h/s)
ALTER TABLE vehicles ADD COLUMN IF NOT EXISTS min_moving_distance NUMERIC DEFAULT 0;
ALTER TABLE vehicles ADD COLUMN IF NOT EXISTS speed_buffer NUMERIC DEFAULT 10;
ALTER TABLE vehicles ADD COLUMN IF NOT EXISTS max_accel_warning NUMERIC DEFAULT 3;

-- ============================================================
-- End of file
-- ============================================================
