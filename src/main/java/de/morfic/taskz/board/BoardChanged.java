package de.morfic.taskz.board;

/** Something visible on the board changed; open views of it load it again (see {@link BoardEvents}). */
public record BoardChanged(int boardId) {
}
