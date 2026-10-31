package com.batchauto.template;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.batchauto.config.JobConfig;
import com.batchauto.errors.BatchAutoException.ConfigException;

import freemarker.core.OutputFormat;
import freemarker.core.TemplateClassResolver;
import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import freemarker.template.TemplateExceptionHandler;

/** Renders a job's FreeMarker template and validates the result against the job's schema. */
public abstract class FormatHandler {

    protected final JobConfig job;
    protected final Path jobDir;
    private final Template template;

    protected FormatHandler(JobConfig job, Path jobDir, OutputFormat outputFormat) {
        this.job = job;
        this.jobDir = jobDir;
        Configuration cfg = new Configuration(Configuration.VERSION_2_3_33);
        try {
            cfg.setDirectoryForTemplateLoading(jobDir.toFile());
        } catch (IOException e) {
            throw new ConfigException(job.jobId() + ": cannot read templates", e);
        }
        cfg.setDefaultEncoding("UTF-8");
        cfg.setOutputFormat(outputFormat);
        cfg.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
        cfg.setLogTemplateExceptions(false);
        cfg.setWrapUncheckedExceptions(true);
        cfg.setNumberFormat("computer");
        cfg.setNewBuiltinClassResolver(TemplateClassResolver.ALLOWS_NOTHING_RESOLVER); // no ?new — templates can't run code
        cfg.setAPIBuiltinEnabled(false);
        try {
            this.template = cfg.getTemplate(job.file().template());
        } catch (IOException e) {
            throw new ConfigException(job.jobId() + ": template error: " + e.getMessage(), e);
        }
    }

    public abstract String ext();

    /** Converts one record's typed values into what the template expects (strings for XML, JSON literals for JSON). */
    protected abstract Map<String, Object> encodeRecord(Map<String, Object> record);

    protected abstract Object encodeContext(Object value);

    public byte[] render(List<Map<String, Object>> records, Map<String, Object> context) {
        Map<String, Object> model = new HashMap<>();
        context.forEach((k, v) -> model.put(k, encodeContext(v)));
        model.put("records", records.stream().map(this::encodeRecord).toList());
        StringWriter out = new StringWriter();
        try {
            template.process(model, out);
        } catch (TemplateException | IOException e) {
            throw new ConfigException(job.jobId() + ": template render failed: " + e.getMessage(), e);
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    public abstract FileValidationResult validate(byte[] content);

    public static FormatHandler forJob(JobConfig job, Path jobDir) {
        return switch (job.file().format()) {
            case "xml" -> new XmlHandler(job, jobDir);
            case "json" -> new JsonHandler(job, jobDir);
            default -> throw new ConfigException("unsupported format " + job.file().format());
        };
    }
}
