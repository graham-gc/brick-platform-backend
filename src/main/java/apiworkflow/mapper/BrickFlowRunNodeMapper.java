package apiworkflow.mapper;

import apiworkflow.entity.BrickFlowRunNode;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface BrickFlowRunNodeMapper {

    BrickFlowRunNode selectById(Long id);

    List<BrickFlowRunNode> selectByRunId(Long runId);

    BrickFlowRunNode selectByRunIdAndNodeId(@Param("runId") Long runId, @Param("nodeId") Long nodeId);

    int insert(BrickFlowRunNode record);

    int updateById(BrickFlowRunNode record);

    int updateStatus(@Param("id") Long id,
                     @Param("status") String status,
                     @Param("durationMs") Long durationMs,
                     @Param("errorMsg") String errorMsg);
}