package com.batchauto.template;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.xml.XMLConstants;
import javax.xml.namespace.NamespaceContext;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;

import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXParseException;

import com.batchauto.config.JobConfig;
import com.batchauto.errors.BatchAutoException.ConfigException;

import freemarker.core.XMLOutputFormat;

/** XML: FreeMarker XML output format (auto-escaping), hardened parser, XSD validation. */
public final class XmlHandler extends FormatHandler {

    public XmlHandler(JobConfig job, Path jobDir) {
        super(job, jobDir, XMLOutputFormat.INSTANCE);
    }

    @Override
    public String ext() { return "xml"; }

    @Override
    protected Map<String, Object> encodeRecord(Map<String, Object> record) {
        Map<String, Object> out = new HashMap<>();
        record.forEach((k, v) -> {
            if (v != null) {
                out.put(k, v instanceof BigDecimal d ? d.toPlainString() : String.valueOf(v));
            }
        });
        return out;
    }

    @Override
    protected Object encodeContext(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Schema schema() {
        try {
            SchemaFactory sf = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
            sf.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            sf.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "file");
            return sf.newSchema(jobDir.resolve(job.file().schemaFile()).toFile());
        } catch (Exception e) {
            throw new ConfigException(job.jobId() + ": invalid XSD: " + e.getMessage(), e);
        }
    }

    static DocumentBuilder secureBuilder() throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        f.setXIncludeAware(false);
        f.setExpandEntityReferences(false);
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        DocumentBuilder b = f.newDocumentBuilder();
        b.setErrorHandler(null);
        return b;
    }

    @Override
    public FileValidationResult validate(byte[] content) {
        Schema schema = schema();
        String schemaName = job.file().schemaFile();
        Document doc;
        try {
            doc = secureBuilder().parse(new ByteArrayInputStream(content));
        } catch (Exception e) {
            return new FileValidationResult(false, List.of("malformed XML: " + e.getMessage()), null, schemaName);
        }
        Integer count = null;
        if (job.file().recordXpath() != null) {
            try {
                XPath xp = XPathFactory.newInstance().newXPath();
                xp.setNamespaceContext(namespaces(job.file().namespaces()));
                count = ((NodeList) xp.evaluate(job.file().recordXpath(), doc, XPathConstants.NODESET)).getLength();
            } catch (Exception e) {
                throw new ConfigException(job.jobId() + ": bad record_xpath: " + e.getMessage(), e);
            }
        }
        List<String> errors = new ArrayList<>();
        try {
            Validator v = schema.newValidator();
            v.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            v.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            v.setErrorHandler(new ErrorHandler() {
                @Override public void warning(SAXParseException e) { }
                @Override public void error(SAXParseException e) { errors.add("line " + e.getLineNumber() + ": " + e.getMessage()); }
                @Override public void fatalError(SAXParseException e) { errors.add("line " + e.getLineNumber() + ": " + e.getMessage()); }
            });
            v.validate(new StreamSource(new ByteArrayInputStream(content)));
        } catch (SAXParseException e) {
            // already recorded by the error handler
        } catch (Exception e) {
            errors.add("validation error: " + e.getMessage());
        }
        return new FileValidationResult(errors.isEmpty(), errors, count, schemaName);
    }

    private static NamespaceContext namespaces(Map<String, String> ns) {
        return new NamespaceContext() {
            @Override public String getNamespaceURI(String prefix) { return ns.getOrDefault(prefix, XMLConstants.NULL_NS_URI); }
            @Override public String getPrefix(String uri) {
                return ns.entrySet().stream().filter(e -> e.getValue().equals(uri)).map(Map.Entry::getKey).findFirst().orElse(null);
            }
            @Override public Iterator<String> getPrefixes(String uri) {
                return ns.entrySet().stream().filter(e -> e.getValue().equals(uri)).map(Map.Entry::getKey).iterator();
            }
        };
    }
}
