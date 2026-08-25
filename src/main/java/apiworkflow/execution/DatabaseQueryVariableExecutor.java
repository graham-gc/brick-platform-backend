package apiworkflow.execution;

import apiworkflow.entity.BrickGlobalVariable;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Component
public class DatabaseQueryVariableExecutor {

    private static final int MAX_ROWS = 1000;
    private static final int QUERY_TIMEOUT_SECONDS = 5;
    private static final Pattern SELECT_STATEMENT = Pattern.compile("(?is)^SELECT\\b.*");

    @Autowired
    private GlobalVariableDataSourceRegistry dataSourceRegistry;

    public Object execute(BrickGlobalVariable variable, List<Object> arguments) {
        JSONObject config = config(variable);
        String sql = config.getString("sql");
        validateReadOnlySql(sql, variable.getName());
        List<String> parameterNames = parameterNames(variable);
        if (arguments.size() != parameterNames.size()) {
            throw new IllegalStateException("DATABASE_QUERY " + variable.getName() + " expects "
                    + parameterNames.size() + " argument(s) but received " + arguments.size());
        }

        DataSource dataSource = dataSourceRegistry.require(config.getString("dataSource"));
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.setMaxRows(MAX_ROWS);
        jdbcTemplate.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
        NamedParameterJdbcTemplate namedTemplate = new NamedParameterJdbcTemplate(jdbcTemplate);
        MapSqlParameterSource parameters = new MapSqlParameterSource();
        for (int index = 0; index < parameterNames.size(); index++) {
            parameters.addValue(parameterNames.get(index), plainValue(arguments.get(index)));
        }

        List<Map<String, Object>> rows;
        try {
            rows = namedTemplate.queryForList(sql, parameters);
        } catch (Exception e) {
            throw new IllegalStateException("DATABASE_QUERY execution failed for " + variable.getName()
                    + ": " + rootMessage(e), e);
        }
        return result(rows, config.getString("resultMode"));
    }

    private JSONObject config(BrickGlobalVariable variable) {
        try {
            Object parsed = JSON.parse(variable.getConfig());
            if (parsed instanceof JSONObject) {
                return (JSONObject) parsed;
            }
        } catch (Exception ignored) {
            // Use the stable error below.
        }
        throw new IllegalStateException(
                "DATABASE_QUERY global variable has invalid configuration: " + variable.getName());
    }

    private void validateReadOnlySql(String sql, String name) {
        if (!StringUtils.hasText(sql)) {
            throw new IllegalStateException("DATABASE_QUERY SQL is required: " + name);
        }
        String trimmed = sql.trim();
        String upper = trimmed.toUpperCase(Locale.ROOT);
        if (!SELECT_STATEMENT.matcher(trimmed).matches()
                || trimmed.contains(";") || trimmed.contains("--")
                || trimmed.contains("/*") || trimmed.contains("*/")
                || upper.contains(" INTO OUTFILE") || upper.contains(" INTO DUMPFILE")
                || upper.contains(" FOR UPDATE") || upper.contains(" LOCK IN SHARE MODE")) {
            throw new IllegalStateException(
                    "DATABASE_QUERY only permits one comment-free SELECT statement: " + name);
        }
    }

    private List<String> parameterNames(BrickGlobalVariable variable) {
        if (!StringUtils.hasText(variable.getParamSchema())) {
            return Collections.emptyList();
        }
        try {
            JSONArray schema = JSON.parseArray(variable.getParamSchema());
            List<String> names = new ArrayList<>();
            for (Object item : schema) {
                if (!(item instanceof JSONObject)
                        || !StringUtils.hasText(((JSONObject) item).getString("name"))) {
                    throw new IllegalArgumentException("invalid parameter");
                }
                names.add(((JSONObject) item).getString("name"));
            }
            return names;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "DATABASE_QUERY has invalid parameter schema: " + variable.getName(), e);
        }
    }

    private Object plainValue(Object value) {
        if (value instanceof JSONObject || value instanceof JSONArray) {
            return JSON.toJSONString(value);
        }
        return value;
    }

    private Object result(List<Map<String, Object>> rows, String configuredMode) {
        String mode = StringUtils.hasText(configuredMode)
                ? configuredMode.trim().toUpperCase(Locale.ROOT) : "VALUE";
        if ("LIST".equals(mode)) {
            Object result = JSON.toJSON(rows == null ? Collections.emptyList() : rows);
            return result instanceof JSONArray ? result : new JSONArray();
        }
        if ("ROW".equals(mode)) {
            if (rows == null || rows.isEmpty()) {
                return new JSONObject(true);
            }
            Object result = JSON.toJSON(rows.get(0));
            return result instanceof JSONObject ? result : new JSONObject(true);
        }
        if (!"VALUE".equals(mode)) {
            throw new IllegalStateException("Unsupported DATABASE_QUERY result mode: " + configuredMode);
        }
        if (rows == null || rows.isEmpty() || rows.get(0).isEmpty()) {
            return null;
        }
        return rows.get(0).values().iterator().next();
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
