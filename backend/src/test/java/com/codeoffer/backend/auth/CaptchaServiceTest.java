package com.codeoffer.backend.auth;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaptchaServiceTest {

    private final CaptchaService service = new CaptchaService();

    @Test
    void generate_storesCodeAndReturnsPng() {
        MockHttpSession session = new MockHttpSession();
        byte[] png = service.generate(session);
        assertNotNull(png);
        assertTrue(png.length > 100);
        assertNotNull(session.getAttribute("CAPTCHA_CODE"));
    }

    @Test
    void validate_caseInsensitiveAndOneShot() {
        MockHttpSession session = new MockHttpSession();
        service.generate(session);
        String code = (String) session.getAttribute("CAPTCHA_CODE");
        assertTrue(service.validate(session, code.toLowerCase()));
        assertFalse(service.validate(session, code)); // 一次性:第二次必败
    }

    @Test
    void validate_wrongCode_failsAndConsumes() {
        MockHttpSession session = new MockHttpSession();
        service.generate(session);
        String code = (String) session.getAttribute("CAPTCHA_CODE");
        assertFalse(service.validate(session, "!!!!"));
        assertFalse(service.validate(session, code)); // 错误尝试也消费
    }

    @Test
    void validate_expired_fails() {
        MockHttpSession session = new MockHttpSession();
        service.generate(session);
        String code = (String) session.getAttribute("CAPTCHA_CODE");
        session.setAttribute("CAPTCHA_EXPIRES_AT", System.currentTimeMillis() - 1);
        assertFalse(service.validate(session, code));
    }

    @Test
    void validate_noCaptcha_fails() {
        assertFalse(service.validate(new MockHttpSession(), "ABCD"));
    }
}