package com.codeoffer.backend.auth;

import jakarta.servlet.http.HttpSession;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.SecureRandom;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Service;

/** 图形验证码:Java2D 自绘,答案存 Session,一次性、5 分钟过期。 */
@Service
public class CaptchaService {

    public static final String CODE_ATTR = "CAPTCHA_CODE";
    public static final String EXPIRES_ATTR = "CAPTCHA_EXPIRES_AT";
    private static final long TTL_MILLIS = 5 * 60 * 1000L;
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    public byte[] generate(HttpSession session) {
        StringBuilder code = new StringBuilder(4);
        for (int i = 0; i < 4; i++) {
            code.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        session.setAttribute(CODE_ATTR, code.toString());
        session.setAttribute(EXPIRES_ATTR, System.currentTimeMillis() + TTL_MILLIS);
        return render(code.toString());
    }

    /** 校验并立即消费(无论成败);忽略大小写;过期视为失败。 */
    public boolean validate(HttpSession session, String input) {
        Object code = session.getAttribute(CODE_ATTR);
        Object expires = session.getAttribute(EXPIRES_ATTR);
        session.removeAttribute(CODE_ATTR);
        session.removeAttribute(EXPIRES_ATTR);
        if (!(code instanceof String expected) || !(expires instanceof Long exp)) {
            return false;
        }
        if (System.currentTimeMillis() > exp || input == null) {
            return false;
        }
        return expected.equalsIgnoreCase(input.trim());
    }

    private byte[] render(String code) {
        BufferedImage img = new BufferedImage(120, 40, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(245, 246, 250));
        g.fillRect(0, 0, 120, 40);
        g.setStroke(new BasicStroke(1.2f));
        for (int i = 0; i < 5; i++) {
            g.setColor(new Color(RANDOM.nextInt(200), RANDOM.nextInt(200), RANDOM.nextInt(200)));
            g.drawLine(RANDOM.nextInt(120), RANDOM.nextInt(40), RANDOM.nextInt(120), RANDOM.nextInt(40));
        }
        g.setFont(new Font("SansSerif", Font.BOLD, 26));
        for (int i = 0; i < code.length(); i++) {
            g.setColor(new Color(RANDOM.nextInt(120), RANDOM.nextInt(120), RANDOM.nextInt(120)));
            double theta = (RANDOM.nextDouble() - 0.5) * 0.5;
            g.rotate(theta, 20 + i * 26, 28);
            g.drawString(String.valueOf(code.charAt(i)), 12 + i * 26, 30);
            g.rotate(-theta, 20 + i * 26, 28);
        }
        g.dispose();
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("验证码渲染失败", e);
        }
    }
}