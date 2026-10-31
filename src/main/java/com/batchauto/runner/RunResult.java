package com.batchauto.runner;

import java.nio.file.Path;

/** result: PASS | FAIL | TIMED_OUT | NOT_FOUND | ERROR | NOT_SUBMITTED */
public record RunResult(String runId, String result, int exitCode, String state, Path reportJson, Path reportHtml, String summary) { }
