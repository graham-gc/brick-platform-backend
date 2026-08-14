package apiworkflow.mapper;

import apiworkflow.entity.BrickFlowEdge;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface BrickFlowEdgeMapper {

    BrickFlowEdge selectById(Long id);

    List<BrickFlowEdge> selectByFlowId(Integer flowId);

    int insert(BrickFlowEdge record);

    int batchInsert(@Param("list") List<BrickFlowEdge> list);

    int updateById(BrickFlowEdge record);

    int deleteByFlowId(Integer flowId);
}