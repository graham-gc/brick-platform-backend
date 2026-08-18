package apiworkflow.dto;

import apiworkflow.entity.BrickFlowNode;
import apiworkflow.entity.BrickFlowNodeAssertion;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
public class BrickFlowFullNode extends BrickFlowNode {
    private List<BrickFlowNodeAssertion> assertions;
    private ConditionConfig condition;

    @Data
    public static class ConditionConfig {
        private String type;
        private String expression;
    }
}