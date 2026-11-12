-- When the hourly job last looked at a rule (see BoardRule.checkedAt), so that a notification for
-- "N days in the column" goes out once and not every hour.
ALTER TABLE board_rule ADD COLUMN checked_at timestamp(6) with time zone;
