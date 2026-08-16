package apiworkflow.service;

import apiworkflow.entity.AppSwaggerMapping;
import apiworkflow.entity.EndpointDefinition;
import java.util.List;
import java.util.Map;

public interface IBrickApiService {

    AppSwaggerMapping getById(Integer id);

    AppSwaggerMapping getByEnvAppConfigAndVersion(String env, String appConfigId, String versionTag);

    List<AppSwaggerMapping> getVersionsByEnvAndAppConfig(String env, String appConfigId);

    int createMapping(AppSwaggerMapping record);

    int updateMapping(AppSwaggerMapping record);

    int softDeleteMapping(Integer id, String operator);

    String detectAndFetchSwaggerJson(String rawUrl);

    boolean isValidSwaggerJson(String json);

    String validateSwaggerJsonDetailed(String json);

    int syncEndpointDefinitionsBySwaggerMappingId(
            Integer swaggerMappingId, String apiDocsJson, String operator,
            String customHost, String customBasePath);

    List<EndpointDefinition> selectEndpointPageBySwaggerMappingId(
            Integer swaggerMappingId, String method, String keyword, int offset, int pageSize);

    int countEndpointsBySwaggerMappingId(Integer swaggerMappingId, String method, String keyword);

    Map<String, Object> getEndpointDetail(Integer endpointId);

    Object resolveEndpointSchema(Integer swaggerMappingId, String schemaRef);

    Map<Integer, Map<String, Object>> getBatchEndpointStats(List<Integer> swaggerMappingIds, String keyword);

    int countCoveredEndpointsBySwaggerMappingId(Integer swaggerMappingId);

    List<AppSwaggerMapping> selectList(AppSwaggerMapping query);
}
