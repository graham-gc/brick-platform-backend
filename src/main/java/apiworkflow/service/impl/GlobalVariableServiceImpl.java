package apiworkflow.service.impl;

import apiworkflow.entity.BrickGlobalVariable;
import apiworkflow.execution.GlobalVariableDataSourceRegistry;
import apiworkflow.mapper.BrickGlobalVariableMapper;
import apiworkflow.service.IGlobalVariableService;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

@Service
public class GlobalVariableServiceImpl implements IGlobalVariableService {

    private static final Pattern VARIABLE_NAME = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");
    private static final Pattern NAMED_SQL_PARAMETER = Pattern.compile("(?<!:):([A-Za-z_][A-Za-z0-9_]*)");
    private static final Pattern SELECT_STATEMENT = Pattern.compile("(?is)^SELECT\\b.*");
    private static final Set<String> VARIABLE_TYPES = new HashSet<>(Arrays.asList(
            "BUILTIN", "FUNCTION", "DATABASE_QUERY", "STATIC", "FILE", "TOKEN", "DYNAMIC"));
    private static final Set<String> DATA_TYPES = new HashSet<>(Arrays.asList(
            "string", "number", "boolean", "object", "array"));

    @Autowired
    private BrickGlobalVariableMapper globalVariableMapper;

    @Autowired
    private GlobalVariableDataSourceRegistry dataSourceRegistry;

    @Override
    public BrickGlobalVariable getById(Long id) {
        return globalVariableMapper.selectById(id);
    }

    @Override
    public BrickGlobalVariable getByName(String name) {
        return globalVariableMapper.selectByName(name);
    }

    @Override
    public List<BrickGlobalVariable> selectList(BrickGlobalVariable query) {
        return globalVariableMapper.selectList(query);
    }

    @Override
    public List<BrickGlobalVariable> selectByType(String type) {
        return globalVariableMapper.selectByType(type);
    }

    @Override
    public int create(BrickGlobalVariable variable, String operator) {
        validateAndNormalise(variable, false);
        variable.setCreateBy(StringUtils.hasText(operator) ? operator : "system");
        return globalVariableMapper.insert(variable);
    }

    @Override
    public int update(BrickGlobalVariable variable, String operator) {
        validateAndNormalise(variable, true);
        variable.setUpdateBy(StringUtils.hasText(operator) ? operator : "system");
        return globalVariableMapper.updateById(variable);
    }

    @Override
    public int delete(Long id) {
        return globalVariableMapper.deleteById(id);
    }

    private void validateAndNormalise(BrickGlobalVariable variable, boolean update) {
        if (variable == null) {
            throw new IllegalArgumentException("Global variable is required");
        }
        if (update && variable.getId() == null) {
            throw new IllegalArgumentException("Global variable id is required");
        }
        String name = variable.getName() == null ? null : variable.getName().trim();
        if (!StringUtils.hasText(name) || !VARIABLE_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "Global variable name must start with a letter or underscore and contain only letters, numbers, and underscores");
        }
        String type = variable.getType() == null
                ? null : variable.getType().trim().toUpperCase(Locale.ROOT);
        if (!VARIABLE_TYPES.contains(type)) {
            throw new IllegalArgumentException("Unsupported global variable type: " + variable.getType());
        }
        String dataType = StringUtils.hasText(variable.getDataType())
                ? variable.getDataType().trim().toLowerCase(Locale.ROOT) : "string";
        if (!DATA_TYPES.contains(dataType)) {
            throw new IllegalArgumentException("Unsupported global variable data type: " + variable.getDataType());
        }

        BrickGlobalVariable existing = globalVariableMapper.selectByName(name);
        if (existing != null && (!update || !existing.getId().equals(variable.getId()))) {
            throw new IllegalArgumentException("Global variable name already exists: " + name);
        }

