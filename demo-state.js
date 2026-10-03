(function (root, factory) {
  const api = factory(root);
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.HarborDemoState = api;
})(typeof globalThis !== 'undefined' ? globalThis : this, function (root) {
  'use strict';

  const STORAGE_KEY = 'harborDemoStateV1';
  const VALID_MODES = new Set(['setup', 'parent', 'kid-sofia', 'kid-mateo']);
  const VALID_CHILDREN = new Set(['sofia', 'mateo']);
  const VALID_REQUEST_MINUTES = new Set([15, 30, 60]);

  function getDefaultStorage(storage) {
    if (storage) return storage;
    if (root && root.localStorage) return root.localStorage;
    return null;
  }

  function createInitialState() {
    return {
      parentProfile: { name: '', email: '' },
      familyName: '',
      onboardingComplete: false,
      selectedMode: 'setup',
      selectedChildId: 'sofia',
      timeRequests: [],
      checkIns: [],
      helpEvents: [],
      bonusMinutesByChild: { sofia: 0, mateo: 0 },
      kidSpaceByChild: {
        sofia: { enabled: false, allowedApps: ['Google Classroom', 'Phone'], exitPin: '1234' },
        mateo: { enabled: false, allowedApps: ['YouTube Kids', 'Minecraft', 'Messages', 'Phone'], exitPin: '4821' },
      },
    };
  }

  function safeArray(value) { return Array.isArray(value) ? value.filter(Boolean) : []; }
  function normalizeAllowedApps(value, fallback) { if (!Array.isArray(value)) return [...fallback]; return [...new Set(value.filter((item) => typeof item === 'string').map((item) => item.trim()).filter(Boolean))]; }
  function normalizeKidSpace(value, fallback) {
    const input = value && typeof value === 'object' ? value : {};
    const pin = typeof input.exitPin === 'string' && /^\d{4,6}$/.test(input.exitPin.trim()) ? input.exitPin.trim() : fallback.exitPin;
    return { enabled: input.enabled === true, allowedApps: normalizeAllowedApps(input.allowedApps, fallback.allowedApps), exitPin: pin };
  }
  function normalizeState(value) {
    const defaults = createInitialState(); const input = value && typeof value === 'object' ? value : {};
    const parent = input.parentProfile && typeof input.parentProfile === 'object' ? input.parentProfile : {};
    const bonus = input.bonusMinutesByChild && typeof input.bonusMinutesByChild === 'object' ? input.bonusMinutesByChild : {};
    const kidSpace = input.kidSpaceByChild && typeof input.kidSpaceByChild === 'object' ? input.kidSpaceByChild : {};
    const legacyHelpEvents = Array.isArray(input.helpEvents) ? input.helpEvents : input.sosEvents;
    return {
      parentProfile: { name: typeof parent.name === 'string' ? parent.name : '', email: typeof parent.email === 'string' ? parent.email : '' },
      familyName: typeof input.familyName === 'string' ? input.familyName : defaults.familyName,
      onboardingComplete: input.onboardingComplete === true,
      selectedMode: VALID_MODES.has(input.selectedMode) ? input.selectedMode : defaults.selectedMode,
      selectedChildId: VALID_CHILDREN.has(input.selectedChildId) ? input.selectedChildId : defaults.selectedChildId,
      timeRequests: safeArray(input.timeRequests).map((item) => ({ id: String(item.id || ''), childId: VALID_CHILDREN.has(item.childId) ? item.childId : 'sofia', minutes: VALID_REQUEST_MINUTES.has(Number(item.minutes)) ? Number(item.minutes) : 15, status: item.status === 'approved' ? 'approved' : 'pending', createdAt: Number.isFinite(Number(item.createdAt)) ? Number(item.createdAt) : Date.now(), approvedAt: Number.isFinite(Number(item.approvedAt)) ? Number(item.approvedAt) : null })).filter((item) => item.id),
      checkIns: safeArray(input.checkIns).map((item) => ({ id: String(item.id || ''), childId: VALID_CHILDREN.has(item.childId) ? item.childId : 'sofia', createdAt: Number.isFinite(Number(item.createdAt)) ? Number(item.createdAt) : Date.now() })).filter((item) => item.id),
      helpEvents: safeArray(legacyHelpEvents).map((item) => ({ id: String(item.id || ''), childId: VALID_CHILDREN.has(item.childId) ? item.childId : 'sofia', createdAt: Number.isFinite(Number(item.createdAt)) ? Number(item.createdAt) : Date.now() })).filter((item) => item.id),
      bonusMinutesByChild: { sofia: Math.max(0, Number(bonus.sofia) || 0), mateo: Math.max(0, Number(bonus.mateo) || 0) },
      kidSpaceByChild: { sofia: normalizeKidSpace(kidSpace.sofia, defaults.kidSpaceByChild.sofia), mateo: normalizeKidSpace(kidSpace.mateo, defaults.kidSpaceByChild.mateo) },
    };
  }
  function load(storage) { const target = getDefaultStorage(storage); if (!target || typeof target.getItem !== 'function') return createInitialState(); try { const raw = target.getItem(STORAGE_KEY); if (!raw) return createInitialState(); return normalizeState(JSON.parse(raw)); } catch (_error) { return createInitialState(); } }
  function save(state, storage) { const target = getDefaultStorage(storage); const clean = normalizeState(state); if (target && typeof target.setItem === 'function') { try { target.setItem(STORAGE_KEY, JSON.stringify(clean)); } catch (_error) {} } return clean; }
  function reset(storage) { const target = getDefaultStorage(storage); if (target && typeof target.removeItem === 'function') { try { target.removeItem(STORAGE_KEY); } catch (_error) {} } return save(createInitialState(), target); }
  function validateParentAccount(input) { const value = input && typeof input === 'object' ? input : {}; const name = String(value.name || '').trim(); const email = String(value.email || '').trim(); const password = String(value.password || ''); const confirmPassword = String(value.confirmPassword || ''); const errors = {}; if (!name) errors.name = 'Enter your name.'; if (!email) errors.email = 'Enter your email address.'; else if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) errors.email = 'Enter a valid email address.'; if (!password) errors.password = 'Enter a password.'; else if (password.length < 8) errors.password = 'Use at least 8 characters.'; if (!confirmPassword) errors.confirmPassword = 'Confirm your password.'; else if (password !== confirmPassword) errors.confirmPassword = 'Passwords do not match.'; return { valid: Object.keys(errors).length === 0, errors }; }
  function validatePairingCode(code) { return /^\d{6}$/.test(String(code || '').replace(/\s+/g, '')); }
  function uniqueId(prefix) { const rand = Math.random().toString(36).slice(2, 8); return `${prefix}-${Date.now().toString(36)}-${rand}`; }
  function submitTimeRequest(childId, minutes, storage) { if (!VALID_CHILDREN.has(childId)) throw new Error('Unknown child.'); const amount = Number(minutes); if (!VALID_REQUEST_MINUTES.has(amount)) throw new Error('Time requests must be 15, 30, or 60 minutes.'); const state = load(storage); const existing = state.timeRequests.find((item) => item.childId === childId && item.minutes === amount && item.status === 'pending'); if (existing) return existing; const request = { id: uniqueId('req'), childId, minutes: amount, status: 'pending', createdAt: Date.now(), approvedAt: null }; state.timeRequests.unshift(request); save(state, storage); return request; }
  function approveTimeRequest(requestId, storage) { const state = load(storage); const request = state.timeRequests.find((item) => item.id === String(requestId)); if (!request) return null; if (request.status !== 'approved') { request.status = 'approved'; request.approvedAt = Date.now(); state.bonusMinutesByChild[request.childId] = (state.bonusMinutesByChild[request.childId] || 0) + request.minutes; save(state, storage); } return request; }
  function recordCheckIn(childId, storage) { if (!VALID_CHILDREN.has(childId)) throw new Error('Unknown child.'); const state = load(storage); const event = { id: uniqueId('checkin'), childId, createdAt: Date.now() }; state.checkIns.unshift(event); save(state, storage); return event; }
  function recordHelpRequest(childId, storage) { if (!VALID_CHILDREN.has(childId)) throw new Error('Unknown child.'); const state = load(storage); const event = { id: uniqueId('help'), childId, createdAt: Date.now() }; state.helpEvents.unshift(event); save(state, storage); return event; }
  function recordSOS(childId, storage) { return recordHelpRequest(childId, storage); }
  function setKidSpace(childId, config, storage) { if (!VALID_CHILDREN.has(childId)) throw new Error('Unknown child.'); const state = load(storage); const current = state.kidSpaceByChild[childId]; const input = config && typeof config === 'object' ? config : {}; state.kidSpaceByChild[childId] = normalizeKidSpace({ enabled: input.enabled === undefined ? current.enabled : input.enabled, allowedApps: input.allowedApps === undefined ? current.allowedApps : input.allowedApps, exitPin: input.exitPin === undefined ? current.exitPin : input.exitPin }, current); save(state, storage); return state.kidSpaceByChild[childId]; }
  return { STORAGE_KEY, createInitialState, load, save, reset, validateParentAccount, validatePairingCode, submitTimeRequest, approveTimeRequest, recordCheckIn, recordHelpRequest, recordSOS, setKidSpace };
});
