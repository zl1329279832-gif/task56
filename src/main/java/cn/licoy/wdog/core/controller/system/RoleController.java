package cn.licoy.wdog.core.controller.system;

import cn.licoy.wdog.common.annotation.SysLogs;
import cn.licoy.wdog.common.bean.ResponseCode;
import cn.licoy.wdog.common.bean.ResponseResult;
import cn.licoy.wdog.common.controller.CrudController;
import cn.licoy.wdog.core.dto.system.role.FindRoleDTO;
import cn.licoy.wdog.core.dto.system.role.PermissionChangeDTO;
import cn.licoy.wdog.core.dto.system.role.RoleAddDTO;
import cn.licoy.wdog.core.dto.system.role.RoleUpdateDTO;
import cn.licoy.wdog.core.entity.system.SysRole;
import cn.licoy.wdog.core.service.system.SysRoleService;
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
    public RoleController(SysRoleService sysRoleService) {
        this.sysRoleService = sysRoleService;
    }

    @Override
    public SysRoleService getService() {
        return sysRoleService;
    }

    @PostMapping(value = {"/preview-permission-change"})
    @ApiOperation(value = "预览权限变更影响")
    @SysLogs("预览权限变更影响")
    @ApiImplicitParam(paramType = "header", name = "Authorization", value = "身份认证Token")
    public ResponseResult previewPermissionChange(@RequestBody @Validated @ApiParam("权限变更数据") PermissionChangeDTO dto) {
        return ResponseResult.e(ResponseCode.OK, sysRoleService.previewPermissionChange(dto));
    }

    @PostMapping(value = {"/apply-permission-change"})
    @ApiOperation(value = "应用权限变更")
    @SysLogs("应用权限变更")
    @ApiImplicitParam(paramType = "header", name = "Authorization", value = "身份认证Token")
    public ResponseResult applyPermissionChange(@RequestBody @Validated @ApiParam("权限变更数据") PermissionChangeDTO dto) {
        return ResponseResult.e(ResponseCode.OK, sysRoleService.applyPermissionChange(dto));
    }

    @PostMapping(value = {"/refresh-result/{changeId}"})
    @ApiOperation(value = "查询权限变更刷新结果")
    @ApiImplicitParam(paramType = "header", name = "Authorization", value = "身份认证Token")
    public ResponseResult getRefreshResult(@PathVariable("changeId") @ApiParam("变更ID") String changeId) {
        return ResponseResult.e(ResponseCode.OK, sysRoleService.getPermissionChangeResult(changeId));
    }
}
