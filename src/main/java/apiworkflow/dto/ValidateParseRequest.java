package apiworkflow.dto;

import lombok.Data;
import java.io.Serializable;

@Data
public class ValidateParseRequest implements Serializable {
    private String swaggerUrl;
    private String swaggerFileContent;
    private Boolean includeEndpoints;
}