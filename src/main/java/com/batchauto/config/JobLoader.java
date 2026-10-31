package com.batchauto.config;

import java.nio.file.Files;
import java.nio.file.Path;

import com.batchauto.errors.BatchAutoException.ConfigException;
import com.batchauto.expr.Expressions;

/** Loads and cross-validates one job directory. */
public final class JobLoader {

    public record LoadedJob(JobConfig job, ValidationRules rules, Path dir) { }

    private JobLoader() { }

    public static LoadedJob load(Path jobDir) {
        String where = jobDir.getFileName().toString();
        try {
            JobConfig job = Yaml.read(jobDir.resolve("job.yaml"), JobConfig.class);
            ValidationRules rules = Yaml.read(jobDir.resolve("validation-rules.yaml"), ValidationRules.class);
            job.validate();
            rules.checkAgainst(job);
            for (String rel : new String[] {job.file().template(), job.file().schemaFile(), job.eligibility().queryFile()}) {
                Path p = jobDir.resolve(rel).normalize();
                Check.that(p.startsWith(jobDir.normalize()), "file reference escapes the job folder: " + rel);
                Check.that(Files.isRegularFile(p), "referenced file '" + rel + "' not found");
            }
            return new LoadedJob(job, rules, jobDir);
        } catch (ConfigException e) {
            throw new ConfigException(where + ": " + e.getMessage(), e);
        } catch (Expressions.ExpressionException e) {
            throw new ConfigException(where + ": " + e.getMessage(), e);
        }
    }
}
