package apiworkflow.mapper;

import apiworkflow.entity.EndpointSchema;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface EndpointSchemaMapper {

    List<EndpointSchema> selectBySwaggerMappingId(
            @Param("swaggerMappingId") Integer swaggerMappingId);

    int batchUpsert(@Param("list") List<EndpointSchema> list);

    int markDeletedBySwaggerMappingId(
            @Param("swaggerMappingId") Integer swaggerMappingId,
            @Param("updateBy") String updateBy);
}
