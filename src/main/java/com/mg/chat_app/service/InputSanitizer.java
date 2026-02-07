package com.mg.chat_app.service;

import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.PolicyFactory;
import org.springframework.stereotype.Component;

@Component
public class InputSanitizer {

    private final PolicyFactory policy = new HtmlPolicyBuilder().toFactory();

    public String sanitize(String input) {
        if (input == null) return null;
        String sanitized = policy.sanitize(input);
        // Decode common HTML entities back to plain characters
        return sanitized
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
    }
}
