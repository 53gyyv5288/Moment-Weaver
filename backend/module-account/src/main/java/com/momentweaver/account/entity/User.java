package com.momentweaver.account.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("`user`")
public class User {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String phone;
    private String email;
    private String passwordHash;
    private String displayName;
    private String avatarUrl;

    /** 0=禁用，1=正常 */
    private Integer status;

    /**
     * 是否为家族管理员。创建家族后由 FamilyService 自动置 1。
     * 一期仅作为标记位（不区分权限强弱），二期可能拆出 PlatformAdmin。
     */
    private Integer isFamilyAdmin;

    /**
     * 是否下次登录强制改密（true = 管理员创建的账号）。
     * 改密成功后由 AccountService 重置为 0。
     */
    private Integer mustChangePassword;

    /**
     * 创建本账号的 userId（NULL=自注册；非空=被管理员创建）。
     * 用于审计追溯。
     */
    private Long createdByUserId;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
