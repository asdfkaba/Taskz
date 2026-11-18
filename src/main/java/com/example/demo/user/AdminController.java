package com.example.demo.user;

import com.example.demo.user.AccountController.DeleteAccountRequest;
import com.example.demo.user.AdminService.UserDto;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// Only with a login: the token filter keeps API tokens out of paths it doesn't list.
@RestController
@RequestMapping("/admin/users")
public class AdminController {

    private final AdminService adminService;

    public AdminController(AdminService adminService) {
        this.adminService = adminService;
    }

    @GetMapping
    public List<UserDto> users() {
        return adminService.users(currentUserEmail());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable("id") int id, @RequestBody DeleteAccountRequest request) {
        adminService.deleteUser(currentUserEmail(), id, request.email());
    }

    private static String currentUserEmail() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }
}
