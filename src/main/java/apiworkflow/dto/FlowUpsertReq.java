package apiworkflow.dto;

import apiworkflow.entity.BrickFlow;
import apiworkflow.entity.BrickFlowEdge;
import lombok.Data;
import java.io.Serializable;
import java.util.List;

@Data
public class FlowUpsertReq implements Serializable {
    private BrickFlow flow;
    private List<BrickFlowFullNode> nodes;
    private List<BrickFlowEdge> edges;
    private String operator;
}