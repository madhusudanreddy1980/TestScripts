package com.batchauto.storage;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.Map;

import com.batchauto.config.CloudProfile;
import com.batchauto.errors.BatchAutoException;
import com.batchauto.errors.BatchAutoException.ConfigException;

/**
 * Picks the adapter from the environment's cloud profile. Cloud adapters are loaded by class
 * name so the core build does not need the GCP / AWS / Azure SDKs (build with -Pcloud).
 */
public final class StorageRegistry {

    public static final Map<String, String> CLOUD_ADAPTERS = Map.of(
            "gcs", "com.batchauto.storage.cloud.GcsAdapter",
            "s3", "com.batchauto.storage.cloud.S3Adapter",
            "azure_blob", "com.batchauto.storage.cloud.AzureBlobAdapter");

    private StorageRegistry() { }

    public static String adapterClassName(CloudProfile profile) {
        return "local".equals(profile.provider()) ? LocalFilesystemAdapter.class.getName()
                : CLOUD_ADAPTERS.get(profile.provider());
    }

    public static InboundStorageAdapter create(CloudProfile profile) {
        profile.validate();
        if ("local".equals(profile.provider())) {
            return new LocalFilesystemAdapter(Path.of(profile.root()));
        }
        String cls = CLOUD_ADAPTERS.get(profile.provider());
        try {
            return (InboundStorageAdapter) Class.forName(cls).getConstructor(CloudProfile.class).newInstance(profile);
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            throw new ConfigException("the " + profile.provider() + " adapter is not in this build; rebuild with: mvn -Pcloud package");
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof BatchAutoException b) {
                throw b;
            }
            throw new ConfigException("cannot create " + profile.provider() + " adapter: " + e.getCause(), e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new ConfigException("cannot create " + profile.provider() + " adapter: " + e, e);
        }
    }
}
