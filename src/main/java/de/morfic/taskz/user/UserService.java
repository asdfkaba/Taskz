package de.morfic.taskz.user;

import de.morfic.taskz.board.BoardService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final BoardService boardService;

    public UserService(UserRepository userRepository, BoardService boardService) {
        this.userRepository = userRepository;
        this.boardService = boardService;
    }

    // A new user always starts with a default board, so the frontend never sees a user without one.
    @Transactional
    public User register(String email, String name) {
        User user = new User();
        user.setEmail(email);
        user.setName(name);
        userRepository.save(user);
        boardService.createDefaultBoard(user);
        return user;
    }
}
