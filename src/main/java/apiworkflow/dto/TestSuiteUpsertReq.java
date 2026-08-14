package apiworkflow.dto;

import apiworkflow.entity.BrickTestSuite;
import apiworkflow.entity.BrickTestSuiteFlowMapping;
import lombok.Data;
import java.io.Serializable;
import java.util.List;

@Data
public class TestSuiteUpsertReq implements Serializable {
    private BrickTestSuite suite;
    private List<BrickTestSuiteFlowMapping> flowMappings;
    private String operator;
    private Long reportId;
}