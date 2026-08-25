package apiworkflow.execution;

import apiworkflow.entity.BrickGlobalVariable;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DatabaseQueryVariableExecutorTest {

    private DatabaseQueryVariableExecutor executor;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:global_variables;MODE=MySQL;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("DROP TABLE IF EXISTS test_orders");
        jdbcTemplate.execute("CREATE TABLE test_orders (id VARCHAR(30), status VARCHAR(30), amount DECIMAL(10,2))");
        jdbcTemplate.update("INSERT INTO test_orders (id, status, amount) VALUES (?, ?, ?)",
                "ord-1", "PAID", 49.90);
        jdbcTemplate.update("INSERT INTO test_orders (id, status, amount) VALUES (?, ?, ?)",
                "ord-2", "PENDING", 29.50);

        GlobalVariableDataSourceRegistry registry = new GlobalVariableDataSourceRegistry(
                dataSource, Collections.<String, DataSource>singletonMap("dataSource", dataSource));
        executor = new DatabaseQueryVariableExecutor();
        ReflectionTestUtils.setField(executor, "dataSourceRegistry", registry);
    }

    @Test
    void executesNamedReadOnlyQueryAndReturnsScalar() {
        BrickGlobalVariable variable = variable(
                "orderStatus", "VALUE",
                "SELECT status FROM test_orders WHERE id = :orderId",
                "[{\"name\":\"orderId\",\"type\":\"string\"}]");

        Object result = executor.execute(variable, Collections.<Object>singletonList("ord-1"));

        assertEquals("PAID", result);
    }

    @Test
    void returnsRowOrListUsingConfiguredMode() {
        BrickGlobalVariable rowVariable = variable(
                "order", "ROW",
                "SELECT id, status FROM test_orders WHERE id = :orderId",
                "[{\"name\":\"orderId\",\"type\":\"string\"}]");
        Object row = executor.execute(rowVariable, Collections.<Object>singletonList("ord-1"));
        assertEquals("PAID", ((JSONObject) row).getString("STATUS"));

        BrickGlobalVariable listVariable = variable(
                "orders", "LIST", "SELECT id, status FROM test_orders", "[]");
        Object list = executor.execute(listVariable, Collections.emptyList());
        assertEquals(2, ((JSONArray) list).size());
    }

    @Test
    void rejectsMutationAndArgumentCountMismatch() {
        BrickGlobalVariable mutation = variable(
                "deleteOrder", "VALUE", "DELETE FROM test_orders", "[]");
        assertThrows(IllegalStateException.class,
                () -> executor.execute(mutation, Collections.emptyList()));

        BrickGlobalVariable parameterized = variable(
                "orderStatus", "VALUE",
                "SELECT status FROM test_orders WHERE id = :orderId",
                "[{\"name\":\"orderId\",\"type\":\"string\"}]");
        assertThrows(IllegalStateException.class,
                () -> executor.execute(parameterized, Arrays.<Object>asList("ord-1", "extra")));
    }

    private BrickGlobalVariable variable(String name, String mode, String sql, String paramSchema) {
        BrickGlobalVariable variable = new BrickGlobalVariable();
        variable.setName(name);
        variable.setType("DATABASE_QUERY");
        variable.setDataType("VALUE".equals(mode) ? "string" : "ROW".equals(mode) ? "object" : "array");
        JSONObject config = new JSONObject(true);
        config.put("dataSource", "primary");
        config.put("sql", sql);
        config.put("resultMode", mode);
        variable.setConfig(config.toJSONString());
        variable.setParamSchema(paramSchema);
        return variable;
    }
}
