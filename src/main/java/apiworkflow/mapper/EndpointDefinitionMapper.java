package apiworkflow.mapper;

import apiworkflow.entity.EndpointDefinition;
import org.apache.ibatis.annotations.*;
import java.util.List;
import java.util.Map;

@Mapper
public interface EndpointDefinitionMapper {

    EndpointDefinition selectById(Integer id);

    int insert(EndpointDefinition record);

    int updateById(EndpointDefinition record);

    int softDeleteById(@Param("id") Integer id, @Param("updateBy") String updateBy);

    EndpointDefinition selectByUniqueKeyWithSwaggerMappingId(
            @Param("swaggerMappingId") Integer swaggerMappingId,
            @Param("httpMethod") String httpMethod,
            @Param("endpointPath") String endpointPath,
            @Param("host") String host,
            @Param("basePath") String basePath,
            @Param("isDeleted") Integer isDeleted);

    int countBySwaggerMappingId(@Param("swaggerMappingId") Integer swaggerMappingId,
                                 @Param("httpMethod") String httpMethod,
                                 @Param("keyword") String keyword,
                                 @Param("isDeleted") Integer isDeleted);

    List<EndpointDefinition> selectPageBySwaggerMappingId(
            @Param("swaggerMappingId") Integer swaggerMappingId,
            @Param("httpMethod") String httpMethod,
            @Param("keyword") String keyword,
            @Param("isDeleted") Integer isDeleted,
            @Param("offset") Integer offset,
            @Param("pageSize") Integer pageSize);

    List<EndpointDefinition> selectAllBySwaggerMappingId(@Param("swaggerMappingId") Integer swaggerMappingId, @Param("isDeleted") Integer isDeleted);

    int batchInsert(@Param("list") List<EndpointDefinition> list);

    int batchUpdate(@Param("list") List<EndpointDefinition> list);

    int markDeletedBySwaggerMappingId(@Param("swaggerMappingId") Integer swaggerMappingId, @Param("updateBy") String updateBy);

    List<Map<String, Object>> batchEndpointStats(@Param("swaggerMappingIds") List<Integer> swaggerMappingIds,
                                                   @Param("keyword") String keyword,
                                                   @Param("isDeleted") Integer isDeleted);

    List<Map<String, Object>> batchLatestUpdateTimes(@Param("swaggerMappingIds") List<Integer> swaggerMappingIds, @Param("isDeleted") Integer isDeleted);

    int countCoveredEndpointsBySwaggerMappingId(@Param("swaggerMappingId") Integer swaggerMappingId);
}