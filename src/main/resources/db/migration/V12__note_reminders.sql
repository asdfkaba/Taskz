-- A note can remind its author: it shows in "What's due" at remind_at (wall-clock time, like due_date).
ALTER TABLE task_note ADD COLUMN remind_at timestamp(6) without time zone;
-- The rule that added the note, so it can remove it when the task leaves the column. No foreign key:
-- deleting the rule leaves its notes as they are.
ALTER TABLE task_note ADD COLUMN rule_id integer;

-- SET_DUE_IN rules ({"days":N}) become rules that add a note with a reminder in N days.
UPDATE board_rule
SET action = 'ADD_NOTE_REMINDER', payload = '{"text":"Follow up",' || substring(payload from 2)
WHERE action = 'SET_DUE_IN';
