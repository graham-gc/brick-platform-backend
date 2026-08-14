package apiworkflow.mapper;

import apiworkflow.entity.BrickFlow;
import org.apache.ibatis.annotations.*;
import java.util.List;
import java.util.Date;

@Mapper
public interface BrickFlowMapper {

    BrickFlow selectById(Integer id);

    List<BrickFlow> selectByIds(@Param("ids") List<Integer> ids);

    int insert(BrickFlow record);

    int updateById(BrickFlow record);

    int softDeleteById(@Param("id") Integer id, @Param("updateBy") String updateBy);

    int hardDeleteById(Integer id);

    int hardDeleteByIds(@Param("ids") List<Integer> ids);

    List<BrickFlow> selectPage(@Param("query") BrickFlow query,
                                @Param("offset") int offset,
                                @Param("limit") int limit,
                                @Param("executionStatus") String executionStatus);

    List<BrickFlow> selectPageByEndpoint(@Param("query") BrickFlow query,
                                          @Param("offset") int offset,
                                          @Param("limit") int limit,
                                          @Param("endpointId") Integer endpointId,
                                          @Param("executionStatus") String executionStatus);

    int count(@Param("query") BrickFlow query, @Param("executionStatus") String executionStatus);

    List<BrickFlow> selectTemplatesBySwaggerMappingId(@Param("swaggerMappingId") Integer swaggerMappingId,
                                                       @Param("flowName") String flowName,
                                                       @Param("offset") int offset,
                                                       @Param("limit") int limit);

    int countTemplatesBySwaggerMappingId(@Param("swaggerMappingId") Integer swaggerMappingId, @Param("flowName") String flowName);

    int updateVersion(@Param("flowId") Integer flowId,
                      @Param("newSwaggerMappingId") Integer newSwaggerMappingId,
                      @Param("newAppConfigId") String newAppConfigId,
                      @Param("newEnv") String newEnv,
                      @Param("updateBy") String updateBy,
                      @Param("updateTime") Date updateTime);
}