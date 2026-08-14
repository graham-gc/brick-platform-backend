package apiworkflow.dto;

import lombok.Data;
import java.io.Serializable;

@Data
public class CopyFlowRequest implements Serializable {
    private String newName;
    private String targetEnv;
    private Integer targetSwaggerMappingId;
    private String operator;
}