-- Archived tasks keep their column but leave the board, the table, "What's due" and share links
-- (see Task.archivedAt). NULL: not archived.
ALTER TABLE task ADD COLUMN archived_at timestamp(6) with time zone;
