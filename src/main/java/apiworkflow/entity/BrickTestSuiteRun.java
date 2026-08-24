package apiworkflow.entity;

import lombok.Data;
import java.io.Serializable;
import java.util.Date;

@Data
public class BrickTestSuiteRun implements Serializable {
    private Long id;
    private Integer suiteId;
    private String status;
    private String businessStatus;
    private String operator;
    private Integer totalFlows;
    private Integer successFlows;
    private Integer failedFlows;
    private Integer businessPassedFlows;
    private Integer businessFailedFlows;
    private Integer businessNotConfiguredFlows;
    private Integer businessNotEvaluatedFlows;
    private Long durationMs;
    private Date startTime;
    private Date endTime;
    private String errorMsg;
    private Date createTime;
    private Date updateTime;
}
