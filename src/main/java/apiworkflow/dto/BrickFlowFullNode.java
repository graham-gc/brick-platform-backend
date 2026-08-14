package apiworkflow.dto;

import apiworkflow.entity.BrickFlowNode;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
public class BrickFlowFullNode extends BrickFlowNode {
    private List<AssertionConfig> assertions;
    private ConditionConfig condition;

    @Data
    public static class AssertionConfig {
        private Long id;
        private String assertionType;
        private String fieldPath;
        private String operator;
        private String expectedValue;
        private Boolean isEnabled;
    }

    @Data
    public static class ConditionConfig {
        private String type;
        private String expression;
    }
}