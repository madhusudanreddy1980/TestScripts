package com.batchauto.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.batchauto.data.Coercion;
import com.batchauto.db.NamedSql;
import com.batchauto.db.SqlGuard;
import com.batchauto.errors.BatchAutoException.ConfigException;
import com.batchauto.errors.BatchAutoException.StorageException;
import com.batchauto.errors.BatchAutoException.TransientStorageException;
import com.batchauto.errors.BatchAutoException.UnsafeSqlException;
import com.batchauto.expr.Expressions;
import com.batchauto.expr.Expressions.ExpressionException;
import com.batchauto.security.Masking;
import com.batchauto.storage.StorageSupport;

class CoreUnitTest {

    @Nested
    class ExpressionsTest {
        @Test
        void arithmeticComparisonAndMembership() {
            assertThat(Expressions.test("a == b + c", Map.of("a", 5, "b", 2, "c", 3))).isTrue();
            assertThat(Expressions.test("credit_limit >= 100000", Map.of("credit_limit", new BigDecimal("100000.00")))).isTrue();
            assertThat(Expressions.test("!(currency =~ ['INR', 'USD'])", Map.of("currency", "XYZ"))).isTrue();
            assertThat(Expressions.test("!(currency =~ ['INR', 'USD'])", Map.of("currency", "INR"))).isFalse();
            assertThat(Expressions.test("email != null", java.util.Collections.singletonMap("email", null))).isFalse();
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "x.getClass()",
            "x.class.forName('java.lang.Runtime')",
            "new('java.io.File', '/etc/passwd')",
            "for (var i : [1,2]) { i }",
            "x = 5",
            "() -> 1",
        })
        void rejectsCodeExecution(String expr) {
            assertThatThrownBy(() -> Expressions.evaluate(expr, Map.of("x", "abc"))).isInstanceOf(ExpressionException.class);
        }

        @Test
        void unknownNameIsAnErrorNotFalse() {
            assertThatThrownBy(() -> Expressions.test("missing > 1", Map.of())).isInstanceOf(ExpressionException.class);
        }

        @Test
        void referencedNames() {
            assertThat(Expressions.referencedNames("records_read == records_processed + records_rejected"))
                    .containsExactlyInAnyOrder("records_read", "records_processed", "records_rejected");
        }
    }

    @Nested
    class SqlGuardTest {
        @ParameterizedTest
        @ValueSource(strings = {
            "SELECT * FROM t WHERE a = :a",
            "WITH x AS (SELECT 1 AS n) SELECT n FROM x",
            "-- comment\nSELECT updated_at, created_at FROM t",
            "SELECT 'drop table x; delete' AS s FROM t",
        })
        void allowsReads(String sql) {
            assertThat(SqlGuard.assertReadOnly(sql)).isNotBlank();
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "DELETE FROM customer",
            "UPDATE customer SET x = 1",
            "SELECT 1; DROP TABLE customer",
            "INSERT INTO t SELECT * FROM s",
            "SELECT * INTO backup FROM customer",
            "PRAGMA writable_schema = 1",
            "WITH d AS (DELETE FROM t RETURNING *) SELECT * FROM d",
        })
        void blocksWrites(String sql) {
            assertThatThrownBy(() -> SqlGuard.assertReadOnly(sql)).isInstanceOf(UnsafeSqlException.class);
        }

        @Test
        void namedParametersSkipLiteralsAndCasts() {
            NamedSql n = NamedSql.parse("SELECT ':nope', a::text FROM t WHERE x = :x AND y IN (:k0, :k1)");
            assertThat(n.names()).containsExactly("x", "k0", "k1");
            assertThat(n.jdbcSql()).isEqualTo("SELECT ':nope', a::text FROM t WHERE x = ? AND y IN (?, ?)");
        }

        @Test
        void redactsUrls() {
            assertThat(SqlGuard.redactUrl("jdbc:postgresql://db:5432/core?user=ro&password=S3cret"))
                    .doesNotContain("S3cret");
        }
    }

    @Nested
    class MaskingTest {
        @Test
        void strategies() {
            assertThat(Masking.mask("123456789012", "partial")).isEqualTo("********9012");
            assertThat(Masking.mask("priya.reddy@example.com", "email")).isEqualTo("p***@example.com");
            assertThat(Masking.mask("1991-07-30", "full")).isEqualTo("****");
            assertThat(Masking.mask(null, "full")).isNull();
        }

        @Test
        void sanitizesSecrets() {
            String s = Masking.sanitize("password=hunter2 url=postgresql://u:pw@h/db Bearer abc.def AccountKey=xyz");
            assertThat(s).doesNotContain("hunter2", ":pw@", "abc.def", "xyz");
        }
    }

    @Nested
    class CoercionTest {
        @Test
        void types() {
            assertThat(Coercion.coerce("1500.50", "decimal")).isEqualTo(new BigDecimal("1500.50"));
            assertThat(Coercion.coerce(1500.5, "decimal")).isEqualTo(new BigDecimal("1500.5"));
            assertThat(Coercion.coerce("2026-10-24T10:00:00", "date")).isEqualTo("2026-10-24");
            assertThat(Coercion.coerce("yes", "boolean")).isEqualTo(true);
            assertThatThrownBy(() -> Coercion.coerce("ABC", "decimal")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Coercion.coerce(true, "integer")).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class StorageHelpersTest {
        @Test
        void safeDestination() {
            assertThat(StorageSupport.safeDestination("inbound/customer/", "F.xml")).isEqualTo("inbound/customer/F.xml");
            for (String bad : List.of("../x.xml", "a/b.xml", "", "..")) {
                assertThatThrownBy(() -> StorageSupport.safeDestination("inbound", bad)).isInstanceOf(ConfigException.class);
            }
        }

        @Test
        void boundedRetries() {
            AtomicInteger calls = new AtomicInteger();
            String ok = StorageSupport.withRetries(() -> {
                if (calls.incrementAndGet() < 3) {
                    throw new TransientStorageException("503", null);
                }
                return "ok";
            }, 3, 0, "t", s -> { });
            assertThat(ok).isEqualTo("ok");

            calls.set(0);
            assertThatThrownBy(() -> StorageSupport.withRetries(() -> {
                calls.incrementAndGet();
                throw new TransientStorageException("503", null);
            }, 3, 0, "t", s -> { })).isInstanceOf(StorageException.class);
            assertThat(calls.get()).isEqualTo(3);
        }
    }
}
