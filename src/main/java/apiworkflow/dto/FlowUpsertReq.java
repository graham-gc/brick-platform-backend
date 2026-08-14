package apiworkflow.dto;

import apiworkflow.entity.BrickFlow;
import apiworkflow.entity.BrickFlowEdge;
import apiworkflow.entity.BrickFlowNode;
import lombok.Data;
import java.io.Serializable;
import java.util.List;

@Data
public class FlowUpsertReq implements Serializable {
    private BrickFlow flow;
    private List<BrickFlowNode> nodes;
    private List<BrickFlowEdge> edges;
    private List<BrickFlowFullNode> fullNodes;
    private String operator;
}