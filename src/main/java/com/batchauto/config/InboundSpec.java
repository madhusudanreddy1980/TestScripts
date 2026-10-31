package com.batchauto.config;

import java.util.Arrays;

public record InboundSpec(String prefix) {
    void validate() {
        Check.notBlank(prefix, "inbound.prefix");
        Check.that(!prefix.startsWith("/") && Arrays.stream(prefix.split("/")).noneMatch(".."::equals),
                "inbound.prefix must be relative and must not contain '..'");
    }
}
