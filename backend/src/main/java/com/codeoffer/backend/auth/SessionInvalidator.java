package com.codeoffer.backend.auth;

import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.stereotype.Component;

/** 按用户名失效会话(expireNow 后,ConcurrentSessionFilter 在下次请求时拦截并返回 401)。 */
@Component
public class SessionInvalidator {

    private final SessionRegistry registry;

    public SessionInvalidator(SessionRegistry registry) {
        this.registry = registry;
    }

    public void invalidateAll(String username) {
        for (SessionInformation si : registry.getAllSessions(username, false)) {
            si.expireNow();
        }
    }

    public void invalidateOthers(String username, String currentSessionId) {
        for (SessionInformation si : registry.getAllSessions(username, false)) {
            if (!si.getSessionId().equals(currentSessionId)) {
                si.expireNow();
            }
        }
    }
}