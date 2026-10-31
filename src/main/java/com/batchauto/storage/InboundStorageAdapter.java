package com.batchauto.storage;

import java.nio.file.Path;
import java.util.Map;

/** Provider-neutral inbound delivery. Core code only ever sees this interface. */
public interface InboundStorageAdapter {

    String provider();

    /** Upload without overwriting; an existing object is a StorageException, never silently replaced. */
    UploadResult uploadFile(Path file, String destination, Map<String, String> metadata);

    /** True if the object is visible at destination and (when given) its SHA-256 matches. */
    boolean verifyFile(String destination, String expectedSha256);

    /** Size, timestamps and custom metadata, or null if absent. */
    Map<String, Object> getFileMetadata(String destination);

    /** Removes a file this framework uploaded (cleanup of unprocessed test files only). */
    boolean deleteTestFile(String destination);

    default String describe(String destination) { return destination; }
}
