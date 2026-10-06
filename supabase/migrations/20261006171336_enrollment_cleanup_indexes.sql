-- Task 13: measured selective FK cleanup paths scanned all enrollment tokens.
-- Add only the two missing lookup indexes; existing child/device indexes suffice.
create index device_enrollment_tokens_family_idx
  on private.device_enrollment_tokens (family_id);
create index device_enrollment_tokens_issuer_idx
  on private.device_enrollment_tokens (issued_by_user_id);