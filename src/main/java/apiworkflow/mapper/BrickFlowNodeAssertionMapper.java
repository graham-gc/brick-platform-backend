package apiworkflow.mapper;

import apiworkflow.entity.BrickFlowNodeAssertion;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface BrickFlowNodeAssertionMapper {

    BrickFlowNodeAssertion selectById(Long id);

    List<BrickFlowNodeAssertion> selectByNodeId(Long nodeId);

    int insert(BrickFlowNodeAssertion record);

    int batchInsert(@Param("list") List<BrickFlowNodeAssertion> list);

    int updateById(BrickFlowNodeAssertion record);

    int deleteByNodeId(Long nodeId);
}