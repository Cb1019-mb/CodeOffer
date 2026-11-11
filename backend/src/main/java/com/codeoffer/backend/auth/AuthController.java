package com.codeoffer.backend.auth;

import com.codeoffer.backend.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final CaptchaService captcha;
    private final AuthService authService;
    private final AuthRateLimiter rateLimiter;
    private final CurrentUser currentUser;

    public AuthController(
            CaptchaService captcha,
            AuthService authService,
            AuthRateLimiter rateLimiter,
            CurrentUser currentUser) {
        this.captcha = captcha;
        this.authService = authService;
        this.rateLimiter = rateLimiter;
        this.currentUser = currentUser;
    }

    @GetMapping("/captcha")
    public ResponseEntity<byte[]> captcha(HttpServletRequest request) {
        rateLimit(request);
        byte[] png = captcha.generate(request.getSession(true));
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .cacheControl(CacheControl.noStore())
                .body(png);
    }

    @PostMapping("/register")
    public AuthService.AuthView register(
            @RequestBody AuthService.AuthRequest req, HttpServletRequest request, HttpServletResponse response) {
        rateLimit(request);
        return authService.register(req, request, response);
    }

    @PostMapping("/login")
    public AuthService.AuthView login(
            @RequestBody AuthService.AuthRequest req, HttpServletRequest request, HttpServletResponse response) {
        rateLimit(request);
        return authService.login(req, request, response);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest request, HttpServletResponse response) {
        authService.logout(request, response);
    }

    @GetMapping("/me")
    public AuthService.AuthView me() {
        return authService.me(currentUser.require());
    }

    private void rateLimit(HttpServletRequest request) {
        if (!rateLimiter.tryAcquire(request.getRemoteAddr())) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "请求过于频繁,请稍后再试");
        }
    }
}