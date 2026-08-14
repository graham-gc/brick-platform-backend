package apiworkflow.service.impl;

import apiworkflow.entity.AppSwaggerMapping;
import apiworkflow.entity.EndpointDefinition;
import apiworkflow.mapper.AppSwaggerMappingMapper;
import apiworkflow.mapper.EndpointDefinitionMapper;
import apiworkflow.service.IBrickApiService;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class BrickApiServiceImpl implements IBrickApiService {

    @Autowired
    private AppSwaggerMappingMapper swaggerMappingMapper;

    @Autowired
    private EndpointDefinitionMapper endpointDefinitionMapper;

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
        if (!StringUtils.hasText(json)) {
            return false;
        }
        try {
            JSONObject root = JSON.parseObject(json);
            return root.containsKey("swagger") || root.containsKey("openapi");
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public String validateSwaggerJsonDetailed(String json) {
        try {
            JSONObject root = JSON.parseObject(json);
            if (root.containsKey("swagger")) {
                return "Swagger v2";
            } else if (root.containsKey("openapi")) {
                return "OpenAPI v3";
            }
            return "Unknown";
        } catch (Exception e) {
            return "Invalid JSON";
        }
    }

    @Override
    public int syncEndpointDefinitionsBySwaggerMappingId(
            Integer swaggerMappingId, String apiDocsJson, String operator,
            String customHost, String customBasePath) {

        AppSwaggerMapping mapping = swaggerMappingMapper.selectById(swaggerMappingId);
        if (mapping == null) {
            throw new RuntimeException("Swagger mapping not found: " + swaggerMappingId);
        }

        JSONObject root = JSON.parseObject(apiDocsJson);
        String swaggerVersion = root.containsKey("swagger") ? "v2" : "v3";

        List<EndpointDefinition> endpoints = new ArrayList<>();
        JSONObject paths = root.getJSONObject("paths");
        if (paths != null) {
            for (Map.Entry<String, Object> pathEntry : paths.entrySet()) {
                String path = pathEntry.getKey();
                JSONObject pathItem = paths.getJSONObject(path);
                if (pathItem == null) continue;

                Set<String> methods = new HashSet<>();
                methods.add("get"); methods.add("post"); methods.add("put"); methods.add("delete");
                methods.add("patch"); methods.add("head"); methods.add("options");

                for (String method : methods) {
                    if (pathItem.containsKey(method)) {
                        JSONObject operation = pathItem.getJSONObject(method);
                        EndpointDefinition endpoint = new EndpointDefinition();
                        endpoint.setEnv(mapping.getEnv());
                        endpoint.setSwaggerMappingId(swaggerMappingId);
                        endpoint.setAppConfigId(mapping.getAppConfigId());
                        endpoint.setProtocol("http");
                        endpoint.setHost(customHost != null ? customHost : "");
                        endpoint.setBasePath(customBasePath != null ? customBasePath : "");
                        endpoint.setEndpointPath(path);
                        endpoint.setHttpMethod(method.toUpperCase());
                        endpoint.setOperationId(operation.getString("operationId"));
                        endpoint.setSummary(operation.getString("summary"));
                        endpoint.setDescription(operation.getString("description"));
                        endpoint.setDeprecated(operation.getInteger("deprecated") != null ? 1 : 0);
                        endpoint.setSwaggerVersion(swaggerVersion);

                        JSONArray tags = operation.getJSONArray("tags");
                        if (tags != null) {
                            endpoint.setTags(tags.toString());
                        }

                        endpoints.add(endpoint);
                    }
                }
            }
        }

        if (!endpoints.isEmpty()) {
            endpointDefinitionMapper.batchInsert(endpoints);
        }

        return endpoints.size();
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
        return result;
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