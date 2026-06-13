package cn.licoy.wdog.core.service.global;

import cn.licoy.wdog.core.entity.system.SysResource;

import java.util.List;
import java.util.Map;

/**
 * @author Licoy
 * @version 2018/4/23/13:59
 */
public interface ShiroService {

    /**
     * 获取拦截器数据
     * @return
     */
    Map<String,String> getFilterChainDefinitionMap();

    /**
     * 迭代所有的资源子集
     * @param resource
     * @param permsList
     */
    void iterationAllResourceInToFilter(SysResource resource,
                                        List<String[]> permsList,List<String[]> anonList);

    /**
     * 重新加载权限
     */
    void reloadPerms();

    /**
     * 清除指定用户的授权缓存
     * @param username 用户名（Shiro 授权缓存 key）
     * @param author 是否清空授权信息
     * @param out 是否清空session
     */
    void clearAuthByUserId(String username,Boolean author, Boolean out);

    /**
     * 批量清除指定用户的授权缓存
     * @param usernameList 用户名列表（Shiro 授权缓存 key）
     * @param author 是否清空授权信息
     * @param out 是否清空session
     */
    void clearAuthByUserIdCollection(List<String> usernameList,Boolean author, Boolean out);

}
