package apiworkflow.dto;

import lombok.Data;
import java.io.Serializable;

@Data
public class RunFlowReq implements Serializable {
    private String overrideBaseUrl;
    private Integer runType;
    private String operator;
}