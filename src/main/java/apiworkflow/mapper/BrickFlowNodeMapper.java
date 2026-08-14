package apiworkflow.mapper;

import apiworkflow.entity.BrickFlowNode;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface BrickFlowNodeMapper {

    BrickFlowNode selectById(Long id);

    List<BrickFlowNode> selectByFlowId(Integer flowId);

    List<BrickFlowNode> selectByFlowIds(@Param("flowIds") List<Integer> flowIds);

    int insert(BrickFlowNode record);

    int batchInsert(@Param("list") List<BrickFlowNode> list);

    int updateById(BrickFlowNode record);

    int deleteByFlowId(Integer flowId);
}