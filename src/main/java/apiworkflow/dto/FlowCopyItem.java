package apiworkflow.dto;

import lombok.Data;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.io.Serializable;

@Data
public class FlowCopyItem implements Serializable {
    @NotNull
    private Integer sourceFlowId;
    @NotBlank
    private String newFlowName;
}
