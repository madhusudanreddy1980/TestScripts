package com.batchauto.errors;

/**
 * Framework error hierarchy. Every error carries the pipeline exit code it maps to,
 * so infrastructure problems stay distinguishable from genuine application defects.
 */
public class BatchAutoException extends RuntimeException {

    private final int exitCode;
    private final String category;

    public BatchAutoException(String message, int exitCode, String category, Throwable cause) {
        super(message, cause);
        this.exitCode = exitCode;
        this.category = category;
    }

    public int exitCode() { return exitCode; }

    public String category() { return category; }

    public static class ConfigException extends BatchAutoException {
        public ConfigException(String m) { this(m, (Throwable) null); }
        public ConfigException(String m, Throwable c) { super(m, ExitCodes.CONFIG_ERROR, "CONFIG_ERROR", c); }
        protected ConfigException(String m, String category) { super(m, ExitCodes.CONFIG_ERROR, category, null); }
    }

    public static class UnsafeSqlException extends ConfigException {
        public UnsafeSqlException(String m) { super(m, "UNSAFE_SQL"); }
    }

    public static class SubmissionBlockedException extends ConfigException {
        public SubmissionBlockedException(String m) { super(m, "SUBMISSION_BLOCKED"); }
    }

    public static class DataShortageException extends BatchAutoException {
        public DataShortageException(String m) { super(m, ExitCodes.DATA_SHORTAGE, "DATA_SHORTAGE", null); }
    }

    public static class InfraException extends BatchAutoException {
        public InfraException(String m, Throwable c) { super(m, ExitCodes.INFRA_ERROR, "INFRA_ERROR", c); }
        protected InfraException(String m, String category, Throwable c) { super(m, ExitCodes.INFRA_ERROR, category, c); }
    }

    public static class StorageException extends InfraException {
        public StorageException(String m) { this(m, (Throwable) null); }
        public StorageException(String m, Throwable c) { super(m, "STORAGE_ERROR", c); }
    }

    public static class TransientStorageException extends InfraException {
        public TransientStorageException(String m, Throwable c) { super(m, "STORAGE_TRANSIENT", c); }
    }

    public static class QueryTimeoutException extends InfraException {
        public QueryTimeoutException(String m, Throwable c) { super(m, "QUERY_TIMEOUT", c); }
    }
}
