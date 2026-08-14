package apiworkflow.service.impl;

import apiworkflow.entity.BrickGlobalVariable;
import apiworkflow.mapper.BrickGlobalVariableMapper;
import apiworkflow.service.IGlobalVariableService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class GlobalVariableServiceImpl implements IGlobalVariableService {

    @Autowired
    private BrickGlobalVariableMapper globalVariableMapper;

    @Override
    public BrickGlobalVariable getById(Long id) {
        return globalVariableMapper.selectById(id);
    }

    @Override
    public BrickGlobalVariable getByName(String name) {
        return globalVariableMapper.selectByName(name);
    }

    @Override
    public List<BrickGlobalVariable> selectList(BrickGlobalVariable query) {
        return globalVariableMapper.selectList(query);
    }

    @Override
    public List<BrickGlobalVariable> selectByType(String type) {
        return globalVariableMapper.selectByType(type);
    }

    @Override
    public int create(BrickGlobalVariable variable, String operator) {
        variable.setCreateBy(operator);
        return globalVariableMapper.insert(variable);
    }

    @Override
    public int update(BrickGlobalVariable variable, String operator) {
        variable.setUpdateBy(operator);
        return globalVariableMapper.updateById(variable);
    }

    @Override
    public int delete(Long id) {
        return globalVariableMapper.deleteById(id);
    }
}