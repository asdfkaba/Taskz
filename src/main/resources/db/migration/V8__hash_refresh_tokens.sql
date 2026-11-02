-- Refresh tokens are stored as their SHA-256 (hex), like TokenService.hash, so a database dump
-- can't log anyone in. Existing logins keep working: their cookies hash to the new values.
UPDATE refresh_token SET token = encode(sha256(convert_to(token, 'UTF8')), 'hex');
