package apiworkflow.mapper;

import apiworkflow.entity.BrickTestSuite;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface BrickTestSuiteMapper {

    BrickTestSuite selectById(Integer id);

    List<BrickTestSuite> selectBySwaggerMappingId(
            @Param("swaggerMappingId") Integer swaggerMappingId,
            @Param("keyword") String keyword,
            @Param("offset") int offset,
            @Param("pageSize") int pageSize);

    int countBySwaggerMappingId(@Param("swaggerMappingId") Integer swaggerMappingId, @Param("keyword") String keyword);

    int insert(BrickTestSuite record);

    int updateById(BrickTestSuite record);

    int softDeleteById(@Param("id") Integer id, @Param("updateBy") String updateBy);
}