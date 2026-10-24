import test from 'node:test';
import assert from 'node:assert/strict';
import { applicationServerKey, installationId, pushConfigured } from '../js/push.js';

test('the public VAPID key becomes the bytes the subscription API wants', () => {
  // A real VAPID public key: 65 bytes, url-safe base64 and unpadded, which is what the backend's own check demands.
  const key = 'BJ' + 'A'.repeat(85);
  const bytes = applicationServerKey(key);
  assert.ok(bytes instanceof Uint8Array);
  assert.equal(bytes.length, 65);
  assert.equal(bytes[0], 4, 'an uncompressed P-256 point starts with 0x04');
});

test('url-safe characters are decoded, not passed through', () => {
  // "-" and "_" stand for "+" and "/", so a key carrying them must still decode.
  const bytes = applicationServerKey('-_-_');
  assert.deepEqual([...bytes], [251, 255, 191]);
});

test('an empty key is refused rather than used', () => {
  assert.throws(() => applicationServerKey(''), /application server key/i);
  assert.throws(() => applicationServerKey(null), /application server key/i);
});

test('this build knows whether it can offer notifications at all', () => {
  // No VITE_VAPID_PUBLIC_KEY is set under test, so the app must report itself unconfigured instead of half-working.
  assert.equal(pushConfigured(), false);
});

test('an installation id is produced even where no storage is available', () => {
  // Node has no localStorage; the browser equivalent is a private window with site data blocked.
  const id = installationId();
  assert.equal(typeof id, 'string');
  assert.ok(id.length >= 8);
});
