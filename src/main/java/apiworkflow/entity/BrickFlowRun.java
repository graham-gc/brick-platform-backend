package apiworkflow.entity;

import lombok.Data;
import java.io.Serializable;
import java.util.Date;

@Data
public class BrickFlowRun implements Serializable {
    private Long id;
    private Integer flowId;
    private String status;
    private String triggeredBy;
    private Integer runType;
    private Long durationMs;
    private String errorMsg;
    private String overrideBaseUrl;
    private Date startTime;
    private Date endTime;
    private Long suiteRunId;
    private String batchId;
    private String remark;
    private String createBy;
    private Date createTime;
    private String updateBy;
    private Date updateTime;
}