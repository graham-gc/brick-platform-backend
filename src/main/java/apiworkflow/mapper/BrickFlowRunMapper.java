package apiworkflow.mapper;

import apiworkflow.entity.BrickFlowRun;
import org.apache.ibatis.annotations.*;
import java.util.List;
import java.util.Map;

@Mapper
public interface BrickFlowRunMapper {

    BrickFlowRun selectById(Long id);

    List<BrickFlowRun> selectByFlowId(Integer flowId);

    int insert(BrickFlowRun record);

    int updateStatusAndDuration(@Param("id") Long id,
                                 @Param("status") String status,
                                 @Param("durationMs") Long durationMs,
                                 @Param("errorMsg") String errorMsg);

    int updateBatchId(@Param("id") Long id, @Param("batchId") String batchId);

    List<Map<String, Object>> selectRunsBySwaggerMappingId(
            @Param("swaggerMappingId") Integer swaggerMappingId,
            @Param("flowName") String flowName,
            @Param("flowNameLike") String flowNameLike,
            @Param("status") String status,
            @Param("startDate") String startDate,
            @Param("endDate") String endDate,
            @Param("testSuiteRunId") Long testSuiteRunId,
            @Param("batchId") String batchId,
            @Param("runType") Integer runType,
            @Param("distinctLatest") Boolean distinctLatest,
            @Param("offset") int offset,
            @Param("limit") int limit);

    int countRunsBySwaggerMappingId(
            @Param("swaggerMappingId") Integer swaggerMappingId,
            @Param("flowName") String flowName,
            @Param("flowNameLike") String flowNameLike,
            @Param("status") String status,
            @Param("startDate") String startDate,
            @Param("endDate") String endDate,
            @Param("testSuiteRunId") Long testSuiteRunId,
            @Param("batchId") String batchId,
            @Param("runType") Integer runType,
            @Param("distinctLatest") Boolean distinctLatest);

    List<Map<String, Object>> selectRunsByFlowId(
            @Param("flowId") Integer flowId,
            @Param("status") String status,
            @Param("startDate") String startDate,
            @Param("endDate") String endDate,
            @Param("offset") int offset,
            @Param("limit") int limit);

    int countRunsByFlowId(@Param("flowId") Integer flowId,
                           @Param("status") String status,
                           @Param("startDate") String startDate,
                           @Param("endDate") String endDate);
}