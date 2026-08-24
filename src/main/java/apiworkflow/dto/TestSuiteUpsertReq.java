package apiworkflow.dto;

import apiworkflow.entity.BrickTestSuite;
import apiworkflow.entity.BrickTestSuiteFlowMapping;
import lombok.Data;
import javax.validation.Valid;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import java.io.Serializable;
import java.util.List;

@Data
public class TestSuiteUpsertReq implements Serializable {
    @Valid
    @NotNull
    private BrickTestSuite suite;
    @Valid
    @NotEmpty
    private List<BrickTestSuiteFlowMapping> flowMappings;
    private String operator;
    private Long reportId;
}
