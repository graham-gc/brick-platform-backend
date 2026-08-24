package apiworkflow.dto;

import apiworkflow.entity.BrickFlow;
import apiworkflow.entity.BrickFlowEdge;
import lombok.Data;
import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import java.io.Serializable;
import java.util.List;

@Data
public class FlowUpsertReq implements Serializable {
    @Valid
    @NotNull
    private BrickFlow flow;
    @Valid
    @NotNull
    private List<BrickFlowFullNode> nodes;
    @Valid
    @NotNull
    private List<BrickFlowEdge> edges;
    private String operator;
}
