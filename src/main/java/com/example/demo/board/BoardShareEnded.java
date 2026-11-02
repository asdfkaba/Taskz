package com.example.demo.board;

/** The board's share link stopped working (revoked, replaced, board deleted); its viewers are cut off. */
public record BoardShareEnded(int boardId) {
}
