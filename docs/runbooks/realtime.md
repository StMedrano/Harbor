# Private family Realtime

Use `family:<family UUID>` with client channel configuration `private: true`.
Before enabling Realtime in a development or production project, disable
**Allow public access** in Supabase Realtime settings. A SQL receive policy does
not disable public channels. This project setting and live WebSocket acceptance
remain deployment checks; CI here verifies database authorization.

Harbor permits active owner/parent members to receive Broadcast hints for their
family. Other-family parents, removed members at authorization refresh, and
anonymous child-device identities are denied. Presence and client publishing
have no Harbor authorization policy. Topics are compared as text; malformed or
nonexistent family topics fail closed.

Broadcasts are refresh hints only. Server publishers must use minimal version,
kind, and route identifiers, never raw location, messages, credentials, or domain
content. Clients refresh authoritative state through normal authorized APIs.
No broadcast sender or authoritative state mutation is introduced by this policy.

Supabase calculates channel permissions at join/authorization refresh. Existing
connections may retain cached permissions until reauthorization. Membership
removal handling must disconnect/rejoin clients or force an authorization refresh;
do not use broadcasts for sensitive data or assume immediate connected-client
revocation solely from this database policy.

Reference: https://supabase.com/docs/guides/realtime/authorization
