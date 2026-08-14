package apiworkflow.mapper;

import apiworkflow.entity.BrickTestSuiteRun;
import org.apache.ibatis.annotations.*;
import java.util.List;
import java.util.Map;

@Mapper
public interface BrickTestSuiteRunMapper {

    BrickTestSuiteRun selectById(Long id);

    int insert(BrickTestSuiteRun record);

    int updateById(BrickTestSuiteRun record);

    List<Map<String, Object>> selectRunsByCondition(
            @Param("testSuiteId") Integer testSuiteId,
            @Param("swaggerMappingId") Integer swaggerMappingId,
            @Param("suiteName") String suiteName,
            @Param("status") String status,
            @Param("startDate") String startDate,
            @Param("endDate") String endDate,
            @Param("offset") int offset,
            @Param("pageSize") int pageSize);

    int countRunsByCondition(
            @Param("testSuiteId") Integer testSuiteId,
            @Param("swaggerMappingId") Integer swaggerMappingId,
            @Param("suiteName") String suiteName,
            @Param("status") String status,
            @Param("startDate") String startDate,
            @Param("endDate") String endDate);
}