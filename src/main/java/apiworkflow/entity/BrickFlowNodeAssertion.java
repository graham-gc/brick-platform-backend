package apiworkflow.entity;

import lombok.Data;
import java.io.Serializable;
import java.util.Date;

@Data
public class BrickFlowNodeAssertion implements Serializable {
    private Long id;
    private Long nodeId;
    private String assertionType;
    private String fieldPath;
    private String operator;
    private String expectedValue;
    private Integer isEnabled;
    private Date createTime;
    private Date updateTime;
}