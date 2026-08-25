package apiworkflow.service.impl;

import apiworkflow.entity.BrickGlobalVariable;
import apiworkflow.execution.GlobalVariableDataSourceRegistry;
import apiworkflow.mapper.BrickGlobalVariableMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GlobalVariableServiceImplTest {

    @Mock
    private BrickGlobalVariableMapper mapper;

    @Mock
    private GlobalVariableDataSourceRegistry dataSourceRegistry;

    private GlobalVariableServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new GlobalVariableServiceImpl();
        ReflectionTestUtils.setField(service, "globalVariableMapper", mapper);
        ReflectionTestUtils.setField(service, "dataSourceRegistry", dataSourceRegistry);
    }

    @Test
    void createsStaticVariableWithCanonicalSyntaxAndDefaults() {
        BrickGlobalVariable variable = new BrickGlobalVariable();
        variable.setName(" tenantId ");
        variable.setType("static");
        variable.setConfig("{\"value\":\"tenant-1\"}");
        when(mapper.insert(any(BrickGlobalVariable.class))).thenReturn(1);

        service.create(variable, null);

        ArgumentCaptor<BrickGlobalVariable> captor = ArgumentCaptor.forClass(BrickGlobalVariable.class);
        verify(mapper).insert(captor.capture());
        BrickGlobalVariable saved = captor.getValue();
        assertEquals("tenantId", saved.getName());
        assertEquals("STATIC", saved.getType());
        assertEquals("string", saved.getDataType());
        assertEquals("${{tenantId}}", saved.getSyntax());
        assertEquals(1, saved.getIsEnabled());
        assertEquals(0, saved.getHasParams());
        assertEquals("system", saved.getCreateBy());
    }

    @Test
    void rejectsInvalidNameDuplicateAndStaticConfiguration() {
        BrickGlobalVariable invalidName = staticVariable("not-valid-name", "{\"value\":1}");
        assertThrows(IllegalArgumentException.class, () -> service.create(invalidName, "admin"));

        BrickGlobalVariable duplicate = staticVariable("tenantId", "{\"value\":1}");
        BrickGlobalVariable existing = staticVariable("tenantId", "{\"value\":2}");
        existing.setId(4L);
        when(mapper.selectByName("tenantId")).thenReturn(existing);
        assertThrows(IllegalArgumentException.class, () -> service.create(duplicate, "admin"));

        BrickGlobalVariable missingValue = staticVariable("emptyValue", "{}");
        assertThrows(IllegalArgumentException.class, () -> service.create(missingValue, "admin"));
    }

    @Test
    void tokenUsesTheSameStoredValueContractAsStatic() {
        BrickGlobalVariable token = staticVariable("sessionCookie", "{\"value\":\"SESSION=demo\"}");
        token.setType("TOKEN");
        token.setDataType("string");
        when(mapper.insert(any(BrickGlobalVariable.class))).thenReturn(1);

        service.create(token, "graham");

        assertEquals("TOKEN", token.getType());
        assertEquals("${{sessionCookie}}", token.getSyntax());
        verify(mapper).insert(token);
    }

    @Test
    void functionDerivesItsSyntaxAndParameterFlagFromSchema() {
        BrickGlobalVariable function = new BrickGlobalVariable();
        function.setName("randomString");
        function.setType("FUNCTION");
        function.setDataType("string");
        function.setConfig("{\"script\":\"return Array(length + 1).join('x');\"}");
        function.setParamSchema("[{\"name\":\"length\",\"type\":\"NUMBER\"}]");
        when(mapper.insert(any(BrickGlobalVariable.class))).thenReturn(1);

        service.create(function, "graham");

        assertEquals(Integer.valueOf(1), function.getHasParams());
        assertEquals("${{randomString(length)}}", function.getSyntax());
        assertEquals("[{\"name\":\"length\",\"type\":\"number\"}]", function.getParamSchema());
        verify(mapper).insert(function);
    }

    @Test
    void databaseQueryUsesBackendDataSourceAndNamedParameters() {
        BrickGlobalVariable query = new BrickGlobalVariable();
        query.setName("orderStatus");
        query.setType("DATABASE_QUERY");
        query.setDataType("string");
        query.setConfig("{\"dataSource\":\"ordersReadOnly\",\"sql\":\"SELECT status FROM orders WHERE id = :orderId\",\"resultMode\":\"VALUE\"}");
        query.setParamSchema("[{\"name\":\"orderId\",\"type\":\"string\"}]");
        when(mapper.insert(any(BrickGlobalVariable.class))).thenReturn(1);

        service.create(query, "graham");

        verify(dataSourceRegistry).require("ordersReadOnly");
        assertEquals("${{orderStatus(orderId)}}", query.getSyntax());
        assertEquals(Integer.valueOf(1), query.getHasParams());
        verify(mapper).insert(query);
    }

    private BrickGlobalVariable staticVariable(String name, String config) {
        BrickGlobalVariable variable = new BrickGlobalVariable();
        variable.setName(name);
        variable.setType("STATIC");
        variable.setDataType("number");
        variable.setConfig(config);
        return variable;
    }
}
