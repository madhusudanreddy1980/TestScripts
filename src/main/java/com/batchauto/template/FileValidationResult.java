package com.batchauto.template;

import java.util.List;

public record FileValidationResult(boolean valid, List<String> errors, Integer recordCount, String schema) { }
