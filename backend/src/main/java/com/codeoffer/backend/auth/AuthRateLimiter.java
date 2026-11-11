package com.codeoffer.backend.auth;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 认证端点按 IP 滑窗限流(单实例内存版;多实例需换 Redis,见部署清单)。 */
@Component
public class AuthRateLimiter {

    private final int limitPerMinute;
    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    public AuthRateLimiter(@Value("${auth.rate-limit.per-minute:30}") int limitPerMinute) {
        this.limitPerMinute = limitPerMinute;
    }

    public boolean tryAcquire(String ip) {
        long now = System.currentTimeMillis();
        Deque<Long> window = hits.computeIfAbsent(ip, k -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && now - window.peekFirst() > 60_000) {
                window.pollFirst();
            }
            if (window.size() >= limitPerMinute) {
                return false;
            }
            window.addLast(now);
            return true;
        }
    }
}