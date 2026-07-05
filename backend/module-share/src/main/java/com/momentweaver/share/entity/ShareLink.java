package com.momentweaver.share.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * M5 分享链接实体。
 *
 * <p>对应 share_link 表（V1 创建，V4 增强）：
 * <ul>
 *   <li>scope: public（任何人）| password（需要密码）</li>
 *   <li>password_hash: BCrypt 哈希；scope=public 时为 null</li>
 *   <li>token: 32 字符 URL-safe base64，unique key</li>
 *   <li>revoked: 1 = 已撤销（owner 操作或授权撤回级联触发）</li>
 *   <li>expires_at: 过期时间；null = 永不过期（M5 暂不开放，前端创建时强制 1~90 天）</li>
 *   <li>view_count: 公开端成功访问次数（含密码验证后）</li>
 *   <li>subject_ids: 限定可见 subject 集合（逗号分隔）；null = 不限</li>
 *   <li>draft_id: 关联成稿（M5 必填，分享的就是成稿）</li>
 * </ul>
 */
@Data
@TableName("share_link")
public class ShareLink {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long projectId;

    /** 关联成稿 id（M5 必填）。存 MongoDB ObjectId 字符串（24 位十六进制）。 */
    private String draftId;

    /** 限定可见 subject id 集合，逗号分隔；null = 全部可见。 */
    private String subjectIds;

    /** 32 字符 URL-safe base64。 */
    private String token;

    /** public | password。 */
    private String scope;

    /** BCrypt 哈希；scope=public 时为 null。 */
    private String passwordHash;

    private Boolean allowCopy;
    private Boolean allowDownload;

    private Integer viewCount;

    /** 冗余创建者显示名（公开端不查 user 表）。 */
    private String createdByName;

    private LocalDateTime lastAccessedAt;
    private LocalDateTime expiresAt;
    private Boolean revoked;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
