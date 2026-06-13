package cn.licoy.wdog.core.controller.system;

import cn.licoy.wdog.common.annotation.SysLogs;
import cn.licoy.wdog.common.bean.ResponseCode;
import cn.licoy.wdog.common.bean.ResponseResult;
import cn.licoy.wdog.common.controller.CrudController;
import cn.licoy.wdog.core.dto.system.role.FindRoleDTO;
import cn.licoy.wdog.core.dto.system.role.RoleAddDTO;
import cn.licoy.wdog.core.dto.system.role.RoleResourceUpdateDTO;
import cn.licoy.wdog.core.dto.system.role.RoleUpdateDTO;
import cn.licoy.wdog.core.entity.system.SysRole;
import cn.licoy.wdog.core.service.system.RolePermissionChangeService;
import cn.licoy.wdog.core.service.system.SysRoleService;
import cn.licoy.wdog.core.vo.system.CacheRefreshResultVO;
import cn.licoy.wdog.core.vo.system.RoleChangePreviewVO;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiImplicitParam;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;



/**
 * @author Licoy
 * @version 2018/4/19/9:41
 */
@RestController
@RequestMapping(value = {"/system/role"})
@Api(tags = {"角色管理"})
public class RoleController implements CrudController<SysRole,RoleAddDTO,RoleUpdateDTO,String,FindRoleDTO,SysRoleService>{

    private final SysRoleService sysRoleService;

    @Autowired
    private RolePermissionChangeService rolePermissionChangeService;

    @Autowired
    public RoleController(SysRoleService sysRoleService) {
        this.sysRoleService = sysRoleService;
    }

    @Override
    public SysRoleService getService() {
        return sysRoleService;
    }

    @PostMapping("/preview-impact")
    @ApiOperation(value = "预览角色资源变更影响")
    @SysLogs("预览角色权限变更影响")
    @ApiImplicitParam(paramType = "header", name = "Authorization", value = "身份认证Token")
    public ResponseResult<RoleChangePreviewVO> previewImpact(
            @RequestBody @Validated @ApiParam(value = "角色资源变更数据") RoleResourceUpdateDTO dto) {
        RoleChangePreviewVO preview = rolePermissionChangeService.previewImpact(
                dto.getRoleId(), dto.getResources());
        return ResponseResult.e(ResponseCode.OK, preview);
    }

    @PostMapping("/apply-resource-change")
    @ApiOperation(value = "保存角色资源变更并刷新权限缓存")
    @SysLogs("应用角色资源变更并刷新缓存")
    @ApiImplicitParam(paramType = "header", name = "Authorization", value = "身份认证Token")
    public ResponseResult<CacheRefreshResultVO> applyResourceChange(
            @RequestBody @Validated @ApiParam(value = "角色资源变更数据") RoleResourceUpdateDTO dto) {
        CacheRefreshResultVO result = rolePermissionChangeService.applyChange(
                dto.getRoleId(), dto.getResources());
        return ResponseResult.e(ResponseCode.OK, result);
    }

    @PostMapping("/refresh-result/{roleId}")
    @ApiOperation(value = "查询角色权限缓存刷新结果")
    @SysLogs("查询角色缓存刷新结果")
    @ApiImplicitParam(paramType = "header", name = "Authorization", value = "身份认证Token")
    public ResponseResult<CacheRefreshResultVO> getRefreshResult(
            @PathVariable("roleId") @ApiParam(value = "角色ID") String roleId) {
        CacheRefreshResultVO result = rolePermissionChangeService.getLastRefreshResult(roleId);
        return ResponseResult.e(ResponseCode.OK, result);
    }
}
