package com.example.demo.board;

/** Something visible on the board changed; open views of it load it again (see {@link BoardEvents}). */
public record BoardChanged(int boardId) {
}
