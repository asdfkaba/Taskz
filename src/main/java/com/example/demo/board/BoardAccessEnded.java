package com.example.demo.board;

/**
 * Logged-in viewers lost access to the board: one user (removed, left) or, with userId null,
 * everyone (the board was deleted). Their live streams are closed.
 */
public record BoardAccessEnded(int boardId, Integer userId) {
}
