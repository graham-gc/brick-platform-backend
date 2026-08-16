package apiworkflow.service.impl;

import apiworkflow.entity.AppSwaggerMapping;
import apiworkflow.entity.EndpointDefinition;
import apiworkflow.entity.EndpointSchema;
import apiworkflow.entity.SwaggerSyncLog;
import apiworkflow.mapper.AppSwaggerMappingMapper;
import apiworkflow.mapper.EndpointDefinitionMapper;
import apiworkflow.mapper.EndpointSchemaMapper;
import apiworkflow.mapper.SwaggerSyncLogMapper;
import apiworkflow.service.IBrickApiService;
import apiworkflow.swagger.ParsedSwaggerDocument;
import apiworkflow.swagger.EndpointSchemaResolver;
import apiworkflow.swagger.SwaggerDocumentParser;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.*;

@Service
public class BrickApiServiceImpl implements IBrickApiService {

    private static final int SCHEMA_BATCH_SIZE = 100;

    @Autowired
    private AppSwaggerMappingMapper swaggerMappingMapper;

    @Autowired
    private EndpointDefinitionMapper endpointDefinitionMapper;

    @Autowired
    private EndpointSchemaMapper endpointSchemaMapper;

    @Autowired
    private EndpointSchemaResolver endpointSchemaResolver;

    @Autowired
    private SwaggerSyncLogMapper swaggerSyncLogMapper;

    @Autowired
    private SwaggerDocumentParser swaggerDocumentParser;

    @Override
    public AppSwaggerMapping getById(Integer id) {
        return swaggerMappingMapper.selectById(id);
    }

    @Override
    public AppSwaggerMapping getByEnvAppConfigAndVersion(String env, String appConfigId, String versionTag) {
        return swaggerMappingMapper.selectByEnvAppConfigAndVersion(env, appConfigId, versionTag);
    }

    @Override
    public List<AppSwaggerMapping> getVersionsByEnvAndAppConfig(String env, String appConfigId) {
        return swaggerMappingMapper.selectVersionsByEnvAndAppConfig(env, appConfigId);
    }

    @Override
    public int createMapping(AppSwaggerMapping record) {
        return swaggerMappingMapper.insert(record);
    }

    @Override
    public int updateMapping(AppSwaggerMapping record) {
        return swaggerMappingMapper.updateById(record);
    }

    @Override
    public int softDeleteMapping(Integer id, String operator) {
        return swaggerMappingMapper.softDeleteById(id, operator);
    }

    @Override
    public List<AppSwaggerMapping> selectList(AppSwaggerMapping query) {
        return swaggerMappingMapper.selectList(query);
    }

    @Override
    public String detectAndFetchSwaggerJson(String rawUrl) {
        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpGet request = new HttpGet(rawUrl);
            try (CloseableHttpResponse response = httpClient.execute(request)) {
                return EntityUtils.toString(response.getEntity());
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to fetch Swagger JSON from: " + rawUrl, e);
        }
    }

