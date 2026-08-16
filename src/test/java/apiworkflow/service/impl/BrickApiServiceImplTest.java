package apiworkflow.service.impl;

import apiworkflow.entity.AppSwaggerMapping;
import apiworkflow.entity.EndpointDefinition;
import apiworkflow.entity.EndpointSchema;
import apiworkflow.entity.SwaggerSyncLog;
import apiworkflow.mapper.AppSwaggerMappingMapper;
import apiworkflow.mapper.EndpointDefinitionMapper;
import apiworkflow.mapper.EndpointSchemaMapper;
import apiworkflow.mapper.SwaggerSyncLogMapper;
import apiworkflow.swagger.SwaggerDocumentParser;
import apiworkflow.swagger.EndpointSchemaResolver;
import apiworkflow.swagger.ParsedSwaggerDocument;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BrickApiServiceImplTest {

    @Mock
    private AppSwaggerMappingMapper swaggerMappingMapper;

    @Mock
    private EndpointDefinitionMapper endpointDefinitionMapper;

    @Mock
    private EndpointSchemaMapper endpointSchemaMapper;

    @Mock
    private SwaggerSyncLogMapper swaggerSyncLogMapper;

    private BrickApiServiceImpl service;
    private SwaggerDocumentParser parser;
    private String swaggerJson;

    @BeforeEach
    void setUp() throws IOException {
        service = new BrickApiServiceImpl();
        parser = new SwaggerDocumentParser();
        swaggerJson = read("examples/mock-commerce-openapi.json");
        ReflectionTestUtils.setField(service, "swaggerMappingMapper", swaggerMappingMapper);
        ReflectionTestUtils.setField(service, "endpointDefinitionMapper", endpointDefinitionMapper);
        ReflectionTestUtils.setField(service, "endpointSchemaMapper", endpointSchemaMapper);
        ReflectionTestUtils.setField(service, "swaggerSyncLogMapper", swaggerSyncLogMapper);
        ReflectionTestUtils.setField(service, "swaggerDocumentParser", parser);
        ReflectionTestUtils.setField(service, "endpointSchemaResolver", new EndpointSchemaResolver());
    }

    @Test
    void importsAllEndpointsAndWritesSyncStatistics() {
        AppSwaggerMapping mapping = mapping();
        when(swaggerMappingMapper.selectById(7)).thenReturn(mapping);
        when(endpointDefinitionMapper.selectAllBySwaggerMappingIdIncludingDeleted(7))
                .thenReturn(Collections.emptyList());

        int count = service.syncEndpointDefinitionsBySwaggerMappingId(
                7, swaggerJson, "graham", null, null
        );

        assertEquals(8, count);
        verify(endpointDefinitionMapper).markDeletedBySwaggerMappingId(7, "graham");

        ArgumentCaptor<List<EndpointDefinition>> endpointCaptor = endpointListCaptor();
        verify(endpointDefinitionMapper).batchUpsert(endpointCaptor.capture());
        List<EndpointDefinition> endpoints = endpointCaptor.getValue();
        assertEquals(8, endpoints.size());
        assertTrue(endpoints.stream().allMatch(endpoint -> Integer.valueOf(7).equals(endpoint.getSwaggerMappingId())));
        assertTrue(endpoints.stream().allMatch(endpoint -> "mock-commerce".equals(endpoint.getAppConfigId())));
        assertTrue(endpoints.stream().allMatch(endpoint -> Integer.valueOf(0).equals(endpoint.getIsDeleted())));

        verify(endpointSchemaMapper).markDeletedBySwaggerMappingId(7, "graham");
        ArgumentCaptor<List<EndpointSchema>> schemaCaptor = endpointSchemaListCaptor();
        verify(endpointSchemaMapper).batchUpsert(schemaCaptor.capture());
        List<EndpointSchema> schemas = schemaCaptor.getValue();
        assertEquals(20, schemas.size());
        assertTrue(schemas.stream().allMatch(schema -> Integer.valueOf(7).equals(schema.getSwaggerMappingId())));
        assertTrue(schemas.stream().allMatch(schema -> "graham".equals(schema.getUpdateBy())));

        ArgumentCaptor<SwaggerSyncLog> logCaptor = ArgumentCaptor.forClass(SwaggerSyncLog.class);
        verify(swaggerSyncLogMapper).insert(logCaptor.capture());
        SwaggerSyncLog log = logCaptor.getValue();
        assertEquals(Integer.valueOf(0), log.getInterfacesBefore());
        assertEquals(Integer.valueOf(8), log.getInterfacesAfter());
        assertEquals(Integer.valueOf(8), log.getInterfacesAdded());
        assertEquals(Integer.valueOf(0), log.getInterfacesUpdated());
        assertEquals(Integer.valueOf(0), log.getInterfacesDeleted());
    }

    @Test
    void distinguishesUnchangedAddedAndDeletedEndpointsDuringResync() {
        AppSwaggerMapping mapping = mapping();
        when(swaggerMappingMapper.selectById(7)).thenReturn(mapping);

        EndpointDefinition unchanged = parser.parse(swaggerJson, null, null).getEndpoints().get(0);
        unchanged.setIsDeleted(0);
        EndpointDefinition stale = new EndpointDefinition();
        stale.setHttpMethod("GET");
        stale.setEndpointPath("/removed");
        stale.setDocChecksum("old");
        stale.setIsDeleted(0);
        when(endpointDefinitionMapper.selectAllBySwaggerMappingIdIncludingDeleted(7))
                .thenReturn(Arrays.asList(unchanged, stale));

        service.syncEndpointDefinitionsBySwaggerMappingId(7, swaggerJson, "graham", null, null);

        verify(endpointDefinitionMapper).batchUpsert(anyList());
        ArgumentCaptor<SwaggerSyncLog> logCaptor = ArgumentCaptor.forClass(SwaggerSyncLog.class);
        verify(swaggerSyncLogMapper).insert(logCaptor.capture());
        SwaggerSyncLog log = logCaptor.getValue();
        assertEquals(Integer.valueOf(2), log.getInterfacesBefore());
        assertEquals(Integer.valueOf(8), log.getInterfacesAfter());
        assertEquals(Integer.valueOf(7), log.getInterfacesAdded());
        assertEquals(Integer.valueOf(0), log.getInterfacesUpdated());
        assertEquals(Integer.valueOf(1), log.getInterfacesDeleted());
    }

    @Test
    void endpointDetailReturnsRawAndRecursivelyResolvedRequestDefinitions() {
        ParsedSwaggerDocument document = parser.parse(swaggerJson, null, null);
        EndpointDefinition endpoint = document.getEndpoints().stream()
                .filter(item -> "POST".equals(item.getHttpMethod()) && "/orders".equals(item.getEndpointPath()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Create order endpoint not found"));
        endpoint.setId(18);
        endpoint.setSwaggerMappingId(7);
        when(endpointDefinitionMapper.selectById(18)).thenReturn(endpoint);
        when(endpointSchemaMapper.selectBySwaggerMappingId(7)).thenReturn(document.getSchemas());

        Map<String, Object> detail = service.getEndpointDetail(18);

        assertTrue(detail.get("requestDefinition") instanceof JSONObject);
        JSONObject resolved = (JSONObject) detail.get("resolvedRequestDefinition");
        assertEquals("object", resolved.getJSONObject("requestBody")
                .getJSONObject("schema").getString("type"));
        assertEquals("string", resolved.getJSONObject("requestBody")
                .getJSONObject("schema")
                .getJSONObject("properties")
                .getJSONObject("deliveryAddress")
                .getJSONObject("properties")
                .getJSONObject("city")
                .getString("type"));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ArgumentCaptor<List<EndpointDefinition>> endpointListCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ArgumentCaptor<List<EndpointSchema>> endpointSchemaListCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
    }

    private AppSwaggerMapping mapping() {
        AppSwaggerMapping mapping = new AppSwaggerMapping();
        mapping.setId(7);
        mapping.setEnv("test");
        mapping.setAppConfigId("mock-commerce");
        mapping.setSwaggerUrl("http://localhost:9090/openapi.json");
        return mapping;
    }

    private String read(String path) throws IOException {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }
}
