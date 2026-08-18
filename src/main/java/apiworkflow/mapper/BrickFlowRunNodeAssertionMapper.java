package apiworkflow.mapper;

import apiworkflow.entity.BrickFlowRunNodeAssertion;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface BrickFlowRunNodeAssertionMapper {

    @Select("SELECT * FROM brick_flow_run_node_assertion WHERE id = #{id}")
    BrickFlowRunNodeAssertion selectById(Long id);

    @Select("SELECT * FROM brick_flow_run_node_assertion WHERE run_node_id = #{runNodeId}")
    List<BrickFlowRunNodeAssertion> selectByRunNodeId(Long runNodeId);

    @Insert("INSERT INTO brick_flow_run_node_assertion (run_node_id, assertion_id, status, actual_value, expected_value, error_msg, create_time) " +
            "VALUES (#{runNodeId}, #{assertionId}, #{status}, #{actualValue}, #{expectedValue}, #{errorMsg}, #{createTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(BrickFlowRunNodeAssertion record);

    @Insert("<script>" +
            "INSERT INTO brick_flow_run_node_assertion (run_node_id, assertion_id, status, actual_value, expected_value, error_msg, create_time) VALUES " +
            "<foreach collection='list' item='item' separator=','>" +
            "(#{item.runNodeId}, #{item.assertionId}, #{item.status}, #{item.actualValue}, #{item.expectedValue}, #{item.errorMsg}, #{item.createTime})" +
            "</foreach>" +
            "</script>")
    int batchInsert(@Param("list") List<BrickFlowRunNodeAssertion> list);
}
