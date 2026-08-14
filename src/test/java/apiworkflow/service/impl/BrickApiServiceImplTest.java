package apiworkflow.service.impl;

import apiworkflow.entity.AppSwaggerMapping;
import apiworkflow.entity.EndpointDefinition;
import apiworkflow.entity.SwaggerSyncLog;
import apiworkflow.mapper.AppSwaggerMappingMapper;
import apiworkflow.mapper.EndpointDefinitionMapper;
import apiworkflow.mapper.SwaggerSyncLogMapper;
import apiworkflow.swagger.SwaggerDocumentParser;
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
        ReflectionTestUtils.setField(service, "swaggerSyncLogMapper", swaggerSyncLogMapper);
        ReflectionTestUtils.setField(service, "swaggerDocumentParser", parser);
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

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ArgumentCaptor<List<EndpointDefinition>> endpointListCaptor() {
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
