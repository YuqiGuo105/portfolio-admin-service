// Local stand-in for the external scheduler. One bounded HTTP request at a time.
const target = new URL(process.env.MEDIA_RECOVERY_URL || '');
if (target.hostname !== '127.0.0.1' || target.protocol !== 'http:' || target.pathname !== '/api/internal/media/recover') {
  throw new Error('Local loopback media recovery endpoint required');
}
const token = process.env.MEDIA_RECOVERY_TOKEN || '';
if (token.length < 32) throw new Error('A dedicated recovery token of at least 32 characters is required');
const interval = Math.max(1000, Number(process.env.MEDIA_RECOVERY_INTERVAL_MS) || 60000);
let stopped = false;
let active;
let timer;
let resume;
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => {
  stopped = true; active?.abort(); clearTimeout(timer); resume?.();
});
while (!stopped) {
  active = new AbortController();
  const timeout = setTimeout(() => active.abort(), 900000);
  try {
    const response = await fetch(target, { method: 'POST', redirect: 'error', signal: active.signal,
      headers: { 'X-Media-Recovery-Token': token } });
    if (!response.ok) throw new Error(`Recovery HTTP ${response.status}`);
    console.log('Media recovery', await response.json());
  } catch (error) {
    if (!stopped) console.error('Recovery will retry next tick:', error.name);
  } finally { clearTimeout(timeout); }
  if (!stopped) await new Promise(resolve => { resume = resolve; timer = setTimeout(resolve, interval); });
}
