package apiworkflow.dto;

import lombok.Data;
import java.io.Serializable;

@Data
public class FlowCopyItem implements Serializable {
    private Integer sourceFlowId;
    private String newFlowName;
}