    @Override
    public boolean isValidSwaggerJson(String json) {
        try {
            swaggerDocumentParser.parse(json, null, null);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public String validateSwaggerJsonDetailed(String json) {
        try {
            ParsedSwaggerDocument document = swaggerDocumentParser.parse(json, null, null);
            String type = "v2".equals(document.getSwaggerVersion()) ? "Swagger v2" : "OpenAPI v3";
            return type + " (" + document.getEndpoints().size() + " endpoints)";
        } catch (Exception e) {
            return "Invalid: " + e.getMessage();
        }
    }

    @Override
    @Transactional
    public int syncEndpointDefinitionsBySwaggerMappingId(
            Integer swaggerMappingId, String apiDocsJson, String operator,
            String customHost, String customBasePath) {
        Date startTime = new Date();
        AppSwaggerMapping mapping = swaggerMappingMapper.selectById(swaggerMappingId);
        if (mapping == null) {
            throw new RuntimeException("Swagger mapping not found: " + swaggerMappingId);
        }

        String syncOperator = StringUtils.hasText(operator) ? operator : "system";
        ParsedSwaggerDocument document = swaggerDocumentParser.parse(apiDocsJson, customHost, customBasePath);
        List<EndpointDefinition> incoming = document.getEndpoints();
        List<EndpointDefinition> existing = endpointDefinitionMapper
                .selectAllBySwaggerMappingIdIncludingDeleted(swaggerMappingId);

        Map<String, EndpointDefinition> existingByKey = new HashMap<>();
        int interfacesBefore = 0;
        for (EndpointDefinition endpoint : existing) {
            if (Integer.valueOf(0).equals(endpoint.getIsDeleted())) {
                interfacesBefore++;
            }
            String key = endpointKey(endpoint);
            EndpointDefinition current = existingByKey.get(key);
            if (current == null || (!Integer.valueOf(0).equals(current.getIsDeleted())
                    && Integer.valueOf(0).equals(endpoint.getIsDeleted()))) {
                existingByKey.put(key, endpoint);
            }
        }

        Set<String> incomingKeys = new HashSet<>();
        int interfacesAdded = 0;
        int interfacesUpdated = 0;
        for (EndpointDefinition endpoint : incoming) {
            endpoint.setEnv(mapping.getEnv());
            endpoint.setSwaggerMappingId(swaggerMappingId);
            endpoint.setAppConfigId(mapping.getAppConfigId());
            endpoint.setSwaggerUrl(mapping.getSwaggerUrl());
            endpoint.setCreateBy(syncOperator);
            endpoint.setUpdateBy(syncOperator);
            endpoint.setIsDeleted(0);

            String key = endpointKey(endpoint);
            incomingKeys.add(key);
            EndpointDefinition old = existingByKey.get(key);
            if (old == null || !Integer.valueOf(0).equals(old.getIsDeleted())) {
                interfacesAdded++;
            } else if (!Objects.equals(old.getDocChecksum(), endpoint.getDocChecksum())) {
                interfacesUpdated++;
            }
        }

        int interfacesDeleted = 0;
        for (EndpointDefinition endpoint : existing) {
            if (Integer.valueOf(0).equals(endpoint.getIsDeleted())
                    && !incomingKeys.contains(endpointKey(endpoint))) {
                interfacesDeleted++;
            }
        }

        endpointDefinitionMapper.markDeletedBySwaggerMappingId(swaggerMappingId, syncOperator);
        if (!incoming.isEmpty()) {
            endpointDefinitionMapper.batchUpsert(incoming);
        }

        syncSchemas(swaggerMappingId, document.getSchemas(), syncOperator);

        Date endTime = new Date();
        SwaggerSyncLog syncLog = new SwaggerSyncLog();
        syncLog.setSwaggerMappingId(swaggerMappingId);
        syncLog.setSyncType("manual");
        syncLog.setSyncStatus("success");
        syncLog.setStartTime(startTime);
        syncLog.setEndTime(endTime);
        syncLog.setDurationMs(endTime.getTime() - startTime.getTime());
        syncLog.setInterfacesBefore(interfacesBefore);
        syncLog.setInterfacesAfter(incoming.size());
        syncLog.setInterfacesAdded(interfacesAdded);
        syncLog.setInterfacesUpdated(interfacesUpdated);
        syncLog.setInterfacesDeleted(interfacesDeleted);
        syncLog.setCreateBy(syncOperator);
        swaggerSyncLogMapper.insert(syncLog);

        return incoming.size();
    }

    private void syncSchemas(Integer swaggerMappingId, List<EndpointSchema> schemas, String operator) {
        endpointSchemaMapper.markDeletedBySwaggerMappingId(swaggerMappingId, operator);
        for (EndpointSchema schema : schemas) {
            schema.setSwaggerMappingId(swaggerMappingId);
            schema.setIsDeleted(0);
            schema.setCreateBy(operator);
            schema.setUpdateBy(operator);
        }
        for (int fromIndex = 0; fromIndex < schemas.size(); fromIndex += SCHEMA_BATCH_SIZE) {
            int toIndex = Math.min(fromIndex + SCHEMA_BATCH_SIZE, schemas.size());
            endpointSchemaMapper.batchUpsert(schemas.subList(fromIndex, toIndex));
        }
    }

    private String endpointKey(EndpointDefinition endpoint) {
        return endpoint.getHttpMethod() + " " + endpoint.getEndpointPath();
    }

    @Override
    public List<EndpointDefinition> selectEndpointPageBySwaggerMappingId(
            Integer swaggerMappingId, String method, String keyword, int offset, int pageSize) {
        return endpointDefinitionMapper.selectPageBySwaggerMappingId(
                swaggerMappingId, method, keyword, 0, offset, pageSize);
    }

    @Override
    public int countEndpointsBySwaggerMappingId(Integer swaggerMappingId, String method, String keyword) {
        return endpointDefinitionMapper.countBySwaggerMappingId(swaggerMappingId, method, keyword, 0);
    }

    @Override
    public Map<String, Object> getEndpointDetail(Integer endpointId) {
        EndpointDefinition endpoint = endpointDefinitionMapper.selectById(endpointId);
        Map<String, Object> result = new HashMap<>();
        result.put("endpoint", endpoint);
        if (endpoint == null || !StringUtils.hasText(endpoint.getRequestDefinitionJson())) {
            result.put("requestDefinition", null);
            result.put("resolvedRequestDefinition", null);
            return result;
        }

        Object requestDefinition;
        try {
            requestDefinition = com.alibaba.fastjson.JSON.parse(endpoint.getRequestDefinitionJson());
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Stored request definition is not valid JSON for endpoint " + endpointId, e);
        }
        List<EndpointSchema> schemas = endpointSchemaMapper
                .selectBySwaggerMappingId(endpoint.getSwaggerMappingId());
        result.put("requestDefinition", requestDefinition);
        result.put("resolvedRequestDefinition", endpointSchemaResolver.resolveValue(schemas, requestDefinition));
        return result;
    }

    @Override
    public Object resolveEndpointSchema(Integer swaggerMappingId, String schemaRef) {
        List<EndpointSchema> schemas = endpointSchemaMapper
                .selectBySwaggerMappingId(swaggerMappingId);
        return endpointSchemaResolver.resolve(schemas, schemaRef);
    }

    @Override
    public Map<Integer, Map<String, Object>> getBatchEndpointStats(List<Integer> swaggerMappingIds, String keyword) {
        List<Map<String, Object>> stats = endpointDefinitionMapper.batchEndpointStats(swaggerMappingIds, keyword, 0);
        Map<Integer, Map<String, Object>> result = new HashMap<>();
        for (Map<String, Object> stat : stats) {
            Integer mappingId = (Integer) stat.get("swagger_mapping_id");
            result.put(mappingId, stat);
        }
        return result;
    }

    @Override
    public int countCoveredEndpointsBySwaggerMappingId(Integer swaggerMappingId) {
        return endpointDefinitionMapper.countCoveredEndpointsBySwaggerMappingId(swaggerMappingId);
    }
}
