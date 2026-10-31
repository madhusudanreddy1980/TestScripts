package com.batchauto.expr;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.jexl3.JexlBuilder;
import org.apache.commons.jexl3.JexlEngine;
import org.apache.commons.jexl3.JexlException;
import org.apache.commons.jexl3.JexlExpression;
import org.apache.commons.jexl3.JexlFeatures;
import org.apache.commons.jexl3.MapContext;
import org.apache.commons.jexl3.introspection.JexlPermissions;
import org.apache.commons.jexl3.introspection.JexlSandbox;

/**
 * Sandboxed expressions for job metadata (eligibility re-checks, expected-rejection rules,
 * target-mapping cases, count equations). Apache Commons JEXL with:
 * <ul>
 *   <li>a deny-everything sandbox — no method calls or property access on any Java class,</li>
 *   <li>no loops, lambdas, object creation, assignments, annotations or pragmas,</li>
 *   <li>strict mode — an unknown variable or a null operand is an error, never a silent false.</li>
 * </ul>
 * Syntax: {@code ==, !=, <, <=, >, >=, +, -, *, /, &&, ||, !, null},
 * membership {@code currency =~ ['INR', 'USD']}.
 */
public final class Expressions {

    public static class ExpressionException extends RuntimeException {
        public ExpressionException(String m, Throwable c) { super(m, c); }
    }

    private static final JexlEngine JEXL = new JexlBuilder()
            .permissions(JexlPermissions.RESTRICTED)
            .sandbox(new JexlSandbox(false))
            .features(new JexlFeatures()
                    .loops(false)
                    .lambda(false)
                    .newInstance(false)
                    .sideEffect(false)
                    .sideEffectGlobal(false)
                    .annotation(false)
                    .pragma(false))
            .strict(true)
            .silent(false)
            .safe(false)
            .cache(512)
            .create();

    private Expressions() { }

    public static Object evaluate(String expression, Map<String, ?> names) {
        JexlExpression e;
        try {
            e = JEXL.createExpression(expression);
        } catch (JexlException ex) {
            throw new ExpressionException("invalid expression '" + expression + "': " + ex.getMessage(), ex);
        }
        MapContext ctx = new MapContext();
        names.forEach(ctx::set);
        try {
            return e.evaluate(ctx);
        } catch (JexlException | ArithmeticException | ClassCastException ex) {
            throw new ExpressionException("cannot evaluate '" + expression + "': " + ex.getMessage(), ex);
        }
    }

    public static boolean test(String expression, Map<String, ?> names) {
        Object v = evaluate(expression, names);
        if (v instanceof Boolean b) {
            return b;
        }
        throw new ExpressionException("expression '" + expression + "' did not return true/false but " + v, null);
    }

    /** Top-level variable names an expression refers to (for configuration checks). */
    public static Set<String> referencedNames(String expression) {
        try {
            Set<List<String>> vars = JEXL.createScript(expression).getVariables();
            Set<String> out = new HashSet<>();
            for (List<String> v : vars) {
                if (!v.isEmpty()) {
                    out.add(v.get(0));
                }
            }
            return out;
        } catch (JexlException ex) {
            throw new ExpressionException("invalid expression '" + expression + "': " + ex.getMessage(), ex);
        }
    }
}
