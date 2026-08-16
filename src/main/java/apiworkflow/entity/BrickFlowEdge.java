package apiworkflow.entity;

import lombok.Data;
import java.io.Serializable;
import java.util.Date;

@Data
public class BrickFlowEdge implements Serializable {
    private Long id;
    private Integer flowId;
    private Long sourceNodeId;
    private Long targetNodeId;
    private String sourceHandle;
    private String targetHandle;
    private String edgeType;
    private String conditionJson;
    private Integer isDeleted;
    private String createBy;
    private Date createTime;
    private String updateBy;
    private Date updateTime;
}
