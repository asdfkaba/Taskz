-- What a share link shows besides the board itself; off for existing links, as before.
ALTER TABLE board_share ADD COLUMN show_attachments boolean NOT NULL DEFAULT false;
ALTER TABLE board_share ADD COLUMN show_notes boolean NOT NULL DEFAULT false;
ALTER TABLE board_share ADD COLUMN show_history boolean NOT NULL DEFAULT false;
-- Leaves out who wrote, uploaded or changed something in what the link shows.
ALTER TABLE board_share ADD COLUMN hide_names boolean NOT NULL DEFAULT false;
