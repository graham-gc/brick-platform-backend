package apiworkflow.service;

import apiworkflow.entity.BrickGlobalVariable;
import java.util.List;

public interface IGlobalVariableService {

    BrickGlobalVariable getById(Long id);

    BrickGlobalVariable getByName(String name);

    List<BrickGlobalVariable> selectList(BrickGlobalVariable query);

    List<BrickGlobalVariable> selectByType(String type);

    int create(BrickGlobalVariable variable, String operator);

    int update(BrickGlobalVariable variable, String operator);

    int delete(Long id);
}