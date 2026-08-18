package apiworkflow.mapper;

import apiworkflow.entity.BrickFlowNodeAssertion;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface BrickFlowNodeAssertionMapper {

    @Select("SELECT * FROM brick_flow_node_assertion WHERE id = #{id}")
    BrickFlowNodeAssertion selectById(Long id);

    @Select("SELECT * FROM brick_flow_node_assertion WHERE node_id = #{nodeId}")
    List<BrickFlowNodeAssertion> selectByNodeId(Long nodeId);

    @Insert("INSERT INTO brick_flow_node_assertion (node_id, assertion_type, field_path, operator, expected_value, is_enabled, create_time, update_time) " +
            "VALUES (#{nodeId}, #{assertionType}, #{fieldPath}, #{operator}, #{expectedValue}, #{isEnabled}, #{createTime}, #{updateTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(BrickFlowNodeAssertion record);

    @Insert("<script>" +
            "INSERT INTO brick_flow_node_assertion (node_id, assertion_type, field_path, operator, expected_value, is_enabled, create_time, update_time) VALUES " +
            "<foreach collection='list' item='item' separator=','>" +
            "(#{item.nodeId}, #{item.assertionType}, #{item.fieldPath}, #{item.operator}, #{item.expectedValue}, #{item.isEnabled}, #{item.createTime}, #{item.updateTime})" +
            "</foreach>" +
            "</script>")
    int batchInsert(@Param("list") List<BrickFlowNodeAssertion> list);

    @Update("UPDATE brick_flow_node_assertion SET node_id=#{nodeId}, assertion_type=#{assertionType}, field_path=#{fieldPath}, " +
            "operator=#{operator}, expected_value=#{expectedValue}, is_enabled=#{isEnabled}, update_time=#{updateTime} WHERE id=#{id}")
    int updateById(BrickFlowNodeAssertion record);

    @Delete("DELETE FROM brick_flow_node_assertion WHERE id = #{id}")
    int deleteById(Long id);

    @Delete("DELETE FROM brick_flow_node_assertion WHERE node_id = #{nodeId}")
    int deleteByNodeId(Long nodeId);
}