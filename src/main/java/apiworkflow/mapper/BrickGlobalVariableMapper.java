package apiworkflow.mapper;

import apiworkflow.entity.BrickGlobalVariable;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface BrickGlobalVariableMapper {

    BrickGlobalVariable selectById(Long id);

    BrickGlobalVariable selectByName(String name);

    List<BrickGlobalVariable> selectList(BrickGlobalVariable query);

    List<BrickGlobalVariable> selectByType(String type);

    int insert(BrickGlobalVariable record);

    int updateById(BrickGlobalVariable record);

    int deleteById(Long id);
}