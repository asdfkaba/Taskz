-- Since when a task is in its column, for the ARCHIVE_AFTER rule (see Task.columnEntered).
-- Existing tasks count from their last change, as their moves were not timed.
ALTER TABLE task ADD COLUMN column_entered timestamp(6) with time zone NOT NULL DEFAULT now();
UPDATE task SET column_entered = coalesce(edited, created, column_entered);
