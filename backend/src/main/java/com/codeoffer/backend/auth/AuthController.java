package com.codeoffer.backend.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final CaptchaService captcha;

    public AuthController(CaptchaService captcha) {
        this.captcha = captcha;
    }

    @GetMapping("/captcha")
    public ResponseEntity<byte[]> captcha(HttpServletRequest request) {
        byte[] png = captcha.generate(request.getSession(true));
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .cacheControl(CacheControl.noStore())
                .body(png);
    }
}