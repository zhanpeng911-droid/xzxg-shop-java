package com.xzxg.shop.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.xzxg.shop.common.api.ApiException;
import com.xzxg.shop.common.web.RequestContext;
import com.xzxg.shop.domain.Account;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    public record LoginRequest(String username, String password) {
    }

    public record RegisterRequest(
            String username,
            String password,
            @JsonProperty("display_name") String displayName) {
    }

    @PostMapping("/login")
    public AuthService.TokenResponse login(@RequestBody(required = false) LoginRequest request) {
        if (request == null) {
            throw badRequestBody();
        }
        return authService.login(request.username(), request.password());
    }

    @PostMapping("/register")
    public ResponseEntity<AuthService.TokenResponse> register(
            @RequestBody(required = false) RegisterRequest request) {
        if (request == null) {
            throw badRequestBody();
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(authService.register(request.username(), request.password(), request.displayName()));
    }

    @PostMapping("/logout")
    public Map<String, Boolean> logout(HttpServletRequest request) {
        authService.logout(AuthFilter.bearerToken(request));
        return Map.of("ok", true);
    }

    @GetMapping("/me")
    public Account me(HttpServletRequest request) {
        Account account = RequestContext.account(request);
        if (account == null) {
            throw ApiException.unauthorized();
        }
        return account;
    }

    private static ApiException badRequestBody() {
        return new ApiException(HttpStatus.BAD_REQUEST, "bad_request", "请求 JSON 不合法");
    }
}
