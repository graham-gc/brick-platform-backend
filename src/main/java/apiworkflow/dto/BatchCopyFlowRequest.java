package apiworkflow.dto;

import lombok.Data;
import java.io.Serializable;
import java.util.List;

@Data
public class BatchCopyFlowRequest implements Serializable {
    private List<FlowCopyItem> flowCopyItems;
    private String targetEnv;
    private Integer targetSwaggerMappingId;
    private String operator;
}