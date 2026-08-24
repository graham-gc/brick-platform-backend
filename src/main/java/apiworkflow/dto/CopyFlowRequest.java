package apiworkflow.dto;

import lombok.Data;
import javax.validation.constraints.NotBlank;
import java.io.Serializable;

@Data
public class CopyFlowRequest implements Serializable {
    @NotBlank
    private String newName;
    private String targetEnv;
    private Integer targetSwaggerMappingId;
    private String operator;
}
