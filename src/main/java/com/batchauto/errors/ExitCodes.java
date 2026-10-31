package com.batchauto.errors;

/** Pipeline exit codes. Infrastructure failures never masquerade as application defects. */
public final class ExitCodes {
    public static final int PASS = 0;
    public static final int TEST_FAILURE = 1;
    public static final int CONFIG_ERROR = 2;
    public static final int INFRA_ERROR = 3;
    public static final int TIMEOUT = 4;
    public static final int DATA_SHORTAGE = 5;

    private ExitCodes() { }
}
