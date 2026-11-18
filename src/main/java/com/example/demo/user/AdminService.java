package com.example.demo.user;

import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

/** What admins (User.admin) can do: see all users and delete their accounts. */
@Service
public class AdminService {

    private final UserRepository userRepository;
    private final AccountService accountService;

    public AdminService(UserRepository userRepository, AccountService accountService) {
        this.userRepository = userRepository;
        this.accountService = accountService;
    }

    // createdAt: null for accounts from before it was recorded.
    public record UserDto(Integer id, String name, String email, boolean admin, Instant createdAt) {
    }

    @Transactional(readOnly = true)
    public List<UserDto> users(String adminEmail) {
        requireAdmin(adminEmail);
        return userRepository.findAll(Sort.by("id")).stream()
                .map(u -> new UserDto(u.getId(), u.getName(), u.getEmail(), u.isAdmin(), u.getCreatedAt()))
                .toList();
    }

    /** Deletes the user's account like they would themselves; confirmEmail must be their email. */
    @Transactional
    public void deleteUser(String adminEmail, int id, String confirmEmail) {
        User admin = requireAdmin(adminEmail);
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        // The own account goes through the settings, which also log this browser out.
        if (user.getId().equals(admin.getId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Delete your own account in the settings");
        }
        accountService.delete(user.getEmail(), confirmEmail);
    }

    // Checked against the database on every request, so taking admin away works at once.
    private User requireAdmin(String email) {
        return userRepository.findByEmail(email).filter(User::isAdmin)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Admins only"));
    }
}
