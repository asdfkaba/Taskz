-- API tokens get scopes (see ApiScope) instead of read/write, an optional expiry and the time they
-- were last used. Existing tokens keep exactly what they could do: read becomes tasks:read, write
-- the four task scopes it covered.
ALTER TABLE api_token
    ADD COLUMN scopes character varying(500),
    ADD COLUMN expires_at timestamp(6) with time zone,
    ADD COLUMN last_used_at timestamp(6) with time zone;

UPDATE api_token SET scopes = concat_ws(',',
    CASE WHEN can_read THEN 'tasks:read' END,
    CASE WHEN can_write THEN 'tasks:create,tasks:update,tasks:move,checklist:check' END);

ALTER TABLE api_token
    ALTER COLUMN scopes SET NOT NULL,
    DROP COLUMN can_read,
    DROP COLUMN can_write;
