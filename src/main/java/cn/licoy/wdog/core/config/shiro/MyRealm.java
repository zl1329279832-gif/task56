package cn.licoy.wdog.core.config.shiro;



import cn.licoy.wdog.common.exception.RequestException;
import cn.licoy.wdog.common.util.JwtUtil;
import cn.licoy.wdog.core.config.jwt.JwtToken;
import cn.licoy.wdog.core.entity.system.SysUser;
import cn.licoy.wdog.core.service.system.SysUserService;
import com.baomidou.mybatisplus.mapper.EntityWrapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.shiro.authc.*;
import org.apache.shiro.authz.AuthorizationInfo;
import org.apache.shiro.authz.SimpleAuthorizationInfo;
import org.apache.shiro.cache.Cache;
import org.apache.shiro.cache.CacheManager;
import org.apache.shiro.realm.AuthorizingRealm;
import org.apache.shiro.subject.PrincipalCollection;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;


import java.util.List;

/**
 * @author licoy.cn
 * @version 2017/9/22
 */
@Slf4j
public class MyRealm extends AuthorizingRealm {

    @Autowired
    private SysUserService userService;

    @Autowired
    private CacheManager cacheManager;

    @Override
    public boolean supports(AuthenticationToken token) {
        return token instanceof JwtToken;
    }

    @Override
    protected AuthorizationInfo doGetAuthorizationInfo(PrincipalCollection principalCollection) {
        log.info("Shiro权限验证执行");
        JwtToken jwtToken = new JwtToken();
        BeanUtils.copyProperties(principalCollection.getPrimaryPrincipal(),jwtToken);
        if(jwtToken.getUsername()!=null){
            SimpleAuthorizationInfo info = new SimpleAuthorizationInfo();
            SysUser findUser = userService.findUserByName(jwtToken.getUsername(),true);
            if(findUser!=null){
                if(findUser.getRoles()!=null){
                    findUser.getRoles().forEach(role->{
                        info.addRole(role.getName());
                        if(role.getResources()!=null){
                            role.getResources().forEach(v->{
                                if(!"".equals(v.getPermission().trim())){
                                    info.addStringPermission(v.getPermission());
                                }
                            });
                        }
                    });
                }
                return info;
            }
        }
        throw new DisabledAccountException("用户信息异常，请重新登录！");
    }

    @Override
    protected AuthenticationInfo doGetAuthenticationInfo(AuthenticationToken authenticationToken) throws AuthenticationException {
        JwtToken token  = (JwtToken) authenticationToken;
        SysUser user;
        String username = token.getUsername()!=null ? token.getUsername() : JwtUtil.getUsername(token.getToken());
        try {
            user = userService.selectOne(new EntityWrapper<SysUser>()
                    .eq("username",username)
                    .setSqlSelect("id,username,status,password"));
        }catch (RequestException e){
            throw new DisabledAccountException(e.getMsg());
        }
        if(user==null){
            throw new DisabledAccountException("用户不存在！");
        }
        if(user.getStatus()!=1){
            throw new DisabledAccountException("用户账户已锁定，暂无法登陆！");
        }
        if(token.getUsername()==null) token.setUsername(user.getUsername());
        String sign = JwtUtil.sign(user.getId(), user.getUsername(), user.getPassword());
        if(token.getToken()==null) token.setToken(sign);
        token.setUid(user.getId());
        return new SimpleAuthenticationInfo(token,user.getPassword(),user.getId());
    }

    /**
     * 覆盖授权缓存的 key 策略：使用 username 作为缓存 key。
     * 默认实现使用整个 principal（JwtToken 对象）作为 key，
     * 导致 cache.remove(uid/username) 无法匹配到正确的缓存条目。
     */
    @Override
    protected Object getAuthorizationCacheKey(PrincipalCollection principals) {
        JwtToken jwtToken = new JwtToken();
        BeanUtils.copyProperties(principals.getPrimaryPrincipal(), jwtToken);
        return jwtToken.getUsername();
    }

    /**
     * 清除指定用户的授权缓存
     * @param username 用户名（缓存 key）
     * @param author 是否清空授权信息
     * @param out 是否清空session
     */
    public void clearAuthByUserId(String username,Boolean author, Boolean out){
        Cache<Object, Object> cache = cacheManager
                .getCache(MyRealm.class.getName()+".authorizationCache");
        cache.remove(username);
    }

    /**
     * 批量清除指定用户的授权缓存
     * @param usernameList 用户名列表（缓存 key）
     * @param author 是否清空授权信息
     * @param out 是否清空session
     */
    public void clearAuthByUserIdCollection(List<String> usernameList,Boolean author, Boolean out){
        Cache<Object, Object> cache = cacheManager
                .getCache(MyRealm.class.getName()+".authorizationCache");
        usernameList.forEach(cache::remove);
    }
}
