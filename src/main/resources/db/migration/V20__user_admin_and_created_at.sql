-- Admins see /admin (all users, deleting accounts). Nobody is one by default; set it by hand:
--   UPDATE "user" SET admin = true WHERE email = '...';
ALTER TABLE "user" ADD COLUMN admin boolean NOT NULL DEFAULT false;
-- When the account was created. Accounts from before this migration have none (null).
ALTER TABLE "user" ADD COLUMN created_at timestamp(6) with time zone;
