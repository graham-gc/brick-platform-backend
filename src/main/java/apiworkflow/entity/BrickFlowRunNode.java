package apiworkflow.entity;

import lombok.Data;
import java.io.Serializable;
import java.util.Date;

@Data
public class BrickFlowRunNode implements Serializable {
    private Long id;
    private Long runId;
    private Long nodeId;
    private Integer endpointId;
    private Integer grpcEndpointId;
    private String status;
    private Integer httpStatus;
    private Long durationMs;
    private Date startTime;
    private Date endTime;
    private String requestMethod;
    private String requestUrl;
    private String requestHeaders;
    private String requestBody;
    private String requestQueryParams;
    private String requestPathParams;
    private String responseHeaders;
    private String responsePreview;
    private String fullResponse;
    private Integer responseSize;
    private String errorMsg;
    private Integer assertionTotalCount;
    private Integer assertionPassedCount;
    private Integer assertionFailedCount;
    private String assertionSummary;
    private Date createTime;
    private Date updateTime;
}