package apiworkflow.entity;

import lombok.Data;
import java.io.Serializable;
import java.util.Date;

@Data
public class BrickTestSuiteFlowMapping implements Serializable {
    private Long id;
    private Integer suiteId;
    private Integer flowId;
    private Integer executionOrder;
    private Integer isDeleted;
    private String createBy;
    private Date createTime;
    private String updateBy;
    private Date updateTime;
}