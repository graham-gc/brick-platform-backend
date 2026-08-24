package apiworkflow.dto;

import lombok.Data;
import javax.validation.Valid;
import javax.validation.constraints.NotEmpty;
import java.io.Serializable;
import java.util.List;

@Data
public class BatchCopyFlowRequest implements Serializable {
    @Valid
    @NotEmpty
    private List<FlowCopyItem> flowCopyItems;
    private String targetEnv;
    private Integer targetSwaggerMappingId;
    private String operator;
}
