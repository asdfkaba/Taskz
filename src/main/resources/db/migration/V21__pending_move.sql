-- A move by hand whose rules haven't run yet (see RuleEngine.movedByHand): they wait a minute, so an
-- accidental move can be undone, or corrected by moving on, without leaving traces. One row per task,
-- gone once the rules ran. from_*: where the task came from, to put it back on undo.
-- event_id: the move's history entry, rewritten when the task moves on. No foreign keys there: the
-- column may be deleted meanwhile, and history entries outlive their task.
CREATE TABLE pending_move (
    task_id integer PRIMARY KEY REFERENCES task (id) ON DELETE CASCADE,
    from_column_id integer NOT NULL,
    from_position integer NOT NULL,
    from_entered timestamp(6) with time zone NOT NULL,
    user_id integer REFERENCES "user" (id) ON DELETE SET NULL,
    time_zone character varying(64),
    event_id integer,
    due_at timestamp(6) with time zone NOT NULL
);
CREATE INDEX pending_move_due_at ON pending_move (due_at);
