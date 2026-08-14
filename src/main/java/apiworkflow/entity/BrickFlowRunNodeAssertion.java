package apiworkflow.entity;

import lombok.Data;
import java.io.Serializable;
import java.util.Date;

@Data
public class BrickFlowRunNodeAssertion implements Serializable {
    private Long id;
    private Long runNodeId;
    private Long assertionId;
    private String status;
    private String actualValue;
    private String expectedValue;
    private String errorMsg;
    private Date createTime;
}