-- Logins find the account by email, so two accounts with the same address would lock that user
-- out. Fails if duplicates already exist; merge or delete them first:
--   select email, count(*) from "user" group by email having count(*) > 1;
-- Several accounts without an email stay possible (NULLs don't clash), new ones can't be created.
ALTER TABLE "user" ADD CONSTRAINT user_email_unique UNIQUE (email);
