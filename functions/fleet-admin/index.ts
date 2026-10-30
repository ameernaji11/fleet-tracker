// Fleet Tracker - admin-only account actions added in v18.
//
// WHY THIS FILE EXISTS
// The dashboard and the phone are both loaded with Supabase's public (anon) key,
// which can read and write the fleet tables but can never change another person's
// password - only a server-only key can do that, and a server-only key must never
// be put in a web page or an APK, where anyone who views the source has it. So the
// Users page asks this function instead. Supabase runs it on its own server and
// hands it the server-only key itself, so no key is typed anywhere by us and none
// ever leaves Supabase.
//
// HOW TO DEPLOY (once, about a minute)
//   Supabase dashboard > your project > Edge Functions > Deploy new function
//   Name: fleet-admin        (exactly this, lower case)
//   Paste this whole file as index.ts, leave "Verify JWT" ON, Deploy.
// Without it the lockout still works everywhere; only the "Reset password" button
// reports that the service is missing, and the administrator can still unlock.
//
// WHAT IT ACCEPTS, and only from a caller whose own profile says role = admin
//   POST {action:'set_password', user_id, password}  - give that account a new
//        password and make it change the password on its next sign-in
//   POST {action:'unlock', user_id}                  - clear the lock, its expiry and
//        the whole count of wrong tries (v21 added the two stamped columns to that)
import { createClient } from 'https://esm.sh/@supabase/supabase-js@2';

const json = (body, status) =>
  new Response(JSON.stringify(body), {
    status: status || 200,
    headers: { 'Content-Type': 'application/json' },
  });

// v21 gave the lock two more columns: locked_until, the moment a lockout ends by
// itself, and fails_at, the stamp that decides when the count of wrong tries ages
// out. An administrator's action puts both down with the lock and the count. A
// database that has not run the newest schema.sql has neither column, and one failed
// update would leave the row half reset, so the older shape is retried once.
async function resetLock(admin, id, extra) {
  const full = { fails: 0, fails_at: null, locked_at: null, locked_until: null, ...(extra || {}) };
  let { error } = await admin.from('profiles').update(full).eq('id', id);
  if (error && /column .* does not exist/i.test(error.message || '')) {
    ({ error } = await admin
      .from('profiles')
      .update({ fails: 0, locked_at: null, ...(extra || {}) })
      .eq('id', id));
  }
  return error;
}

Deno.serve(async (req) => {
  try {
    const url = Deno.env.get('SUPABASE_URL');
    const anon = Deno.env.get('SUPABASE_ANON_KEY');
    const srv = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY');
    if (!url || !anon || !srv) return json({ error: 'This function is missing its Supabase keys.' }, 500);

    const head = req.headers.get('authorization') || '';
    if (head.toLowerCase().indexOf('bearer ') !== 0) return json({ error: 'Sign in first.' }, 401);

    // Ask as the caller, not as the server: Supabase checks the token and row-level
    // security still applies, so a stolen-or-forged token simply fails here.
    const asUser = createClient(url, anon, {
      global: { headers: { Authorization: head } },
      auth: { persistSession: false, autoRefreshToken: false },
    });
    const { data: mine, error: whoErr } = await asUser.auth.getUser(head.slice(7));
    if (whoErr || !mine || !mine.user) return json({ error: 'Your session has ended. Sign in again.' }, 401);

    const admin = createClient(url, srv, { auth: { persistSession: false, autoRefreshToken: false } });
    const { data: prof, error: profErr } = await admin.from('profiles').select('role').eq('id', mine.user.id).single();
    if (profErr) return json({ error: profErr.message }, 500);
    if (!prof || prof.role !== 'admin') return json({ error: 'Only an administrator can change another account.' }, 403);

    const body = await req.json().catch(() => ({}));
    const id = String((body && body.user_id) || '');
    if (!/^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/.test(id)) {
      return json({ error: 'No account was given.' }, 400);
    }
    const action = String((body && body.action) || '');

    if (action === 'set_password') {
      const pw = String((body && body.password) || '');
      if (pw.length < 6) return json({ error: 'The new password must be at least 6 characters.' }, 400);
      const { error } = await admin.auth.admin.updateUserById(id, { password: pw, email_confirm: true });
      if (error) return json({ error: error.message }, 500);
      // the flag matters: without it the account keeps using a password somebody else
      // typed, so a profile that could not be written is reported rather than ignored
      const err = await resetLock(admin, id, { must_change: true });
      if (err) return json({ error: 'The password is set, but this account could not be told to replace it: ' + err.message }, 500);
      return json({ ok: true });
    }

    if (action === 'unlock') {
      const { error } = await admin.auth.admin.updateUserById(id, { ban_duration: '0s' });
      if (error) return json({ error: error.message }, 500);
      const err2 = await resetLock(admin, id);
      if (err2) return json({ error: err2.message }, 500);
      return json({ ok: true });
    }

    return json({ error: 'Unknown action.' }, 400);
  } catch (e) {
    return json({ error: String((e && e.message) || e) }, 500);
  }
});