        List<String> parameterNames = new ArrayList<>();
        if ("STATIC".equals(type) || "TOKEN".equals(type)) {
            validateStoredValueConfig(variable.getConfig(), name, type);
        } else if ("FUNCTION".equals(type)) {
            parameterNames = validateFunction(variable, name);
        } else if ("DATABASE_QUERY".equals(type)) {
            parameterNames = validateDatabaseQuery(variable, name, dataType);
        }
        variable.setName(name);
        variable.setType(type);
        variable.setDataType(dataType);
        variable.setIsEnabled(variable.getIsEnabled() == null ? 1 : variable.getIsEnabled() == 0 ? 0 : 1);
        variable.setHasParams(parameterNames.isEmpty() ? 0 : 1);
        variable.setSyntax(parameterNames.isEmpty()
                ? "${{" + name + "}}"
                : "${{" + name + "(" + String.join(",", parameterNames) + ")}}");
    }

    private void validateStoredValueConfig(String config, String name, String type) {
        if (!StringUtils.hasText(config)) {
            throw new IllegalArgumentException(type + " global variable value is required: " + name);
        }
        try {
            Object parsed = JSON.parse(config);
            if (!(parsed instanceof JSONObject) || !((JSONObject) parsed).containsKey("value")) {
                throw new IllegalArgumentException(
                        type + " global variable configuration must contain value: " + name);
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException(type + " global variable configuration is invalid: " + name, e);
        }
    }

    private List<String> validateFunction(BrickGlobalVariable variable, String variableName) {
        final JSONObject config;
        try {
            Object parsed = JSON.parse(variable.getConfig());
            if (!(parsed instanceof JSONObject)) {
                throw new IllegalArgumentException("FUNCTION configuration must be a JSON object: " + variableName);
            }
            config = (JSONObject) parsed;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("FUNCTION configuration is invalid: " + variableName, e);
        }
        if (!StringUtils.hasText(config.getString("script"))) {
            throw new IllegalArgumentException("FUNCTION JavaScript is required: " + variableName);
        }

        return validateParameterSchema(variable, variableName, "FUNCTION");
    }

    private List<String> validateDatabaseQuery(BrickGlobalVariable variable, String variableName,
                                               String dataType) {
        final JSONObject config;
        try {
            Object parsed = JSON.parse(variable.getConfig());
            if (!(parsed instanceof JSONObject)) {
                throw new IllegalArgumentException(
                        "DATABASE_QUERY configuration must be a JSON object: " + variableName);
            }
            config = (JSONObject) parsed;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "DATABASE_QUERY configuration is invalid: " + variableName, e);
        }
        String dataSource = StringUtils.hasText(config.getString("dataSource"))
                ? config.getString("dataSource").trim() : "primary";
        dataSourceRegistry.require(dataSource);
        String sql = config.getString("sql");
        String upperSql = StringUtils.hasText(sql) ? sql.trim().toUpperCase(Locale.ROOT) : "";
        if (!StringUtils.hasText(sql)
                || !SELECT_STATEMENT.matcher(sql.trim()).matches()
                || sql.contains(";") || sql.contains("--") || sql.contains("/*") || sql.contains("*/")) {
            throw new IllegalArgumentException(
                    "DATABASE_QUERY only permits one comment-free SELECT statement: " + variableName);
        }
        if (upperSql.contains(" INTO OUTFILE") || upperSql.contains(" INTO DUMPFILE")
                || upperSql.contains(" FOR UPDATE") || upperSql.contains(" LOCK IN SHARE MODE")) {
            throw new IllegalArgumentException(
                    "DATABASE_QUERY does not permit locking or file-writing SELECT statements: " + variableName);
        }
        String resultMode = StringUtils.hasText(config.getString("resultMode"))
                ? config.getString("resultMode").trim().toUpperCase(Locale.ROOT) : "VALUE";
        if (!Arrays.asList("VALUE", "ROW", "LIST").contains(resultMode)) {
            throw new IllegalArgumentException("Unsupported DATABASE_QUERY result mode: " + resultMode);
        }
        if ("ROW".equals(resultMode) && !"object".equals(dataType)) {
            throw new IllegalArgumentException("DATABASE_QUERY ROW result must use object data type");
        }
        if ("LIST".equals(resultMode) && !"array".equals(dataType)) {
            throw new IllegalArgumentException("DATABASE_QUERY LIST result must use array data type");
        }

        List<String> parameterNames = validateParameterSchema(variable, variableName, "DATABASE_QUERY");
        Set<String> referencedNames = new HashSet<>();
        Matcher matcher = NAMED_SQL_PARAMETER.matcher(sql);
        while (matcher.find()) {
            referencedNames.add(matcher.group(1));
        }
        if (!referencedNames.equals(new HashSet<>(parameterNames))) {
            throw new IllegalArgumentException(
                    "DATABASE_QUERY SQL parameters must exactly match the configured parameters: " + variableName);
        }
        config.put("dataSource", dataSource);
        config.put("sql", sql.trim());
        config.put("resultMode", resultMode);
        variable.setConfig(config.toJSONString());
        return parameterNames;
    }

    private List<String> validateParameterSchema(BrickGlobalVariable variable, String variableName,
                                                 String variableType) {
        JSONArray parameters;
        if (!StringUtils.hasText(variable.getParamSchema())) {
            parameters = new JSONArray();
        } else {
            try {
                parameters = JSON.parseArray(variable.getParamSchema());
            } catch (Exception e) {
                throw new IllegalArgumentException(
                        variableType + " parameter schema is invalid: " + variableName, e);
            }
        }
        Set<String> uniqueNames = new HashSet<>();
        List<String> names = new ArrayList<>();
        for (Object item : parameters) {
            if (!(item instanceof JSONObject)) {
                throw new IllegalArgumentException(
                        variableType + " parameter definition is invalid: " + variableName);
            }
            JSONObject parameter = (JSONObject) item;
            String name = parameter.getString("name");
            if (!StringUtils.hasText(name) || !VARIABLE_NAME.matcher(name).matches()
                    || !uniqueNames.add(name)) {
                throw new IllegalArgumentException(
                        variableType + " parameter name is invalid or duplicated: " + variableName);
            }
            String parameterType = StringUtils.hasText(parameter.getString("type"))
                    ? parameter.getString("type").trim().toLowerCase(Locale.ROOT) : "string";
            if (!DATA_TYPES.contains(parameterType)) {
                throw new IllegalArgumentException(
                        variableType + " parameter data type is unsupported: " + parameter.getString("type"));
            }
            parameter.put("name", name);
            parameter.put("type", parameterType);
            names.add(name);
        }
        variable.setParamSchema(parameters.toJSONString());
        return names;
    }
}
