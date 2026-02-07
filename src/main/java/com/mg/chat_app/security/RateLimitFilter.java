package com.mg.chat_app.security;

import java.io.IOException;
import java.time.Duration;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public class RateLimitFilter implements Filter {

    private final ProxyManager<String> proxyManager;

    private static final BucketConfiguration LOGIN_CONFIG = BucketConfiguration.builder()
            .addLimit(Bandwidth.simple(5, Duration.ofMinutes(1)))
            .build();

    private static final BucketConfiguration REGISTER_CONFIG = BucketConfiguration.builder()
            .addLimit(Bandwidth.simple(3, Duration.ofMinutes(10)))
            .build();

    public RateLimitFilter(ProxyManager<String> proxyManager) {
        this.proxyManager = proxyManager;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        // Pass through CORS preflight
        if ("OPTIONS".equalsIgnoreCase(httpRequest.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        String clientIp = extractClientIp(httpRequest);
        String path = httpRequest.getRequestURI();
        boolean isLogin = path.endsWith("/login");

        String bucketKey = "rate_limit:" + (isLogin ? "login:" : "register:") + clientIp;
        BucketConfiguration config = isLogin ? LOGIN_CONFIG : REGISTER_CONFIG;

        Bucket bucket = proxyManager.builder().build(bucketKey, () -> config);
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);

        if (probe.isConsumed()) {
            httpResponse.setHeader("X-Rate-Limit-Remaining", String.valueOf(probe.getRemainingTokens()));
            chain.doFilter(request, response);
        } else {
            long waitSeconds = Duration.ofNanos(probe.getNanosToWaitForRefill()).toSeconds() + 1;
            httpResponse.setStatus(429);
            httpResponse.setHeader("Retry-After", String.valueOf(waitSeconds));
            httpResponse.setContentType("application/json");
            httpResponse.getWriter().write("{\"error\":\"Too many requests. Retry after " + waitSeconds + " seconds.\"}");
        }
    }

    private String extractClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
