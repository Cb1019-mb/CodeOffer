package com.codeoffer.backend.config;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.stereotype.Component;

/**
 * SPA 路由 fallback:React Router 的客户端路由路径(如 /projects/1)刷新时,
 * 后端没有对应映射,统一转发到 index.html 交给前端路由处理。
 * 仅拦截 GET 且非 /api、无文件扩展名的路径;静态资源与 API 不受影响。
 */
@Component
public class SpaFallbackFilter implements Filter {

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        String uri = request.getRequestURI();
        boolean isGet = "GET".equalsIgnoreCase(request.getMethod());
        boolean isApi = uri.startsWith("/api");
        boolean hasExtension = uri.contains(".");
        if (isGet && !isApi && !hasExtension) {
            request.getRequestDispatcher("/index.html").forward(req, res);
            return;
        }
        chain.doFilter(req, res);
    }
}
