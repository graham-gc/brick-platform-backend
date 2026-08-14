package apiworkflow.mapper;

import apiworkflow.entity.BrickTestSuiteFlowMapping;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface BrickTestSuiteFlowMappingMapper {

    List<BrickTestSuiteFlowMapping> selectBySuiteId(Integer suiteId);

    int insert(BrickTestSuiteFlowMapping record);

    int batchInsert(@Param("list") List<BrickTestSuiteFlowMapping> list);

    int deleteBySuiteId(@Param("suiteId") Integer suiteId, @Param("updateBy") String updateBy);
}