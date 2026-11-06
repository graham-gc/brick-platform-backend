package apiworkflow.mapper;

import lombok.Data;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SwaggerSyncImpactMapper {

    @Select("SELECT f.id AS flow_id, f.name AS flow_name, n.id AS node_id, n.endpoint_id, "
            + "n.response_variables_json, a.id AS assertion_id, a.field_path AS assertion_path "
            + "FROM brick_endpoint_definition e "
            + "JOIN brick_flow_node n ON n.endpoint_id = e.id AND n.is_deleted = 0 "
            + "JOIN brick_flow f ON f.id = n.flow_id AND f.is_deleted = 0 "
            + "LEFT JOIN brick_flow_node_assertion a ON a.node_id = n.id "
            + "AND a.is_enabled = 1 AND a.assertion_type = 'json_path' "
            + "WHERE e.swagger_mapping_id = #{mappingId} AND e.is_deleted = 0 "
            + "AND (n.node_type IS NULL OR LOWER(n.node_type) = 'http') "
            + "AND (n.response_variables_json IS NOT NULL OR a.id IS NOT NULL) "
            + "ORDER BY f.id, n.id, a.id")
    List<ReferenceRow> selectReferences(@Param("mappingId") Integer mappingId);

    @Data
    class ReferenceRow {
        private Integer flowId;
        private String flowName;
        private Long nodeId;
        private Integer endpointId;
        private String responseVariablesJson;
        private Long assertionId;
        private String assertionPath;
    }
}
