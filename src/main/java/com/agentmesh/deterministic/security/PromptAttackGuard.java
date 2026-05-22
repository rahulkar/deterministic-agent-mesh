package com.agentmesh.deterministic.security;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PromptAttackGuard {
    private static final List<Pattern> ATTACK_PATTERNS = List.of(
        Pattern.compile("\\bignore\\s+(all|any|the|previous|above|prior)\\s+(instructions?|rules?|prompts?)\\b"),
        Pattern.compile("\\b(disregard|bypass|override)\\s+(safety|policy|instructions?|guardrails?)\\b"),
        Pattern.compile("\\b(reveal|show|print|dump|exfiltrate)\\b.*\\b(system\\s+prompt|developer\\s+message|api\\s*key|secret|policy)\\b"),
        Pattern.compile("\\b(system|developer)\\s*:\\s*"),
        Pattern.compile("<\\s*/?\\s*(system|developer|instruction)\\s*>"),
        Pattern.compile("\\bpretend\\s+to\\s+be\\s+(system|developer|admin)\\b"),
        Pattern.compile("\\bact\\s+as\\s+(system|developer|admin|dan)\\b"),
        Pattern.compile("\\bjailbreak\\b|\\bdan\\s+mode\\b|\\bprompt\\s+injection\\b")
    );
    private static final Pattern BASE64_TOKEN = Pattern.compile("\\b[A-Za-z0-9+/]{16,}={0,2}\\b");

    public GuardDecision inspect(String userPrompt) {
        if (userPrompt == null || userPrompt.isBlank()) {
            return GuardDecision.block("EMPTY_PROMPT", "");
        }

        String canonical = canonicalize(userPrompt);
        for (Pattern pattern : ATTACK_PATTERNS) {
            if (pattern.matcher(canonical).find()) {
                return GuardDecision.block("PROMPT_ATTACK:" + pattern.pattern(), canonical);
            }
        }
        return GuardDecision.allow(canonical);
    }

    public String canonicalize(String input) {
        String value = input;
        for (int i = 0; i < 2; i++) {
            String decoded = URLDecoder.decode(value, StandardCharsets.UTF_8);
            if (decoded.equals(value)) {
                break;
            }
            value = decoded;
        }

        StringBuilder expanded = new StringBuilder(value);
        Matcher matcher = BASE64_TOKEN.matcher(value);
        while (matcher.find()) {
            decodeBase64(matcher.group()).ifPresent(decoded -> expanded.append(' ').append(decoded));
        }

        return expanded.toString()
            .replaceAll("\\p{Cntrl}", " ")
            .replaceAll("\\s+", " ")
            .trim()
            .toLowerCase(Locale.ROOT)
            .replaceAll("\\basprin\\b", "aspirin");
    }

    private java.util.Optional<String> decodeBase64(String token) {
        try {
            byte[] bytes = Base64.getDecoder().decode(token);
            String decoded = new String(bytes, StandardCharsets.UTF_8);
            if (decoded.chars().allMatch(ch -> ch == 9 || ch == 10 || ch == 13 || (ch >= 32 && ch < 127))) {
                return java.util.Optional.of(decoded);
            }
        } catch (IllegalArgumentException ignored) {
            // Not actually base64.
        }
        return java.util.Optional.empty();
    }
}
